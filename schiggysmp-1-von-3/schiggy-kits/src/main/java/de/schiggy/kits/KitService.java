package de.schiggy.kits;

import de.schiggy.core.database.Database;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Speichert Kits in der Datenbank und haelt sie im Speicher (fuer das Menue). Wartezeiten werden atomar in der
 * Datenbank gesetzt, damit ein Kit nicht durch Doppelklick zweimal abgeholt werden kann.
 */
public final class KitService {

    public record EffectSpec(String type, int seconds, int amplifier) {
    }

    public record Kit(String name, String display, Material icon, long cooldownSeconds,
                      List<ItemStack> items, List<EffectSpec> effects) {
    }

    private final SchiggyKits plugin;
    private final Database db;
    private final Map<String, Kit> kits = new ConcurrentHashMap<>();
    /** Spieler -> (Kit-Name klein -> naechste erlaubte Abholung in ms) */
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public KitService(SchiggyKits plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fk_kits ("
                        + "name TEXT PRIMARY KEY COLLATE NOCASE, display TEXT NOT NULL, icon TEXT NOT NULL, "
                        + "cooldown INTEGER NOT NULL, items BLOB NOT NULL, effects TEXT NOT NULL, created INTEGER NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fk_cooldowns ("
                        + "uuid TEXT NOT NULL, kit TEXT NOT NULL COLLATE NOCASE, next_use INTEGER NOT NULL, "
                        + "PRIMARY KEY (uuid, kit))");
            }
            return null;
        }).join();
        reload().join();
    }

    /** Liest alle Kits neu aus der Datenbank. */
    public CompletableFuture<Integer> reload() {
        return db.<Integer>submit(c -> {
            Map<String, Kit> loaded = new ConcurrentHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, display, icon, cooldown, items, effects FROM fk_kits");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Material icon = Material.matchMaterial(rs.getString(3));
                    List<ItemStack> items;
                    try {
                        items = unpack(rs.getBytes(5));
                    } catch (IOException e) {
                        throw new SQLException("Kit " + rs.getString(1) + " ist beschaedigt", e);
                    }
                    Kit kit = new Kit(rs.getString(1), rs.getString(2), icon == null ? Material.CHEST : icon,
                            rs.getLong(4), items, decodeEffects(rs.getString(6)));
                    loaded.put(kit.name().toLowerCase(Locale.ROOT), kit);
                }
            }
            kits.clear();
            kits.putAll(loaded);
            return loaded.size();
        });
    }

    // ------------------------------------------------------------------ Kits

    public List<Kit> all() {
        List<Kit> list = new ArrayList<>(kits.values());
        list.sort(Comparator.comparing(kit -> kit.name().toLowerCase(Locale.ROOT)));
        return list;
    }

    public Optional<Kit> get(String name) {
        return Optional.ofNullable(kits.get(name.toLowerCase(Locale.ROOT)));
    }

    public CompletableFuture<Void> save(Kit kit) {
        byte[] packed;
        try {
            packed = pack(kit.items());
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        kits.put(kit.name().toLowerCase(Locale.ROOT), kit);
        return db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fk_kits(name, display, icon, cooldown, items, effects, created) VALUES(?,?,?,?,?,?,?) "
                            + "ON CONFLICT(name) DO UPDATE SET display = excluded.display, icon = excluded.icon, "
                            + "cooldown = excluded.cooldown, items = excluded.items, effects = excluded.effects")) {
                ps.setString(1, kit.name());
                ps.setString(2, kit.display());
                ps.setString(3, kit.icon().name());
                ps.setLong(4, kit.cooldownSeconds());
                ps.setBytes(5, packed);
                ps.setString(6, encodeEffects(kit.effects()));
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Kit konnte nicht gespeichert werden", ex);
            return null;
        });
    }

    public CompletableFuture<Boolean> delete(String name) {
        boolean existed = kits.remove(name.toLowerCase(Locale.ROOT)) != null;
        return db.<Boolean>transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM fk_kits WHERE name = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM fk_cooldowns WHERE kit = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            return existed;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Kit konnte nicht geloescht werden", ex);
            return existed;
        });
    }

    // ------------------------------------------------------------------ Wartezeiten

    public CompletableFuture<Void> loadPlayer(UUID uuid) {
        return db.<Void>submit(c -> {
            Map<String, Long> map = new ConcurrentHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT kit, next_use FROM fk_cooldowns WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1).toLowerCase(Locale.ROOT), rs.getLong(2));
                    }
                }
            }
            cooldowns.put(uuid, map);
            return null;
        });
    }

    public void unload(UUID uuid) {
        cooldowns.remove(uuid);
    }

    /** Verbleibende Wartezeit in Millisekunden (0 = bereit). */
    public long remainingMillis(UUID uuid, Kit kit) {
        Map<String, Long> map = cooldowns.get(uuid);
        if (map == null) {
            return 0;
        }
        long next = map.getOrDefault(kit.name().toLowerCase(Locale.ROOT), 0L);
        return Math.max(0, next - System.currentTimeMillis());
    }

    private void cacheCooldown(UUID uuid, Kit kit, long nextUse) {
        cooldowns.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>()).put(kit.name().toLowerCase(Locale.ROOT), nextUse);
    }

    /** true, wenn die Wartezeit jetzt gestartet wurde (Abholung erlaubt). */
    public CompletableFuture<Boolean> startCooldown(UUID uuid, Kit kit, long cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return CompletableFuture.completedFuture(true);
        }
        return db.<Long>transaction(c -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement("SELECT next_use FROM fk_cooldowns WHERE uuid = ? AND kit = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, kit.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong(1) > now) {
                        return rs.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fk_cooldowns(uuid, kit, next_use) VALUES(?,?,?) "
                            + "ON CONFLICT(uuid, kit) DO UPDATE SET next_use = excluded.next_use")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, kit.name());
                ps.setLong(3, now + cooldownSeconds * 1000L);
                ps.executeUpdate();
            }
            return 0L;
        }).thenApply(next -> {
            if (next == 0L) {
                cacheCooldown(uuid, kit, System.currentTimeMillis() + cooldownSeconds * 1000L);
                return true;
            }
            cacheCooldown(uuid, kit, next);
            return false;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Wartezeit konnte nicht gesetzt werden", ex);
            return false;
        });
    }

    // ------------------------------------------------------------------ Umwandlung

    static byte[] pack(List<ItemStack> items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(items.size());
            for (ItemStack item : items) {
                byte[] data = item.serializeAsBytes();
                out.writeInt(data.length);
                out.write(data);
            }
        }
        return bytes.toByteArray();
    }

    static List<ItemStack> unpack(byte[] data) throws IOException {
        List<ItemStack> list = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                byte[] item = new byte[in.readInt()];
                in.readFully(item);
                list.add(ItemStack.deserializeBytes(item));
            }
        }
        return list;
    }

    static String encodeEffects(List<EffectSpec> effects) {
        StringBuilder out = new StringBuilder();
        for (EffectSpec effect : effects) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(effect.type()).append(':').append(effect.seconds()).append(':').append(effect.amplifier());
        }
        return out.toString();
    }

    static List<EffectSpec> decodeEffects(String text) {
        List<EffectSpec> list = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return list;
        }
        for (String part : text.split(";")) {
            String[] fields = part.split(":");
            if (fields.length == 3) {
                try {
                    list.add(new EffectSpec(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[2])));
                } catch (NumberFormatException ignored) {
                    // beschaedigter Eintrag wird uebersprungen
                }
            }
        }
        return list;
    }
}
