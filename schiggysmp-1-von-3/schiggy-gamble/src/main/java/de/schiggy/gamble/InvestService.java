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
 * Investitionen: Jede Sekunde bekommt jeder Online-Spieler einen Prozentsatz seines angelegten Geldes.
 * Einzahlen und Abheben sind atomar (Geld und angelegter Betrag stimmen immer ueberein).
 */
public final class InvestService {

    private enum Kind { OK, NO_MONEY, ERROR, NOT_ENOUGH }

    private final SchiggyGamble plugin;
    private final Database db;
    /** angelegter Betrag in Cent, nur fuer Online-Spieler */
    private final Map<UUID, Long> invested = new ConcurrentHashMap<>();
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    public InvestService(SchiggyGamble plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS sg_invest (uuid TEXT PRIMARY KEY, amount INTEGER NOT NULL)");
            }
            return null;
        }).join();
        SchiggyScheduler.asyncRepeating(plugin, this::payIncome, 1, 1);
    }

    // ------------------------------------------------------------------ Einkommen

    public double ratePercent() {
        return Math.max(0, plugin.getConfig().getDouble("rate-percent-per-second", 1.0));
    }

    public long incomePerSecond(long investedCents) {
        return (long) Math.floor(investedCents * ratePercent() / 100.0);
    }

    /** Jede Sekunde: allen Online-Spielern mit Investment ihr Einkommen gutschreiben. */
    private void payIncome() {
        for (Map.Entry<UUID, Long> entry : invested.entrySet()) {
            long income = incomePerSecond(entry.getValue());
            if (income > 0) {
                economy().deposit(entry.getKey(), Currency.MONEY, income, "INVEST_INCOME", "per second");
            }
        }
    }

    // ------------------------------------------------------------------ Spieler

    public CompletableFuture<Void> loadPlayer(UUID uuid) {
        return db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM sg_invest WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    long amount = rs.next() ? rs.getLong(1) : 0L;
                    if (amount > 0) {
                        invested.put(uuid, amount);
                    }
                }
            }
            return null;
        });
    }

    public void unload(UUID uuid) {
        invested.remove(uuid);
        processing.remove(uuid);
    }

    public long amountOf(UUID uuid) {
        return invested.getOrDefault(uuid, 0L);
    }

    // ------------------------------------------------------------------ Einzahlen

    /** Muss auf dem Thread des Spielers laufen. */
    void invest(Player player, long amount, Runnable done) {
        UUID uuid = player.getUniqueId();
        var msg = plugin.messages();
        long min = Currency.MONEY.toMinor(plugin.getConfig().getDouble("min-amount", 100));
        long max = Currency.MONEY.toMinor(plugin.getConfig().getDouble("max-total", 1_000_000));
        long current = amountOf(uuid);
        if (amount < min) {
            msg.send(player, "amount-too-small", "min", economy().format(Currency.MONEY, min));
            return;
        }
        if (current + amount > max) {
            msg.send(player, "too-much", "max", economy().format(Currency.MONEY, max),
                    "current", economy().format(Currency.MONEY, current));
            return;
        }
        if (!processing.add(uuid)) {
            return;
        }
        economy().withdraw(uuid, Currency.MONEY, amount, "INVEST", "amount=" + amount).thenCompose(tx -> {
            if (tx.status() != EconomyService.Status.SUCCESS) {
                return CompletableFuture.completedFuture(tx.status() == EconomyService.Status.INSUFFICIENT_FUNDS
                        ? Kind.NO_MONEY : Kind.ERROR);
            }
            return add(uuid, amount).thenCompose(ok -> {
                if (ok) {
                    invested.merge(uuid, amount, Long::sum);
                    return CompletableFuture.completedFuture(Kind.OK);
                }
                return economy().deposit(uuid, Currency.MONEY, amount, "INVEST_REFUND", "db failed")
                        .thenApply(refund -> Kind.ERROR);
            });
        }).whenComplete((kind, error) -> SchiggyScheduler.entity(plugin, player, () -> {
            processing.remove(uuid);
            if (error != null || kind == null) {
                plugin.getLogger().log(Level.SEVERE, "Investition fehlgeschlagen", error);
                msg.send(player, "error");
            } else {
                switch (kind) {
                    case OK -> {
                        long total = amountOf(uuid);
                        msg.send(player, "invested", "amount", economy().format(Currency.MONEY, amount),
                                "total", economy().format(Currency.MONEY, total),
                                "income", economy().format(Currency.MONEY, incomePerSecond(total)));
                    }
                    case NO_MONEY -> msg.send(player, "no-money", "amount", economy().format(Currency.MONEY, amount));
                    default -> msg.send(player, "error");
                }
            }
            done.run();
        }));
    }

    private CompletableFuture<Boolean> add(UUID uuid, long amount) {
        return db.<Boolean>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sg_invest(uuid, amount) VALUES(?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET amount = amount + excluded.amount")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, amount);
                ps.executeUpdate();
                return true;
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Investment konnte nicht gespeichert werden", ex);
            return false;
        });
    }

    // ------------------------------------------------------------------ Abheben

    /** amount <= 0 bedeutet alles. Muss auf dem Thread des Spielers laufen. */
    void withdraw(Player player, long requested, Runnable done) {
        UUID uuid = player.getUniqueId();
        var msg = plugin.messages();
        long current = amountOf(uuid);
        if (current <= 0) {
            msg.send(player, "nothing-invested");
            return;
        }
        long amount = requested <= 0 ? current : requested;
        if (amount > current) {
            msg.send(player, "not-enough-invested", "total", economy().format(Currency.MONEY, current));
            return;
        }
        if (!processing.add(uuid)) {
            return;
        }
        db.<Boolean>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE sg_invest SET amount = amount - ? WHERE uuid = ? AND amount >= ?")) {
                ps.setLong(1, amount);
                ps.setString(2, uuid.toString());
                ps.setLong(3, amount);
                return ps.executeUpdate() == 1;
            }
        }).thenCompose(ok -> {
            if (!ok) {
                return CompletableFuture.completedFuture(Kind.NOT_ENOUGH);
            }
            invested.compute(uuid, (key, old) -> {
                long left = (old == null ? 0 : old) - amount;
                return left > 0 ? left : null;
            });
            return economy().deposit(uuid, Currency.MONEY, amount, "INVEST_WITHDRAW", "amount=" + amount)
                    .thenApply(tx -> tx.status() == EconomyService.Status.SUCCESS ? Kind.OK : Kind.ERROR);
        }).whenComplete((kind, error) -> SchiggyScheduler.entity(plugin, player, () -> {
            processing.remove(uuid);
            if (error != null || kind == null || kind == Kind.ERROR) {
                plugin.getLogger().log(Level.SEVERE, "Abheben fehlgeschlagen", error);
                msg.send(player, "error");
            } else if (kind == Kind.NOT_ENOUGH) {
                msg.send(player, "not-enough-invested", "total", economy().format(Currency.MONEY, amountOf(uuid)));
            } else {
                msg.send(player, "withdrawn", "amount", economy().format(Currency.MONEY, amount),
                        "total", economy().format(Currency.MONEY, amountOf(uuid)));
            }
            done.run();
        }));
    }
}
