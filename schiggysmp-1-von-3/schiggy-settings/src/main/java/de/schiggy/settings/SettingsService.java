package de.schiggy.settings;

import de.schiggy.core.database.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Speichert die Einstellungen pro Spieler. Lesen laeuft ueber den Cache (sofort, auch aus anderen Plugins),
 * Schreiben aktualisiert den Cache sofort und speichert danach asynchron in der Datenbank.
 */
public final class SettingsService {

    private final SchiggySettings plugin;
    private final Database db;
    private final Map<UUID, Map<Setting, Boolean>> cache = new ConcurrentHashMap<>();

    public SettingsService(SchiggySettings plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fs_settings ("
                        + "uuid TEXT NOT NULL, setting TEXT NOT NULL, enabled INTEGER NOT NULL, "
                        + "PRIMARY KEY (uuid, setting))");
            }
            return null;
        }).join();
    }

    /** Wird beim Login (async) aufgerufen. */
    public CompletableFuture<Void> loadPlayer(UUID uuid) {
        return db.<Void>submit(c -> {
            Map<Setting, Boolean> map = new ConcurrentHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT setting, enabled FROM fs_settings WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Setting setting = Setting.byKey(rs.getString(1));
                        if (setting != null) {
                            map.put(setting, rs.getInt(2) != 0);
                        }
                    }
                }
            }
            cache.put(uuid, map);
            return null;
        });
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    /** Aktueller Wert (Standardwert, falls der Spieler nichts geaendert hat oder nicht geladen ist). */
    public boolean isEnabled(UUID uuid, Setting setting) {
        Map<Setting, Boolean> map = cache.get(uuid);
        if (map != null) {
            Boolean value = map.get(setting);
            if (value != null) {
                return value;
            }
        }
        return setting.defaultValue();
    }

    public CompletableFuture<Void> set(UUID uuid, Setting setting, boolean enabled) {
        cache.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>()).put(setting, enabled);
        return db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fs_settings(uuid, setting, enabled) VALUES(?,?,?) "
                            + "ON CONFLICT(uuid, setting) DO UPDATE SET enabled = excluded.enabled")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, setting.key());
                ps.setInt(3, enabled ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Einstellung konnte nicht gespeichert werden", ex);
            return null;
        });
    }

    /** Schaltet um und gibt den neuen Wert zurueck. */
    public boolean toggle(UUID uuid, Setting setting) {
        boolean updated = !isEnabled(uuid, setting);
        set(uuid, setting, updated);
        return updated;
    }
}
