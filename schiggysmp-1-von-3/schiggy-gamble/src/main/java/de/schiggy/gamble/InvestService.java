package de.schiggy.gamble;

import de.schiggy.core.database.Database;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Investments mit "fauler" Berechnung: Das Einkommen wird aus der vergangenen Zeit berechnet (investierter Betrag x Zinssatz
 * pro Intervall), also auch fuer die Zeit, in der der Spieler offline war (begrenzt durch max-offline-hours).
 * Auszahlungen setzen den Betrag zuerst auf null und zahlen erst dann aus: so gibt es keine Doppelzahlung.
 */
public final class InvestService {

    /** Konto eines Spielers. Alle Zugriffe laufen ueber synchronized (account). Betraege in Cent. */
    static final class Account {
        long invested;
        long pending;
        long earned;
        long lastTick;
        boolean auto;
        boolean dirty;
    }

    public record View(long invested, long pending, long earned, long nextMillis, boolean auto) {
    }

    private final SchiggyGamble plugin;
    private final Database db;
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private int saveCounter;

    public InvestService(SchiggyGamble plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS sg_invest2 (uuid TEXT PRIMARY KEY, invested INTEGER NOT NULL, "
                        + "pending INTEGER NOT NULL, earned INTEGER NOT NULL, last_tick INTEGER NOT NULL, auto INTEGER NOT NULL)");
            }
            return null;
        }).join();
        SchiggyScheduler.asyncRepeating(plugin, this::tick, 1, 1);
    }

    // ------------------------------------------------------------------ Einstellungen

    double rate() {
        return Math.max(0, plugin.getConfig().getDouble("rate", 0.001));
    }

    long intervalTicks() {
        return Math.max(1, plugin.getConfig().getLong("interval-ticks", 20));
    }

    long intervalMillis() {
        return intervalTicks() * 50L;
    }

    long maxInvestment() {
        return Currency.MONEY.toMinor(plugin.getConfig().getDouble("max-investment", 1_000_000_000_000.0));
    }

    long minAmount() {
        return Math.max(1, Currency.MONEY.toMinor(plugin.getConfig().getDouble("min-amount", 1)));
    }

    long incomePerInterval(long investedCents) {
        return (long) Math.floor(investedCents * rate());
    }

    // ------------------------------------------------------------------ Berechnung

    /** Schreibt das seit dem letzten Mal verdiente Einkommen gut. Muss unter synchronized (account) laufen. */
    private void accrue(Account a, long now) {
        if (a.invested <= 0) {
            a.lastTick = now;
            return;
        }
        long interval = intervalMillis();
        long elapsed = now - a.lastTick;
        if (elapsed < interval) {
            return;
        }
        long cap = Math.max(1, plugin.getConfig().getLong("max-offline-hours", 24)) * 3_600_000L;
        boolean capped = elapsed > cap;
        long intervals = Math.min(elapsed, cap) / interval;
        long gain;
        try {
            gain = Math.multiplyExact(incomePerInterval(a.invested), intervals);
        } catch (ArithmeticException e) {
            gain = Long.MAX_VALUE / 4;
        }
        a.pending = a.pending > Long.MAX_VALUE / 2 ? a.pending : a.pending + gain;
        a.earned = a.earned > Long.MAX_VALUE / 2 ? a.earned : a.earned + gain;
        a.lastTick = capped ? now : a.lastTick + intervals * interval;
        a.dirty = true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        boolean persist = ++saveCounter % 30 == 0;
        for (Map.Entry<UUID, Account> entry : accounts.entrySet()) {
            Account a = entry.getValue();
            long payout = 0;
            synchronized (a) {
                accrue(a, now);
                if (a.auto && a.pending > 0) {
                    payout = a.pending;
                    a.pending = 0;
                    a.dirty = true;
                }
            }
            if (payout > 0) {
                payOut(entry.getKey(), a, payout, "INVEST_AUTO");
            }
            if (persist) {
                save(entry.getKey(), a);
            }
        }
    }

    /** Zahlt aus. Schlaegt das fehl, wird der Betrag wieder auf "abholbereit" gesetzt. */
    private CompletableFuture<Boolean> payOut(UUID uuid, Account a, long amount, String type) {
        return economy().deposit(uuid, Currency.MONEY, amount, type, "invest").thenApply(result -> {
            if (result.status() != EconomyService.Status.SUCCESS) {
                synchronized (a) {
                    a.pending += amount;
                    a.dirty = true;
                }
                return false;
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ Laden und Speichern

    CompletableFuture<Void> loadPlayer(UUID uuid) {
        return db.<Void>submit(c -> {
            Account a = new Account();
            a.lastTick = System.currentTimeMillis();
            a.auto = plugin.getConfig().getBoolean("auto-collect-default", false);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT invested, pending, earned, last_tick, auto FROM sg_invest2 WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        a.invested = rs.getLong(1);
                        a.pending = rs.getLong(2);
                        a.earned = rs.getLong(3);
                        a.lastTick = rs.getLong(4);
                        a.auto = rs.getInt(5) != 0;
                    }
                }
            }
            accounts.put(uuid, a);
            return null;
        });
    }

    void unload(UUID uuid) {
        Account a = accounts.remove(uuid);
        busy.remove(uuid);
        if (a != null) {
            save(uuid, a);
        }
    }

    void saveAll() {
        for (Map.Entry<UUID, Account> entry : accounts.entrySet()) {
            save(entry.getKey(), entry.getValue()).join();
        }
    }

    private CompletableFuture<Void> save(UUID uuid, Account a) {
        long invested;
        long pending;
        long earned;
        long lastTick;
        boolean auto;
        synchronized (a) {
            invested = a.invested;
            pending = a.pending;
            earned = a.earned;
            lastTick = a.lastTick;
            auto = a.auto;
            a.dirty = false;
        }
        return db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sg_invest2(uuid, invested, pending, earned, last_tick, auto) VALUES(?,?,?,?,?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET invested = excluded.invested, pending = excluded.pending, "
                            + "earned = excluded.earned, last_tick = excluded.last_tick, auto = excluded.auto")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, invested);
                ps.setLong(3, pending);
                ps.setLong(4, earned);
                ps.setLong(5, lastTick);
                ps.setInt(6, auto ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Investment konnte nicht gespeichert werden", ex);
            return null;
        });
    }

    // ------------------------------------------------------------------ Anzeige

    View view(UUID uuid) {
        Account a = accounts.get(uuid);
        if (a == null) {
            return new View(0, 0, 0, 0, false);
        }
        synchronized (a) {
            long now = System.currentTimeMillis();
            accrue(a, now);
            long next = a.invested > 0 ? Math.max(0, intervalMillis() - (now - a.lastTick)) : 0;
            return new View(a.invested, a.pending, a.earned, next, a.auto);
        }
    }

    // ------------------------------------------------------------------ Aktionen (auf dem Thread des Spielers)

    void invest(Player player, long amount, Runnable done) {
        UUID uuid = player.getUniqueId();
        var msg = plugin.messages();
        Account a = accounts.get(uuid);
        if (a == null) {
            msg.send(player, "error");
            return;
        }
        if (amount <= 0) {
            msg.send(player, "invalid-amount");
            return;
        }
        if (amount < minAmount()) {
            msg.send(player, "amount-small", "min", economy().format(Currency.MONEY, minAmount()));
            return;
        }
        synchronized (a) {
            accrue(a, System.currentTimeMillis());
            long max = maxInvestment();
            if (a.invested + amount > max) {
                msg.send(player, "too-much", "max", economy().format(Currency.MONEY, max),
                        "current", economy().format(Currency.MONEY, a.invested));
                return;
            }
        }
        if (!busy.add(uuid)) {
            return;
        }
        economy().withdraw(uuid, Currency.MONEY, amount, "INVEST", "amount=" + amount)
                .whenComplete((tx, error) -> SchiggyScheduler.entity(plugin, player, () -> {
                    busy.remove(uuid);
                    if (error != null || tx == null) {
                        plugin.getLogger().log(Level.SEVERE, "Investition fehlgeschlagen", error);
                        msg.send(player, "error");
                    } else if (tx.status() == EconomyService.Status.SUCCESS) {
                        long total;
                        synchronized (a) {
                            long now = System.currentTimeMillis();
                            accrue(a, now);
                            if (a.invested == 0) {
                                a.lastTick = now;
                            }
                            a.invested += amount;
                            a.dirty = true;
                            total = a.invested;
                        }
                        save(uuid, a);
                        msg.send(player, "invested", "amount", economy().format(Currency.MONEY, amount),
                                "total", economy().format(Currency.MONEY, total),
                                "income", economy().format(Currency.MONEY, incomePerInterval(total)));
                    } else if (tx.status() == EconomyService.Status.INSUFFICIENT_FUNDS) {
                        msg.send(player, "no-money", "amount", economy().format(Currency.MONEY, amount));
                    } else {
                        msg.send(player, "error");
                    }
                    done.run();
                }));
    }

    void claim(Player player, Runnable done) {
        UUID uuid = player.getUniqueId();
        var msg = plugin.messages();
        Account a = accounts.get(uuid);
        long amount = 0;
        if (a != null) {
            synchronized (a) {
                accrue(a, System.currentTimeMillis());
                amount = a.pending;
                a.pending = 0; // zuerst auf null, dann auszahlen: keine Doppelzahlung
                a.dirty = true;
            }
        }
        if (amount <= 0) {
            msg.send(player, "nothing-to-claim");
            done.run();
            return;
        }
        long paid = amount;
        payOut(uuid, a, paid, "INVEST_CLAIM").thenAccept(ok -> SchiggyScheduler.entity(plugin, player, () -> {
            if (ok) {
                msg.send(player, "claimed", "amount", economy().format(Currency.MONEY, paid));
            } else {
                msg.send(player, "error");
            }
            done.run();
        }));
    }

    void delete(Player player, Runnable done) {
        UUID uuid = player.getUniqueId();
        var msg = plugin.messages();
        Account a = accounts.get(uuid);
        long capital = 0;
        long income = 0;
        if (a != null) {
            synchronized (a) {
                accrue(a, System.currentTimeMillis());
                capital = a.invested;
                income = a.pending;
                a.invested = 0;
                a.pending = 0;
                a.earned = 0;
                a.dirty = true;
            }
        }
        if (capital <= 0) {
            msg.send(player, "nothing-invested");
            done.run();
            return;
        }
        long refundCapital = capital;
        long refundIncome = income;
        save(uuid, a);
        economy().deposit(uuid, Currency.MONEY, refundCapital + refundIncome, "INVEST_DELETE", "refund")
                .thenAccept(result -> SchiggyScheduler.entity(plugin, player, () -> {
                    if (result.status() == EconomyService.Status.SUCCESS) {
                        msg.send(player, "deleted", "amount", economy().format(Currency.MONEY, refundCapital + refundIncome),
                                "capital", economy().format(Currency.MONEY, refundCapital),
                                "income", economy().format(Currency.MONEY, refundIncome));
                    } else {
                        // Auszahlung fehlgeschlagen: das Investment wiederherstellen
                        synchronized (a) {
                            a.invested += refundCapital;
                            a.pending += refundIncome;
                            a.dirty = true;
                        }
                        save(uuid, a);
                        msg.send(player, "error");
                    }
                    done.run();
                }));
    }

    void toggleAuto(Player player, Runnable done) {
        Account a = accounts.get(player.getUniqueId());
        if (a == null) {
            plugin.messages().send(player, "error");
            return;
        }
        boolean now;
        synchronized (a) {
            a.auto = !a.auto;
            a.dirty = true;
            now = a.auto;
        }
        save(player.getUniqueId(), a);
        plugin.messages().send(player, now ? "auto-on" : "auto-off");
        done.run();
    }

    // ------------------------------------------------------------------ Admin

    /** Konto eines beliebigen Spielers (online aus dem Speicher, sonst aus der Datenbank). */
    private CompletableFuture<Account> adminAccount(UUID uuid) {
        Account cached = accounts.get(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return db.<Account>submit(c -> {
            Account a = new Account();
            a.lastTick = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT invested, pending, earned, last_tick, auto FROM sg_invest2 WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        a.invested = rs.getLong(1);
                        a.pending = rs.getLong(2);
                        a.earned = rs.getLong(3);
                        a.lastTick = rs.getLong(4);
                        a.auto = rs.getInt(5) != 0;
                    }
                }
            }
            return a;
        });
    }

    CompletableFuture<View> adminView(UUID uuid) {
        return adminAccount(uuid).thenApply(a -> {
            synchronized (a) {
                accrue(a, System.currentTimeMillis());
                return new View(a.invested, a.pending, a.earned, 0, a.auto);
            }
        });
    }

    /** Setzt ein Investment zurueck. Mit refund bekommt der Spieler sein Kapital zurueck. Gibt den Rueckzahlbetrag zurueck. */
    CompletableFuture<Long> adminReset(UUID uuid, boolean refund) {
        return adminAccount(uuid).thenCompose(a -> {
            long capital;
            synchronized (a) {
                capital = a.invested;
                a.invested = 0;
                a.pending = 0;
                a.earned = 0;
                a.dirty = true;
            }
            save(uuid, a);
            if (!refund || capital <= 0) {
                return CompletableFuture.completedFuture(0L);
            }
            return economy().deposit(uuid, Currency.MONEY, capital, "INVEST_ADMIN_RESET", "refund")
                    .thenApply(result -> result.status() == EconomyService.Status.SUCCESS ? capital : 0L);
        });
    }

    CompletableFuture<Void> adminSetEarned(UUID uuid, long cents) {
        return adminAccount(uuid).thenCompose(a -> {
            synchronized (a) {
                a.earned = Math.max(0, cents);
                a.dirty = true;
            }
            return save(uuid, a);
        });
    }
}
