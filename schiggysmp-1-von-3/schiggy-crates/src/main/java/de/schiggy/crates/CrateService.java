package de.schiggy.crates;

import de.schiggy.core.database.Database;
import de.schiggy.core.util.Names;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/** Crates aus der config.yml, virtuelle Schluessel, Crate-Bloecke und Gewinn-Verlauf in der Datenbank. */
public final class CrateService {

    public record Reward(double chance, ItemStack display, ItemStack give, List<String> commands,
                         String plainName, boolean broadcast) {
    }

    public record Crate(String id, String name, Material icon, int offer, List<Reward> rewards, double totalChance) {
        public String plainName() {
            return MiniMessage.miniMessage().stripTags(name);
        }
    }

    public record HistoryEntry(String reward, String crate, long time) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SchiggyCrates plugin;
    private final Database db;
    private volatile Map<String, Crate> crates = Map.of();
    private final Map<String, String> blocks = new ConcurrentHashMap<>();
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    public CrateService(SchiggyCrates plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public Set<UUID> processing() {
        return processing;
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fc_keys ("
                        + "uuid TEXT NOT NULL, crate TEXT NOT NULL, amount INTEGER NOT NULL, PRIMARY KEY (uuid, crate))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fc_blocks ("
                        + "world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, "
                        + "crate TEXT NOT NULL, PRIMARY KEY (world, x, y, z))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fc_history ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, name TEXT NOT NULL, "
                        + "crate TEXT NOT NULL, reward TEXT NOT NULL, time INTEGER NOT NULL)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_history ON fc_history(uuid, time)");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, crate FROM fc_blocks");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    blocks.put(key(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), rs.getString(5));
                }
            }
            return null;
        }).join();
        reloadCatalog();
    }

    // ------------------------------------------------------------------ Katalog

    public Map<String, Crate> crates() {
        return crates;
    }

    public Optional<Crate> crate(String id) {
        return Optional.ofNullable(crates.get(id.toLowerCase(java.util.Locale.ROOT)));
    }

    public void reloadCatalog() {
        Map<String, Crate> loaded = new LinkedHashMap<>();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("crates");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                List<Reward> rewards = new ArrayList<>();
                double total = 0;
                for (Map<?, ?> map : section.getMapList("rewards")) {
                    Reward reward = parseReward(id, map);
                    if (reward != null) {
                        rewards.add(reward);
                        total += reward.chance();
                    }
                }
                if (rewards.isEmpty()) {
                    plugin.getLogger().warning("Crate '" + id + "' hat keine gueltigen Belohnungen und wird uebersprungen.");
                    continue;
                }
                Material icon = Material.matchMaterial(section.getString("icon", "CHEST"));
                int offer = 0;
                String offerText = section.getString("offer", "all");
                if (!offerText.equalsIgnoreCase("all")) {
                    try {
                        offer = Math.max(0, Integer.parseInt(offerText));
                    } catch (NumberFormatException ignored) {
                        // ungueltig: alle anbieten
                    }
                }
                loaded.put(id.toLowerCase(java.util.Locale.ROOT), new Crate(id.toLowerCase(java.util.Locale.ROOT),
                        section.getString("name", id), icon == null ? Material.CHEST : icon, offer,
                        List.copyOf(rewards), total));
            }
        }
        this.crates = loaded;
    }

    private Reward parseReward(String crateId, Map<?, ?> map) {
        double chance = map.get("chance") instanceof Number chanceNumber && chanceNumber.doubleValue() > 0
                ? chanceNumber.doubleValue() : 1.0;
        List<String> commands = new ArrayList<>();
        if (map.get("commands") instanceof List<?> raw) {
            for (Object entry : raw) {
                commands.add(String.valueOf(entry));
            }
        }
        boolean isCommand = !commands.isEmpty();
        Object materialName = isCommand ? map.get("icon") : map.get("material");
        Material material = materialName == null ? null : Material.matchMaterial(String.valueOf(materialName));
        if (material == null || !material.isItem() || material.isAir()) {
            plugin.getLogger().warning("Crate '" + crateId + "': Unbekanntes Item '" + materialName + "' wird uebersprungen.");
            return null;
        }
        int amount = !isCommand && map.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        String name = map.get("name") == null ? null : String.valueOf(map.get("name"));

        ItemStack display = new ItemStack(material, Math.min(64, amount));
        List<String> lore = new ArrayList<>();
        if (map.get("lore") instanceof List<?> rawLore) {
            for (Object line : rawLore) {
                lore.add(String.valueOf(line));
            }
        }
        if (name != null || !lore.isEmpty()) {
            display.editMeta(meta -> {
                if (name != null) {
                    meta.displayName(MM.deserialize(name).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                }
                if (!lore.isEmpty()) {
                    List<Component> lines = new ArrayList<>();
                    for (String line : lore) {
                        lines.add(MM.deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                    }
                    meta.lore(lines);
                }
            });
        }
        ItemStack give = isCommand ? null : display.clone();
        if (give != null) {
            give.setAmount(amount);
        }
        String plain = name != null ? MM.stripTags(name) : Names.pretty(material) + (amount > 1 ? " x" + amount : "");
        return new Reward(chance, display, give, List.copyOf(commands), plain,
                Boolean.TRUE.equals(map.get("broadcast")));
    }

    /** Die Belohnungen, aus denen der Spieler diesmal waehlen darf: alle oder N zufaellige. */
    public List<Reward> offer(Crate crate) {
        if (crate.offer() <= 0 || crate.offer() >= crate.rewards().size()) {
            return crate.rewards();
        }
        List<Reward> pool = new ArrayList<>(crate.rewards());
        List<Reward> chosen = new ArrayList<>();
        while (chosen.size() < crate.offer() && !pool.isEmpty()) {
            double total = pool.stream().mapToDouble(Reward::chance).sum();
            double roll = ThreadLocalRandom.current().nextDouble() * total;
            double sum = 0;
            Reward picked = pool.get(pool.size() - 1);
            for (Reward reward : pool) {
                sum += reward.chance();
                if (roll < sum) {
                    picked = reward;
                    break;
                }
            }
            pool.remove(picked);
            chosen.add(picked);
        }
        return chosen;
    }

    /** Waehlt eine Belohnung nach Gewicht. */
    public Reward pick(Crate crate) {
        double roll = ThreadLocalRandom.current().nextDouble() * crate.totalChance();
        double sum = 0;
        for (Reward reward : crate.rewards()) {
            sum += reward.chance();
            if (roll < sum) {
                return reward;
            }
        }
        return crate.rewards().get(crate.rewards().size() - 1);
    }

    // ------------------------------------------------------------------ Schluessel

    public CompletableFuture<Integer> keys(UUID uuid, String crate) {
        return db.<Integer>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM fc_keys WHERE uuid = ? AND crate = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, crate);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).exceptionally(ex -> fail(ex, 0));
    }

    public CompletableFuture<Map<String, Integer>> allKeys(UUID uuid) {
        return db.<Map<String, Integer>>submit(c -> {
            Map<String, Integer> map = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT crate, amount FROM fc_keys WHERE uuid = ? AND amount > 0 ORDER BY crate")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
            return map;
        }).exceptionally(ex -> fail(ex, Map.<String, Integer>of()));
    }

    public CompletableFuture<Void> addKeys(UUID uuid, String crate, int amount) {
        return db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fc_keys(uuid, crate, amount) VALUES(?,?,?) "
                            + "ON CONFLICT(uuid, crate) DO UPDATE SET amount = amount + excluded.amount")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, crate);
                ps.setInt(3, amount);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> fail(ex, null));
    }

    /** Verbraucht atomar einen Schluessel. true, wenn einer da war. */
    public CompletableFuture<Boolean> consumeKey(UUID uuid, String crate) {
        return db.<Boolean>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE fc_keys SET amount = amount - 1 WHERE uuid = ? AND crate = ? AND amount >= 1")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, crate);
                return ps.executeUpdate() == 1;
            }
        }).exceptionally(ex -> fail(ex, Boolean.FALSE));
    }

    // ------------------------------------------------------------------ Verlauf

    public void log(UUID uuid, String name, String crate, String reward) {
        db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fc_history(uuid, name, crate, reward, time) VALUES(?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, crate);
                ps.setString(4, reward);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> fail(ex, null));
    }

    public CompletableFuture<List<HistoryEntry>> history(UUID uuid, int limit) {
        return db.<List<HistoryEntry>>submit(c -> {
            List<HistoryEntry> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT reward, crate, time FROM fc_history WHERE uuid = ? ORDER BY time DESC LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new HistoryEntry(rs.getString(1), rs.getString(2), rs.getLong(3)));
                    }
                }
            }
            return list;
        }).exceptionally(ex -> fail(ex, List.<HistoryEntry>of()));
    }

    // ------------------------------------------------------------------ Crate-Bloecke

    private static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    private static String key(Location loc) {
        return key(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public Optional<Crate> crateAt(Location loc) {
        if (loc.getWorld() == null) {
            return Optional.empty();
        }
        String id = blocks.get(key(loc));
        return id == null ? Optional.empty() : crate(id);
    }

    public boolean isCrateBlock(Location loc) {
        return loc.getWorld() != null && blocks.containsKey(key(loc));
    }

    public void setBlock(Location loc, String crate) {
        blocks.put(key(loc), crate);
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fc_blocks(world, x, y, z, crate) VALUES(?,?,?,?,?) "
                            + "ON CONFLICT(world, x, y, z) DO UPDATE SET crate = excluded.crate")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.setString(5, crate);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> fail(ex, null));
    }

    public boolean removeBlock(Location loc) {
        boolean existed = blocks.remove(key(loc)) != null;
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> fail(ex, null));
        return existed;
    }

    private <T> T fail(Throwable ex, T fallback) {
        plugin.getLogger().log(Level.SEVERE, "Datenbankfehler in SchiggyCrates", ex);
        return fallback;
    }
}
