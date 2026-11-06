package de.schiggy.worth;

import de.schiggy.core.database.Database;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Zentrale Preisquelle. Preise werden als ganze Zahl in Cent gespeichert.
 * Lesen laeuft ueber den Cache (sofort, auch aus anderen Plugins), Aenderungen werden asynchron gespeichert.
 */
public final class WorthService {

    private final SchiggyWorth plugin;
    private final Database db;
    private final Map<Material, Long> prices = new ConcurrentHashMap<>();
    /** Automatisch berechnete Preise fuer alle uebrigen Ueberlebens-Items. */
    private volatile Map<Material, Long> autoPrices = Map.of();

    public WorthService(SchiggyWorth plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    /** Beim Start: Tabelle anlegen, Preise laden, bei leerer Tabelle die prices.yml importieren. */
    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fw_prices ("
                        + "material TEXT PRIMARY KEY, price INTEGER NOT NULL)");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT material, price FROM fw_prices");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Material material = Material.matchMaterial(rs.getString(1));
                    if (material != null) {
                        prices.put(material, rs.getLong(2));
                    }
                }
            }
            return null;
        }).join();
        if (prices.isEmpty()) {
            int count = importFile(false).join();
            plugin.getLogger().info(count + " Startpreise aus der prices.yml importiert.");
        }
        rebuildAutoPrices();
    }

    // ------------------------------------------------------------------ Auto-Preise

    /** Alle Materialien, die es nur im Kreativmodus gibt oder nicht als Item existieren. */
    private static final java.util.Set<Material> CREATIVE_ONLY = new java.util.HashSet<>();

    static {
        for (String name : new String[]{
                "BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "DEBUG_STICK",
                "KNOWLEDGE_BOOK", "SPAWNER", "TRIAL_SPAWNER", "VAULT", "END_PORTAL_FRAME", "REINFORCED_DEEPSLATE",
                "BUDDING_AMETHYST", "PETRIFIED_OAK_SLAB", "FARMLAND", "CHORUS_PLANT",
                "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
                "TEST_BLOCK", "TEST_INSTANCE_BLOCK", "AIR", "CAVE_AIR", "VOID_AIR", "WRITTEN_BOOK", "FILLED_MAP",
                "PLAYER_HEAD"}) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                CREATIVE_ONLY.add(material);
            }
        }
    }

    /** true, wenn das Item nicht im Ueberlebensmodus erhaeltlich ist (oder per Config gesperrt wurde). */
    public boolean isBlocked(Material material) {
        String name = material.name();
        if (name.startsWith("LEGACY_") || !material.isItem() || material.isAir()) {
            return true;
        }
        if (CREATIVE_ONLY.contains(material) || name.endsWith("_SPAWN_EGG") || name.startsWith("INFESTED_")) {
            return true;
        }
        return plugin.getConfig().getStringList("blocked").stream().anyMatch(entry -> entry.equalsIgnoreCase(name));
    }

    /** Berechnet die Auto-Preise neu (beim Start und nach Aenderungen an der Config). */
    public void rebuildAutoPrices() {
        Map<Material, Long> result = new ConcurrentHashMap<>();
        if (plugin.getConfig().getBoolean("auto-price.enabled", true)) {
            long base = Math.round(plugin.getConfig().getDouble("auto-price.default", 0.25) * 100);
            org.bukkit.configuration.ConfigurationSection keywords =
                    plugin.getConfig().getConfigurationSection("auto-price.keywords");
            for (Material material : Material.values()) {
                if (isBlocked(material) || prices.containsKey(material)) {
                    continue;
                }
                long price = base;
                if (keywords != null) {
                    for (String keyword : keywords.getKeys(false)) {
                        if (material.name().contains(keyword.toUpperCase(java.util.Locale.ROOT))) {
                            price = Math.round(keywords.getDouble(keyword) * 100);
                            break;
                        }
                    }
                }
                if (price > 0) {
                    result.put(material, price);
                }
            }
        }
        this.autoPrices = result;
    }

    // ------------------------------------------------------------------ Lesen (fuer alle Plugins)

    /** Preis pro Stueck in Cent, 0 wenn das Item keinen Wert hat. */
    public long getPrice(Material material) {
        Long explicit = prices.get(material);
        if (explicit != null) {
            return explicit;
        }
        return autoPrices.getOrDefault(material, 0L);
    }

    public boolean hasPrice(Material material) {
        return getPrice(material) > 0;
    }

    public int size() {
        return prices.size() + autoPrices.size();
    }

    /** Alle Preise alphabetisch nach Item-Name sortiert. */
    public List<Map.Entry<Material, Long>> sorted() {
        Map<Material, Long> merged = new java.util.HashMap<>(autoPrices);
        merged.putAll(prices);
        List<Map.Entry<Material, Long>> list = new ArrayList<>(merged.entrySet());
        list.removeIf(entry -> isBlocked(entry.getKey()));
        list.sort(Comparator.comparing(entry -> entry.getKey().name()));
        return list;
    }

    // ------------------------------------------------------------------ Schreiben

    public CompletableFuture<Void> setPrice(Material material, long cents) {
        prices.put(material, cents);
        rebuildAutoPrices();
        return db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fw_prices(material, price) VALUES(?,?) "
                            + "ON CONFLICT(material) DO UPDATE SET price = excluded.price")) {
                ps.setString(1, material.name());
                ps.setLong(2, cents);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Preis konnte nicht gespeichert werden", ex);
            return null;
        });
    }

    /** true, wenn es einen Preis gab. */
    public CompletableFuture<Boolean> removePrice(Material material) {
        boolean existed = prices.remove(material) != null;
        rebuildAutoPrices();
        return db.<Boolean>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM fw_prices WHERE material = ?")) {
                ps.setString(1, material.name());
                ps.executeUpdate();
            }
            return existed;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Preis konnte nicht entfernt werden", ex);
            return existed;
        });
    }

    /** Liest die prices.yml ein. overwrite = false ergaenzt nur fehlende Preise. Gibt die Anzahl zurueck. */
    public CompletableFuture<Integer> importFile(boolean overwrite) {
        File file = new File(plugin.getDataFolder(), "prices.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<Material, Long> toWrite = new LinkedHashMap<>();
        for (String key : yaml.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || !material.isItem() || material.isAir()) {
                plugin.getLogger().warning("prices.yml: unbekanntes Item '" + key + "' wird uebersprungen.");
                continue;
            }
            long cents = Math.round(yaml.getDouble(key, 0) * 100);
            if (cents <= 0) {
                continue;
            }
            if (!overwrite && prices.containsKey(material)) {
                continue;
            }
            toWrite.put(material, cents);
        }
        if (toWrite.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.<Integer>transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fw_prices(material, price) VALUES(?,?) "
                            + "ON CONFLICT(material) DO UPDATE SET price = excluded.price")) {
                for (Map.Entry<Material, Long> entry : toWrite.entrySet()) {
                    ps.setString(1, entry.getKey().name());
                    ps.setLong(2, entry.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return toWrite.size();
        }).thenApply(count -> {
            prices.putAll(toWrite);
            rebuildAutoPrices();
            return count;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Import der prices.yml fehlgeschlagen", ex);
            return 0;
        });
    }
}
