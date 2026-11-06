package de.schiggy.stats;

import de.schiggy.core.database.Database;
import de.schiggy.core.util.SchiggyScheduler;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Kills, Tode und Spielzeit. Online-Spieler liegen im Speicher (sofort lesbar, auch fuer das Scoreboard),
 * alle Werte werden regelmaessig und beim Verlassen in die Datenbank geschrieben.
 */
public final class StatsService {

    public enum Category { MONEY, KILLS, DEATHS, PLAYTIME }

    public record Snapshot(long kills, long deaths, long playSeconds) {
        public double kdr() {
            return deaths == 0 ? kills : (double) kills / deaths;
        }
    }

    public record Entry(UUID uuid, String name, long value) {
    }

    private static final class Data {
        String name;
        long kills;
        long deaths;
        long playSeconds;
        long sessionStart;

        synchronized Snapshot snapshot() {
            return new Snapshot(kills, deaths, playSeconds + (System.currentTimeMillis() - sessionStart) / 1000);
        }

        /** Rechnet die laufende Sitzung in die Gesamt-Spielzeit ein und gibt den aktuellen Stand zurueck. */
        synchronized Snapshot roll() {
            long now = System.currentTimeMillis();
            playSeconds += (now - sessionStart) / 1000;
            sessionStart = now;
            return new Snapshot(kills, deaths, playSeconds);
        }
    }

    private final SchiggyStats plugin;
    private final Database db;
    private final Map<UUID, Data> cache = new ConcurrentHashMap<>();

    public StatsService(SchiggyStats plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS ss_stats ("
                        + "uuid TEXT PRIMARY KEY, name TEXT NOT NULL, kills INTEGER NOT NULL, "
                        + "deaths INTEGER NOT NULL, playtime INTEGER NOT NULL)");
            }
            return null;
        }).join();
        long period = Math.max(10, plugin.getConfig().getLong("save-interval-seconds", 60));
        SchiggyScheduler.asyncRepeating(plugin, this::flushAll, period, period);
    }

    // ------------------------------------------------------------------ Laden und Speichern

    public CompletableFuture<Void> loadPlayer(UUID uuid, String name) {
        return db.<Void>submit(c -> {
            Data data = new Data();
            data.name = name;
            data.sessionStart = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement("SELECT kills, deaths, playtime FROM ss_stats WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        data.kills = rs.getLong(1);
                        data.deaths = rs.getLong(2);
                        data.playSeconds = rs.getLong(3);
                    }
                }
            }
            cache.put(uuid, data);
            return null;
        });
    }

    /** Beim Verlassen: speichern und aus dem Speicher nehmen. */
    public void unload(UUID uuid) {
        Data data = cache.remove(uuid);
        if (data != null) {
            write(uuid, data);
        }
    }

    public CompletableFuture<Void> flushAll() {
        CompletableFuture<Void> last = CompletableFuture.completedFuture(null);
        for (Map.Entry<UUID, Data> entry : cache.entrySet()) {
            last = write(entry.getKey(), entry.getValue());
        }
        return last;
    }

    private CompletableFuture<Void> write(UUID uuid, Data data) {
        Snapshot snapshot = data.roll();
        String name = data.name;
        return db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ss_stats(uuid, name, kills, deaths, playtime) VALUES(?,?,?,?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, kills = excluded.kills, "
                            + "deaths = excluded.deaths, playtime = excluded.playtime")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, snapshot.kills());
                ps.setLong(4, snapshot.deaths());
                ps.setLong(5, snapshot.playSeconds());
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Statistiken konnten nicht gespeichert werden", ex);
            return null;
        });
    }

    // ------------------------------------------------------------------ Zaehlen

    public void addKill(UUID uuid) {
        Data data = cache.get(uuid);
        if (data != null) {
            synchronized (data) {
                data.kills++;
            }
        }
    }

    public void addDeath(UUID uuid) {
        Data data = cache.get(uuid);
        if (data != null) {
            synchronized (data) {
                data.deaths++;
            }
        }
    }

    // ------------------------------------------------------------------ Lesen

    /** Sofort lesbar fuer Online-Spieler (leer, wenn der Spieler nicht geladen ist). */
    public java.util.Optional<Snapshot> cached(UUID uuid) {
        Data data = cache.get(uuid);
        return data == null ? java.util.Optional.empty() : java.util.Optional.of(data.snapshot());
    }

    /** Werte eines beliebigen Spielers, auch wenn er offline ist. */
    public CompletableFuture<Snapshot> statsOf(UUID uuid) {
        Data data = cache.get(uuid);
        if (data != null) {
            return CompletableFuture.completedFuture(data.snapshot());
        }
        return db.<Snapshot>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT kills, deaths, playtime FROM ss_stats WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Snapshot(rs.getLong(1), rs.getLong(2), rs.getLong(3)) : new Snapshot(0, 0, 0);
                }
            }
        }).exceptionally(ex -> new Snapshot(0, 0, 0));
    }

    /** Die besten Spieler nach Kills, Tode oder Spielzeit. (Geld kommt aus SchiggyEconomy.) */
    public CompletableFuture<List<Entry>> top(Category category, int limit) {
        String column = switch (category) {
            case KILLS -> "kills";
            case DEATHS -> "deaths";
            case PLAYTIME -> "playtime";
            default -> throw new IllegalArgumentException("Geld hat keine eigene Datenbank-Spalte");
        };
        // erst speichern (gleiche Warteschlange), dann lesen: die Liste ist so aktuell
        flushAll();
        return db.<List<Entry>>submit(c -> {
            List<Entry> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, " + column + " FROM ss_stats WHERE "
                    + column + " > 0 ORDER BY " + column + " DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Entry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                    }
                }
            }
            return list;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Bestenliste konnte nicht geladen werden", ex);
            return List.<Entry>of();
        });
    }
}
