package de.schiggy.extras;

import de.schiggy.core.database.Database;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Inventories;
import de.schiggy.core.util.Names;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.worth.SchiggyWorth;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Spawner-System. Gekaufte oder gegebene Spawner-Items werden platziert und "produzieren" virtuelle Mobs:
 * Statt echter Mobs sammeln sich Drops und Erfahrung im Lager des Spawners (Rechtsklick oeffnet das Menue).
 * Gleiche Spawner lassen sich per Rechtsklick stapeln. Alles liegt in der Datenbank.
 */
final class Spawners implements Listener, TabExecutor {

    /** Ein platzierter Spawner. Lager und XP werden ueber synchronized (this) geschuetzt. */
    static final class Spawner {
        volatile long id = -1;
        final UUID owner;
        final String world;
        final int x;
        final int y;
        final int z;
        final EntityType type;
        int stack;
        long xp;
        double fraction;
        boolean dirty;
        final Map<Material, Long> items = new LinkedHashMap<>();

        Spawner(UUID owner, String world, int x, int y, int z, EntityType type, int stack) {
            this.owner = owner;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.stack = stack;
        }

        long storedItems() {
            long total = 0;
            for (long count : items.values()) {
                total += count;
            }
            return total;
        }
    }

    record Drop(Material material, int min, int max, double chance) {
    }

    record MobConfig(double seconds, long xp, long value, List<Drop> drops) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SchiggyExtras plugin;
    private final Database db;
    private final Map<String, Spawner> spawners = new ConcurrentHashMap<>();
    private volatile Map<EntityType, MobConfig> mobs = Map.of();
    private final NamespacedKey typeKey;
    private final NamespacedKey stackKey;

    Spawners(SchiggyExtras plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.typeKey = new NamespacedKey(plugin, "spawner_type");
        this.stackKey = new NamespacedKey(plugin, "spawner_stack");
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    // ------------------------------------------------------------------ Start

    void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS sx_spawners ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, world TEXT NOT NULL, "
                        + "x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type TEXT NOT NULL, "
                        + "stack INTEGER NOT NULL, xp INTEGER NOT NULL, items TEXT NOT NULL, UNIQUE(world, x, y, z))");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, owner, world, x, y, z, type, stack, xp, items FROM sx_spawners");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EntityType type;
                    try {
                        type = EntityType.valueOf(rs.getString(7));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    Spawner s = new Spawner(UUID.fromString(rs.getString(2)), rs.getString(3), rs.getInt(4),
                            rs.getInt(5), rs.getInt(6), type, rs.getInt(8));
                    s.id = rs.getLong(1);
                    s.xp = rs.getLong(9);
                    for (String part : rs.getString(10).split(";")) {
                        String[] fields = part.split(":");
                        Material material = fields.length == 2 ? Material.matchMaterial(fields[0]) : null;
                        if (material != null) {
                            try {
                                s.items.put(material, Long.parseLong(fields[1]));
                            } catch (NumberFormatException ignored) {
                                // beschaedigter Eintrag
                            }
                        }
                    }
                    spawners.put(key(s.world, s.x, s.y, s.z), s);
                }
            }
            return null;
        }).join();
        reloadMobs();
        long interval = Math.max(1, plugin.getConfig().getLong("spawners.interval-seconds", 5));
        SchiggyScheduler.asyncRepeating(plugin, this::produce, interval, interval);
        SchiggyScheduler.asyncRepeating(plugin, this::flush, 60, 60);
    }

    void reloadMobs() {
        Map<EntityType, MobConfig> loaded = new LinkedHashMap<>();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("spawners.mobs");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                EntityType type;
                try {
                    type = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("spawners.mobs: Unbekannter Mob '" + name + "'");
                    continue;
                }
                ConfigurationSection section = root.getConfigurationSection(name);
                if (section == null) {
                    continue;
                }
                List<Drop> drops = new ArrayList<>();
                for (Map<?, ?> map : section.getMapList("drops")) {
                    Material material = map.get("material") == null ? null : Material.matchMaterial(String.valueOf(map.get("material")));
                    if (material == null) {
                        continue;
                    }
                    int min = map.get("min") instanceof Number n ? Math.max(0, n.intValue()) : 0;
                    int max = map.get("max") instanceof Number n ? Math.max(min, n.intValue()) : min;
                    double chance = map.get("chance") instanceof Number n ? n.doubleValue() : 1.0;
                    drops.add(new Drop(material, min, max, chance));
                }
                loaded.put(type, new MobConfig(Math.max(1, section.getDouble("seconds", 20)), section.getLong("xp", 0),
                        Currency.MONEY.toMinor(section.getDouble("value", 0)), List.copyOf(drops)));
            }
        }
        this.mobs = loaded;
    }

    // ------------------------------------------------------------------ Hilfen

    static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    static String key(Block block) {
        return key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    boolean isRegistered(Block block) {
        return spawners.containsKey(key(block));
    }

    private static String pretty(EntityType type) {
        StringBuilder out = new StringBuilder();
        for (String part : type.name().toLowerCase(Locale.ROOT).split("_")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private boolean ownerOrAdmin(Player player, Spawner s) {
        return s.owner.equals(player.getUniqueId()) || player.hasPermission("schiggy.spawners.admin");
    }

    /** Das Spawner-Item. stack = wie viele Spawner im Stack stecken. */
    ItemStack item(EntityType type, int stack, int amount) {
        ItemStack item = new ItemStack(Material.SPAWNER, Math.max(1, Math.min(64, amount)));
        item.editMeta(meta -> {
            meta.displayName(MM.deserialize(msg().raw("spawner-item-name", "mob", pretty(type)))
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            List<Component> lore = new ArrayList<>();
            for (String line : msg().rawList("spawner-item-lore", "stack", String.valueOf(stack))) {
                lore.add(MM.deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.name());
            meta.getPersistentDataContainer().set(stackKey, PersistentDataType.INTEGER, stack);
        });
        return item;
    }

    /** Mob-Typ eines Spawner-Items oder null. */
    EntityType typeOf(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) {
            return null;
        }
        String name = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (name == null) {
            return null;
        }
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    int stackOf(ItemStack item) {
        Integer stack = item.getItemMeta().getPersistentDataContainer().get(stackKey, PersistentDataType.INTEGER);
        return stack == null ? 1 : Math.max(1, stack);
    }

    // ------------------------------------------------------------------ Fuer die Sell Wand

    List<Spawner> inBox(String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        List<Spawner> list = new ArrayList<>();
        for (Spawner s : spawners.values()) {
            if (s.world.equals(world) && s.x >= minX && s.x <= maxX && s.y >= minY && s.y <= maxY
                    && s.z >= minZ && s.z <= maxZ) {
                list.add(s);
            }
        }
        return list;
    }

    /** Verkaufswert in Cent: Spawner-Wert mal Stack plus Lager zum Worth-Preis. */
    long value(Spawner s) {
        MobConfig config = mobs.get(s.type);
        long total = config == null ? 0 : config.value() * s.stack;
        synchronized (s) {
            for (Map.Entry<Material, Long> entry : s.items.entrySet()) {
                total += SchiggyWorth.getInstance().service().getPrice(entry.getKey()) * entry.getValue();
            }
        }
        return total;
    }

    /** Entfernt den Spawner atomar. true nur beim ersten Aufruf: so wird nie doppelt ausgezahlt. */
    boolean remove(Spawner s) {
        boolean removed = spawners.remove(key(s.world, s.x, s.y, s.z), s);
        if (removed && s.id > 0) {
            long id = s.id;
            db.<Void>submit(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM sx_spawners WHERE id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                return null;
            }).exceptionally(ex -> {
                plugin.getLogger().log(Level.SEVERE, "Spawner konnte nicht geloescht werden", ex);
                return null;
            });
        }
        return removed;
    }

    // ------------------------------------------------------------------ Produktion und Speichern

    private void produce() {
        double interval = Math.max(1, plugin.getConfig().getLong("spawners.interval-seconds", 5));
        long cap = plugin.getConfig().getLong("spawners.max-storage", 20000);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Spawner s : spawners.values()) {
            MobConfig config = mobs.get(s.type);
            if (config == null) {
                continue;
            }
            synchronized (s) {
                double made = s.stack * interval / config.seconds() + s.fraction;
                long whole = (long) made;
                s.fraction = made - whole;
                if (whole <= 0) {
                    continue;
                }
                long total = s.storedItems();
                for (Drop drop : config.drops()) {
                    if (total >= cap) {
                        break;
                    }
                    double expected = whole * drop.chance() * (drop.min() + drop.max()) / 2.0;
                    long count = (long) expected;
                    if (random.nextDouble() < expected - count) {
                        count++;
                    }
                    count = Math.min(count, cap - total);
                    if (count > 0) {
                        s.items.merge(drop.material(), count, Long::sum);
                        total += count;
                    }
                }
                s.xp += whole * config.xp();
                s.dirty = true;
            }
        }
    }

    /** Schreibt alle geaenderten Spawner in die Datenbank. */
    CompletableFuture<Void> flush() {
        List<Object[]> rows = new ArrayList<>();
        for (Spawner s : spawners.values()) {
            synchronized (s) {
                if (!s.dirty || s.id <= 0) {
                    continue;
                }
                s.dirty = false;
                StringBuilder items = new StringBuilder();
                for (Map.Entry<Material, Long> entry : s.items.entrySet()) {
                    if (items.length() > 0) {
                        items.append(';');
                    }
                    items.append(entry.getKey().name()).append(':').append(entry.getValue());
                }
                rows.add(new Object[]{s.id, s.stack, s.xp, items.toString()});
            }
        }
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return db.<Void>transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE sx_spawners SET stack = ?, xp = ?, items = ? WHERE id = ?")) {
                for (Object[] row : rows) {
                    ps.setInt(1, (Integer) row[1]);
                    ps.setLong(2, (Long) row[2]);
                    ps.setString(3, (String) row[3]);
                    ps.setLong(4, (Long) row[0]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Spawner konnten nicht gespeichert werden", ex);
            return null;
        });
    }

    private void insert(Spawner s) {
        db.<Long>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO sx_spawners(owner, world, x, y, z, type, stack, xp, items) VALUES(?,?,?,?,?,?,?,0,'')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, s.owner.toString());
                ps.setString(2, s.world);
                ps.setInt(3, s.x);
                ps.setInt(4, s.y);
                ps.setInt(5, s.z);
                ps.setString(6, s.type.name());
                ps.setInt(7, s.stack);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            }
        }).thenAccept(id -> {
            s.id = id;
            synchronized (s) {
                s.dirty = true;
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Spawner konnte nicht gespeichert werden", ex);
            return null;
        });
    }

    // ------------------------------------------------------------------ Ereignisse

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        EntityType type = typeOf(event.getItemInHand());
        if (type == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("schiggy.spawners.use") || !mobs.containsKey(type)) {
            event.setCancelled(true);
            msg().send(player, "no-permission");
            return;
        }
        int limit = plugin.getConfig().getInt("spawners.max-per-player", 50);
        long owned = spawners.values().stream().filter(s -> s.owner.equals(player.getUniqueId())).count();
        if (owned >= limit && !player.hasPermission("schiggy.spawners.admin")) {
            event.setCancelled(true);
            msg().send(player, "spawner-limit", "limit", String.valueOf(limit));
            return;
        }
        int stack = stackOf(event.getItemInHand());
        Block block = event.getBlockPlaced();
        if (block.getState() instanceof CreatureSpawner creatureSpawner) {
            creatureSpawner.setSpawnedType(type);
            creatureSpawner.update(true, false);
        }
        Spawner s = new Spawner(player.getUniqueId(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), type, stack);
        spawners.put(key(block), s);
        insert(s);
        msg().send(player, "spawner-placed", "mob", pretty(type), "stack", String.valueOf(stack));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (SchiggyExtras.INTERNAL.get()) {
            return;
        }
        Block block = event.getBlock();
        Spawner s = spawners.get(key(block));
        if (s == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!ownerOrAdmin(player, s)) {
            event.setCancelled(true);
            msg().send(player, "spawner-not-yours", "owner", String.valueOf(Bukkit.getOfflinePlayer(s.owner).getName()));
            return;
        }
        synchronized (s) {
            if (!s.items.isEmpty()) {
                event.setCancelled(true);
                msg().send(player, "spawner-not-empty");
                return;
            }
        }
        if (!remove(s)) {
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item(s.type, s.stack, 1));
        msg().send(player, "spawner-broken");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) {
            return;
        }
        Spawner s = spawners.get(key(block));
        if (s == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (plugin.tools().isTool(hand)) {
            return; // die Sell Wand wird in Tools behandelt
        }
        event.setCancelled(true);
        if (!ownerOrAdmin(player, s)) {
            msg().send(player, "spawner-not-yours", "owner", String.valueOf(Bukkit.getOfflinePlayer(s.owner).getName()));
            return;
        }
        EntityType held = typeOf(hand);
        if (held == null) {
            open(player, s);
            return;
        }
        if (held != s.type) {
            open(player, s);
            return;
        }
        int add = stackOf(hand) * hand.getAmount();
        int max = plugin.getConfig().getInt("spawners.max-stack", 1000);
        synchronized (s) {
            if (s.stack + add > max) {
                msg().send(player, "spawner-max-stack", "max", String.valueOf(max));
                return;
            }
            s.stack += add;
            s.dirty = true;
        }
        player.getInventory().setItemInMainHand(null);
        msg().send(player, "spawner-stacked", "stack", String.valueOf(s.stack));
    }

    /** Unsere Spawner spawnen keine echten Mobs. */
    @EventHandler(ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        Location loc = event.getSpawner().getLocation();
        if (loc.getWorld() != null && spawners.containsKey(key(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isRegistered);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isRegistered);
    }

    // ------------------------------------------------------------------ Menue

    void open(Player player, Spawner s) {
        MobConfig config = mobs.get(s.type);
        List<Map.Entry<Material, Long>> entries;
        long xp;
        long stored;
        int stack;
        synchronized (s) {
            entries = new ArrayList<>(s.items.entrySet());
            xp = s.xp;
            stored = s.storedItems();
            stack = s.stack;
        }
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        String mob = pretty(s.type);
        SchiggyGui gui = new SchiggyGui(msg().component("gui-spawner-title", "mob", mob, "stack", String.valueOf(stack)), 3);

        for (int i = 0; i < entries.size() && i < 9; i++) {
            Material material = entries.get(i).getKey();
            long count = entries.get(i).getValue();
            gui.set(9 + i, ItemBuilder.of(material).amount((int) Math.min(64, count))
                    .name(msg().raw("gui-spawner-item-name", "item", Names.pretty(material)))
                    .lore(msg().rawList("gui-spawner-item-lore", "count", String.valueOf(count)))
                    .build(), event -> take(player, s, material, event.getClick().isRightClick()));
        }
        gui.set(19, ItemBuilder.of(Material.EXPERIENCE_BOTTLE).name(msg().raw("gui-spawner-xp-name"))
                .lore(msg().rawList("gui-spawner-xp-lore", "xp", String.valueOf(xp))).build(),
                event -> collectXp(player, s));
        long value = 0;
        synchronized (s) {
            for (Map.Entry<Material, Long> entry : s.items.entrySet()) {
                value += SchiggyWorth.getInstance().service().getPrice(entry.getKey()) * entry.getValue();
            }
        }
        gui.set(22, ItemBuilder.of(Material.GOLD_INGOT).name(msg().raw("gui-spawner-sell-name"))
                .lore(msg().rawList("gui-spawner-sell-lore", "value", economy().format(Currency.MONEY, value))).build(),
                event -> sellStorage(player, s));
        double perMinute = config == null ? 0 : stack * 60.0 / config.seconds();
        gui.set(25, ItemBuilder.of(Material.SPAWNER).name(msg().raw("gui-spawner-info-name", "mob", mob))
                .lore(msg().rawList("gui-spawner-info-lore", "stack", String.valueOf(stack),
                        "rate", String.format(Locale.US, "%.1f", perMinute), "stored", String.valueOf(stored),
                        "max", String.valueOf(plugin.getConfig().getLong("spawners.max-storage", 20000)))).build());
        gui.open(player);
    }

    private void take(Player player, Spawner s, Material material, boolean many) {
        long available;
        synchronized (s) {
            available = s.items.getOrDefault(material, 0L);
        }
        int maxStack = material.getMaxStackSize();
        int space = Inventories.freeSlots(player) * maxStack;
        long want = many ? Math.min(available, Math.min(space, 2304)) : Math.min(available, Math.min(space, maxStack));
        if (want <= 0) {
            msg().send(player, available <= 0 ? "spawner-sold-none" : "spawner-inventory-full");
            return;
        }
        long taken;
        synchronized (s) {
            long current = s.items.getOrDefault(material, 0L);
            taken = Math.min(want, current);
            if (taken <= 0) {
                return;
            }
            if (current - taken <= 0) {
                s.items.remove(material);
            } else {
                s.items.put(material, current - taken);
            }
            s.dirty = true;
        }
        Inventories.giveMaterial(player, material, (int) taken);
        msg().send(player, "spawner-taken", "amount", String.valueOf(taken), "item", Names.pretty(material));
        open(player, s);
    }

    private void collectXp(Player player, Spawner s) {
        long xp;
        synchronized (s) {
            xp = s.xp;
            s.xp = 0;
            s.dirty = true;
        }
        if (xp <= 0) {
            msg().send(player, "spawner-xp-none");
        } else {
            player.giveExp((int) Math.min(xp, Integer.MAX_VALUE));
            msg().send(player, "spawner-xp", "xp", String.valueOf(xp));
        }
        open(player, s);
    }

    private void sellStorage(Player player, Spawner s) {
        Map<Material, Long> snapshot;
        synchronized (s) {
            snapshot = new LinkedHashMap<>(s.items);
            s.items.clear();
            s.dirty = true;
        }
        long total = 0;
        long items = 0;
        Map<Material, Long> unsold = new LinkedHashMap<>();
        for (Map.Entry<Material, Long> entry : snapshot.entrySet()) {
            long price = SchiggyWorth.getInstance().service().getPrice(entry.getKey());
            if (price <= 0) {
                unsold.put(entry.getKey(), entry.getValue());
            } else {
                total += price * entry.getValue();
                items += entry.getValue();
            }
        }
        if (!unsold.isEmpty()) {
            restore(s, unsold);
        }
        if (total <= 0) {
            msg().send(player, "spawner-sold-none");
            return;
        }
        long soldTotal = total;
        long soldItems = items;
        Map<Material, Long> sold = new LinkedHashMap<>(snapshot);
        sold.keySet().removeAll(unsold.keySet());
        economy().deposit(player.getUniqueId(), Currency.MONEY, soldTotal, "SPAWNER_SELL", "items=" + soldItems)
                .thenAccept(result -> SchiggyScheduler.entity(plugin, player, () -> {
                    if (result.status() == EconomyService.Status.SUCCESS) {
                        msg().send(player, "spawner-sold", "items", String.valueOf(soldItems),
                                "amount", economy().format(Currency.MONEY, soldTotal));
                    } else {
                        restore(s, sold);
                        msg().send(player, "error");
                    }
                    open(player, s);
                }));
    }

    private void restore(Spawner s, Map<Material, Long> items) {
        synchronized (s) {
            for (Map.Entry<Material, Long> entry : items.entrySet()) {
                s.items.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
            s.dirty = true;
        }
    }

    // ------------------------------------------------------------------ Befehle

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "give" -> give(sender, args);
            case "list" -> list(sender);
            case "reload" -> {
                if (!sender.hasPermission("schiggy.spawners.admin")) {
                    msg().send(sender, "no-permission");
                } else {
                    plugin.reload();
                    msg().send(sender, "reloaded");
                }
            }
            default -> msg().send(sender, "spawner-usage");
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("schiggy.spawners.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            msg().send(sender, "spawner-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg().send(sender, "player-offline");
            return;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = null;
        }
        if (type == null || !mobs.containsKey(type)) {
            StringBuilder names = new StringBuilder();
            for (EntityType known : mobs.keySet()) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(known.name().toLowerCase(Locale.ROOT));
            }
            msg().send(sender, "spawner-unknown", "mob", args[2], "mobs", names.toString());
            return;
        }
        int amount = 1;
        int stack = 1;
        try {
            if (args.length > 3) {
                amount = Math.max(1, Math.min(2304, Integer.parseInt(args[3])));
            }
            if (args.length > 4) {
                stack = Math.max(1, Math.min(plugin.getConfig().getInt("spawners.max-stack", 1000), Integer.parseInt(args[4])));
            }
        } catch (NumberFormatException e) {
            msg().send(sender, "spawner-usage");
            return;
        }
        EntityType chosen = type;
        int count = amount;
        int perItem = stack;
        SchiggyScheduler.entity(plugin, target, () -> {
            int remaining = count;
            while (remaining > 0) {
                int part = Math.min(64, remaining);
                Inventories.give(target, item(chosen, perItem, part));
                remaining -= part;
            }
            msg().send(target, "spawner-received", "amount", String.valueOf(count), "mob", pretty(chosen));
        });
        msg().send(sender, "spawner-given", "player", target.getName(), "amount", String.valueOf(count), "mob", pretty(chosen));
    }

    private void list(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        boolean any = false;
        for (Spawner s : spawners.values()) {
            if (s.owner.equals(player.getUniqueId())) {
                if (!any) {
                    msg().send(player, "spawner-list-header");
                    any = true;
                }
                msg().send(player, "spawner-list-line", "mob", pretty(s.type), "stack", String.valueOf(s.stack),
                        "x", String.valueOf(s.x), "y", String.valueOf(s.y), "z", String.valueOf(s.z), "world", s.world);
            }
        }
        if (!any) {
            msg().send(player, "spawner-list-none");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("schiggy.spawners.admin");
        if (args.length == 1) {
            out.add("list");
            if (admin) {
                out.addAll(List.of("give", "reload"));
            }
        } else if (admin && args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                out.add(online.getName());
            }
        } else if (admin && args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (EntityType type : mobs.keySet()) {
                out.add(type.name().toLowerCase(Locale.ROOT));
            }
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
