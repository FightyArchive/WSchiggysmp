package de.schiggy.homes;

import de.schiggy.core.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Speichert Homes in der Datenbank. Alle Aenderungen laufen als Transaktion auf dem Datenbank-Thread. */
public final class HomeService {

    public record Home(String name, String world, double x, double y, double z, float yaw, float pitch) {
    }

    public enum SaveStatus { CREATED, UPDATED, LIMIT_REACHED, ERROR }

    public record SaveResult(SaveStatus status, int count) {
    }

    private final SchiggyHomes plugin;
    private final Database db;
    /** Namen der Homes online befindlicher Spieler, fuer die Tab-Completion. */
    private final Map<UUID, List<String>> names = new ConcurrentHashMap<>();

    public HomeService(SchiggyHomes plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fh_homes ("
                        + "uuid TEXT NOT NULL, name TEXT NOT NULL COLLATE NOCASE, world TEXT NOT NULL, "
                        + "x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, "
                        + "created INTEGER NOT NULL, PRIMARY KEY (uuid, name))");
            }
            return null;
        }).join();
    }

    public List<String> cachedNames(UUID uuid) {
        return names.getOrDefault(uuid, List.of());
    }

    public void forget(UUID uuid) {
        names.remove(uuid);
    }

    public CompletableFuture<List<Home>> list(UUID uuid) {
        return db.<List<Home>>submit(c -> {
            List<Home> homes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, world, x, y, z, yaw, pitch FROM fh_homes WHERE uuid = ? ORDER BY name")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        homes.add(new Home(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                                rs.getDouble(5), (float) rs.getDouble(6), (float) rs.getDouble(7)));
                    }
                }
            }
            names.put(uuid, homes.stream().map(Home::name).toList());
            return homes;
        }).exceptionally(ex -> fail(ex, List.<Home>of()));
    }

    /** limit = -1 bedeutet unbegrenzt. Ein vorhandenes Home gleichen Namens wird aktualisiert. */
    public CompletableFuture<SaveResult> save(UUID uuid, Home home, int limit) {
        return db.<SaveResult>transaction(c -> {
            boolean exists;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM fh_homes WHERE uuid = ? AND name = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, home.name());
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (!exists && limit >= 0) {
                int current = count(c, uuid);
                if (current >= limit) {
                    return new SaveResult(SaveStatus.LIMIT_REACHED, current);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fh_homes(uuid, name, world, x, y, z, yaw, pitch, created) VALUES(?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(uuid, name) DO UPDATE SET world = excluded.world, x = excluded.x, "
                            + "y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, home.name());
                ps.setString(3, home.world());
                ps.setDouble(4, home.x());
                ps.setDouble(5, home.y());
                ps.setDouble(6, home.z());
                ps.setDouble(7, home.yaw());
                ps.setDouble(8, home.pitch());
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
            }
            refreshNames(c, uuid);
            return new SaveResult(exists ? SaveStatus.UPDATED : SaveStatus.CREATED, count(c, uuid));
        }).exceptionally(ex -> fail(ex, new SaveResult(SaveStatus.ERROR, 0)));
    }

    /** true, wenn ein Home geloescht wurde. */
    public CompletableFuture<Boolean> delete(UUID uuid, String name) {
        return db.<Boolean>transaction(c -> {
            int rows;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM fh_homes WHERE uuid = ? AND name = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                rows = ps.executeUpdate();
            }
            refreshNames(c, uuid);
            return rows > 0;
        }).exceptionally(ex -> fail(ex, Boolean.FALSE));
    }

    public CompletableFuture<Void> loadNames(UUID uuid) {
        return db.<Void>submit(c -> {
            refreshNames(c, uuid);
            return null;
        }).exceptionally(ex -> fail(ex, null));
    }

    private int count(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM fh_homes WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void refreshNames(Connection c, UUID uuid) throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM fh_homes WHERE uuid = ? ORDER BY name")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
            }
        }
        names.put(uuid, List.copyOf(list));
    }

    private <T> T fail(Throwable ex, T fallback) {
        plugin.getLogger().log(Level.SEVERE, "Datenbankfehler in SchiggyHomes", ex);
        return fallback;
    }
}
