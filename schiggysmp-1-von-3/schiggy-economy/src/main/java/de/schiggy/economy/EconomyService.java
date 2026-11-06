package de.schiggy.economy;

import de.schiggy.core.database.Database;
import de.schiggy.core.util.SchiggyScheduler;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Kern des Geldsystems. Die Datenbank ist die einzige Wahrheit: jede Aenderung laeuft als Transaktion
 * auf dem Datenbank-Thread (kein Doppelt-Auszahlen, keine negativen Kontostaende).
 * Der Cache dient nur dem schnellen Lesen fuer Online-Spieler.
 */
public final class EconomyService {

    public enum Status { SUCCESS, INSUFFICIENT_FUNDS, LIMIT_EXCEEDED, INVALID_AMOUNT, ERROR }

    /** balance = neuer Kontostand des (ersten) Spielers, otherBalance = Kontostand des Empfaengers bei Transfers. */
    public record TxResult(Status status, long balance, long otherBalance) {
    }

    public record PlayerRef(UUID uuid, String name) {
    }

    public record BaltopEntry(String name, long amount) {
    }

    private final SchiggyEconomy plugin;
    private final Database db;
    private final Map<UUID, Map<Currency, Long>> cache = new ConcurrentHashMap<>();
    private volatile List<BaltopEntry> baltop = List.of();

    public EconomyService(SchiggyEconomy plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    // ------------------------------------------------------------------ Setup

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fe_balances ("
                        + "uuid TEXT NOT NULL, currency TEXT NOT NULL, amount INTEGER NOT NULL, "
                        + "PRIMARY KEY (uuid, currency))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fe_names ("
                        + "uuid TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE, seen INTEGER NOT NULL)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fe_names_name ON fe_names(name)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fe_balances_top ON fe_balances(currency, amount DESC)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fe_log ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, type TEXT NOT NULL, "
                        + "from_uuid TEXT, to_uuid TEXT, currency TEXT NOT NULL, amount INTEGER NOT NULL, note TEXT)");
            }
            int days = plugin.getConfig().getInt("transaction-log.retention-days", 30);
            if (days > 0) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM fe_log WHERE time < ?")) {
                    ps.setLong(1, System.currentTimeMillis() - days * 86_400_000L);
                    ps.executeUpdate();
                }
            }
            return null;
        }).join();
    }

    public void startBaltopRefresh() {
        long period = Math.max(10, plugin.getConfig().getLong("baltop.refresh-seconds", 60));
        SchiggyScheduler.asyncRepeating(plugin, this::refreshBaltop, 1, period);
    }

    private void refreshBaltop() {
        int limit = Math.max(1, plugin.getConfig().getInt("baltop.entries", 50));
        db.submit(c -> {
            List<BaltopEntry> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT n.name, b.amount FROM fe_balances b JOIN fe_names n ON n.uuid = b.uuid "
                            + "WHERE b.currency = ? AND b.amount > 0 ORDER BY b.amount DESC LIMIT ?")) {
                ps.setString(1, Currency.MONEY.id());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new BaltopEntry(rs.getString(1), rs.getLong(2)));
                    }
                }
            }
            baltop = List.copyOf(list);
            return null;
        }).exceptionally(ex -> fail(ex, null));
    }

    public List<BaltopEntry> baltop() {
        return baltop;
    }

    // ------------------------------------------------------------------ Spieler laden

    /** Wird beim Login (async) aufgerufen: legt den Spieler an und fuellt den Cache. */
    public CompletableFuture<Void> loadPlayer(UUID uuid, String name) {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fe_names(uuid, name, seen) VALUES(?,?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, seen = excluded.seen")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            Map<Currency, Long> map = new ConcurrentHashMap<>();
            for (Currency currency : Currency.values()) {
                Long balance = selectBalance(c, uuid, currency);
                if (balance == null) {
                    balance = currency.toMinor(plugin.getConfig().getDouble("start-balance." + currency.id(), 0));
                    setBalance(c, uuid, currency, balance);
                }
                map.put(currency, balance);
            }
            cache.put(uuid, map);
            return null;
        });
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    /** Kontostand aus dem Cache (nur fuer Online-Spieler), ohne Datenbankzugriff. */
    public java.util.OptionalLong getCachedBalance(UUID uuid, Currency currency) {
        Map<Currency, Long> map = cache.get(uuid);
        if (map == null) {
            return java.util.OptionalLong.empty();
        }
        Long value = map.get(currency);
        return value == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(value);
    }

    // ------------------------------------------------------------------ Lesen

    public CompletableFuture<Long> getBalance(UUID uuid, Currency currency) {
        Map<Currency, Long> map = cache.get(uuid);
        if (map != null) {
            Long value = map.get(currency);
            if (value != null) {
                return CompletableFuture.completedFuture(value);
            }
        }
        return db.<Long>submit(c -> balance(c, uuid, currency)).exceptionally(ex -> fail(ex, 0L));
    }

    /** Sucht einen Spieler anhand des Namens (muss schon einmal auf dem Server gewesen sein). */
    public CompletableFuture<Optional<PlayerRef>> findPlayer(String name) {
        return db.<Optional<PlayerRef>>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, name FROM fe_names WHERE name = ? COLLATE NOCASE ORDER BY seen DESC LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)));
                    }
                }
            }
            return Optional.<PlayerRef>empty();
        }).exceptionally(ex -> fail(ex, Optional.<PlayerRef>empty()));
    }

    // ------------------------------------------------------------------ Schreiben

    public CompletableFuture<TxResult> deposit(UUID uuid, Currency currency, long amount, String type, String note) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(new TxResult(Status.INVALID_AMOUNT, 0, 0));
        }
        return finish(db.transaction(c -> {
            long current = balance(c, uuid, currency);
            long updated;
            try {
                updated = Math.addExact(current, amount);
            } catch (ArithmeticException e) {
                return new TxResult(Status.LIMIT_EXCEEDED, current, 0);
            }
            if (updated > maxBalance(currency)) {
                return new TxResult(Status.LIMIT_EXCEEDED, current, 0);
            }
            setBalance(c, uuid, currency, updated);
            log(c, type, null, uuid, currency, amount, note);
            return new TxResult(Status.SUCCESS, updated, 0);
        }), uuid, null, currency);
    }

    public CompletableFuture<TxResult> withdraw(UUID uuid, Currency currency, long amount, String type, String note) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(new TxResult(Status.INVALID_AMOUNT, 0, 0));
        }
        return finish(db.transaction(c -> {
            long current = balance(c, uuid, currency);
            if (current < amount) {
                return new TxResult(Status.INSUFFICIENT_FUNDS, current, 0);
            }
            long updated = current - amount;
            setBalance(c, uuid, currency, updated);
            log(c, type, uuid, null, currency, amount, note);
            return new TxResult(Status.SUCCESS, updated, 0);
        }), uuid, null, currency);
    }

    public CompletableFuture<TxResult> set(UUID uuid, Currency currency, long amount, String type, String note) {
        if (amount < 0) {
            return CompletableFuture.completedFuture(new TxResult(Status.INVALID_AMOUNT, 0, 0));
        }
        return finish(db.transaction(c -> {
            long current = balance(c, uuid, currency);
            if (amount > maxBalance(currency)) {
                return new TxResult(Status.LIMIT_EXCEEDED, current, 0);
            }
            setBalance(c, uuid, currency, amount);
            log(c, type, null, uuid, currency, amount, note);
            return new TxResult(Status.SUCCESS, amount, 0);
        }), uuid, null, currency);
    }

    /** Atomarer Transfer: entweder beide Konten werden geaendert oder keins. */
    public CompletableFuture<TxResult> transfer(UUID from, UUID to, Currency currency, long amount, String note) {
        if (amount <= 0 || from.equals(to)) {
            return CompletableFuture.completedFuture(new TxResult(Status.INVALID_AMOUNT, 0, 0));
        }
        return finish(db.transaction(c -> {
            long fromBalance = balance(c, from, currency);
            if (fromBalance < amount) {
                return new TxResult(Status.INSUFFICIENT_FUNDS, fromBalance, 0);
            }
            long toBalance = balance(c, to, currency);
            long newTo;
            try {
                newTo = Math.addExact(toBalance, amount);
            } catch (ArithmeticException e) {
                return new TxResult(Status.LIMIT_EXCEEDED, fromBalance, toBalance);
            }
            if (newTo > maxBalance(currency)) {
                return new TxResult(Status.LIMIT_EXCEEDED, fromBalance, toBalance);
            }
            long newFrom = fromBalance - amount;
            setBalance(c, from, currency, newFrom);
            setBalance(c, to, currency, newTo);
            log(c, "PAY", from, to, currency, amount, note);
            return new TxResult(Status.SUCCESS, newFrom, newTo);
        }), from, to, currency);
    }

    // ------------------------------------------------------------------ Formatierung

    public String format(Currency currency, long minor) {
        FileConfiguration cfg = plugin.getConfig();
        Locale locale = Locale.forLanguageTag(cfg.getString("format.locale", "en-US"));
        DecimalFormat df = new DecimalFormat(currency.decimals() == 0 ? "#,##0" : "#,##0.00",
                DecimalFormatSymbols.getInstance(locale));
        String number = df.format(BigDecimal.valueOf(minor, currency.decimals()));
        String pattern = cfg.getString("format." + currency.id(), "{amount}");
        return pattern.replace("{amount}", number);
    }

    // ------------------------------------------------------------------ Intern

    private CompletableFuture<TxResult> finish(CompletableFuture<TxResult> future, UUID first, UUID second,
                                               Currency currency) {
        return future
                .exceptionally(ex -> fail(ex, new TxResult(Status.ERROR, 0, 0)))
                .thenApplyAsync(result -> {
                    if (result.status() == Status.SUCCESS) {
                        cacheSet(first, currency, result.balance());
                        if (second != null) {
                            cacheSet(second, currency, result.otherBalance());
                        }
                    }
                    return result;
                }, db.executor());
    }

    private void cacheSet(UUID uuid, Currency currency, long value) {
        Map<Currency, Long> map = cache.get(uuid);
        if (map != null) {
            map.put(currency, value);
        }
    }

    private long maxBalance(Currency currency) {
        long units = plugin.getConfig().getLong("limits.max-balance." + currency.id(), Long.MAX_VALUE / 1000);
        try {
            return Math.multiplyExact(units, currency.scale());
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private long balance(Connection c, UUID uuid, Currency currency) throws SQLException {
        Long value = selectBalance(c, uuid, currency);
        return value == null ? 0L : value;
    }

    private Long selectBalance(Connection c, UUID uuid, Currency currency) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT amount FROM fe_balances WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, currency.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return null;
            }
        }
    }

    private void setBalance(Connection c, UUID uuid, Currency currency, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fe_balances(uuid, currency, amount) VALUES(?,?,?) "
                        + "ON CONFLICT(uuid, currency) DO UPDATE SET amount = excluded.amount")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, currency.id());
            ps.setLong(3, amount);
            ps.executeUpdate();
        }
    }

    private void log(Connection c, String type, UUID from, UUID to, Currency currency, long amount, String note)
            throws SQLException {
        if (!plugin.getConfig().getBoolean("transaction-log.enabled", true)) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fe_log(time, type, from_uuid, to_uuid, currency, amount, note) VALUES(?,?,?,?,?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, type);
            ps.setString(3, from == null ? null : from.toString());
            ps.setString(4, to == null ? null : to.toString());
            ps.setString(5, currency.id());
            ps.setLong(6, amount);
            ps.setString(7, note);
            ps.executeUpdate();
        }
    }

    private <T> T fail(Throwable ex, T fallback) {
        plugin.getLogger().log(Level.SEVERE, "Datenbankfehler in SchiggyEconomy", ex);
        return fallback;
    }
}
