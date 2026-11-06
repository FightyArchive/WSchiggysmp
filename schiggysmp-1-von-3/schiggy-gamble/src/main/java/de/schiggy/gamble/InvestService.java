package de.schiggy.gamble;

import de.schiggy.core.database.Database;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Names;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Investitionen. Das Ergebnis wird beim Anlegen ausgewuerfelt und gespeichert. Das Abholen ist atomar
 * (nur einmal moeglich): erst wird die Investition als bezahlt markiert, dann das Geld ausgezahlt.
 */
public final class InvestService {

    public record Investment(long id, long amount, double multiplier, long created, long matures) {
    }

    public record Outcome(double chance, double multiplier) {
    }

    private final SchiggyGamble plugin;
    private final Database db;
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    public InvestService(SchiggyGamble plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    public Set<UUID> processing() {
        return processing;
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fg_invest ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, amount INTEGER NOT NULL, "
                        + "multiplier REAL NOT NULL, created INTEGER NOT NULL, matures INTEGER NOT NULL, status TEXT NOT NULL)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fg_invest ON fg_invest(uuid, status)");
            }
            return null;
        }).join();
    }

    // ------------------------------------------------------------------ Konfiguration

    public List<Outcome> outcomes() {
        List<Outcome> list = new ArrayList<>();
        for (Map<?, ?> map : plugin.getConfig().getMapList("outcomes")) {
            if (map.get("chance") instanceof Number chance && map.get("multiplier") instanceof Number multiplier
                    && chance.doubleValue() > 0 && multiplier.doubleValue() >= 0) {
                list.add(new Outcome(chance.doubleValue(), multiplier.doubleValue()));
            }
        }
        if (list.isEmpty()) {
            list.add(new Outcome(1, 1.0));
        }
        return list;
    }

    private double roll() {
        List<Outcome> outcomes = outcomes();
        double total = outcomes.stream().mapToDouble(Outcome::chance).sum();
        double value = ThreadLocalRandom.current().nextDouble() * total;
        double sum = 0;
        for (Outcome outcome : outcomes) {
            sum += outcome.chance();
            if (value < sum) {
                return outcome.multiplier();
            }
        }
        return outcomes.get(outcomes.size() - 1).multiplier();
    }

    public long durationMillis() {
        return Math.max(1, plugin.getConfig().getLong("duration-minutes", 30)) * 60_000L;
    }

    public int maxActive() {
        return Math.max(1, plugin.getConfig().getInt("max-active", 3));
    }

    // ------------------------------------------------------------------ Lesen

    /** Laufende (auch bereits fertige, noch nicht abgeholte) Investitionen. */
    public CompletableFuture<List<Investment>> running(UUID uuid) {
        return db.<List<Investment>>submit(c -> {
            List<Investment> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, amount, multiplier, created, matures "
                    + "FROM fg_invest WHERE uuid = ? AND status = 'RUNNING' ORDER BY matures ASC LIMIT 7")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Investment(rs.getLong(1), rs.getLong(2), rs.getDouble(3), rs.getLong(4), rs.getLong(5)));
                    }
                }
            }
            return list;
        }).exceptionally(ex -> fail(ex, List.<Investment>of()));
    }

    // ------------------------------------------------------------------ Investieren

    private enum StartKind { STARTED, TOO_MANY, NO_MONEY, ERROR }

    /** Muss auf dem Thread des Spielers laufen. */
    void start(Player player, long amount, Runnable done) {
        UUID uuid = player.getUniqueId();
        var msg = plugin.messages();
        if (!processing.add(uuid)) {
            return;
        }
        long duration = durationMillis();
        int max = maxActive();
        activeCount(uuid).thenCompose(count -> {
            if (count >= max) {
                return CompletableFuture.completedFuture(StartKind.TOO_MANY);
            }
            return economy().withdraw(uuid, Currency.MONEY, amount, "INVEST", "amount=" + amount).thenCompose(tx -> {
                if (tx.status() != EconomyService.Status.SUCCESS) {
                    return CompletableFuture.completedFuture(tx.status() == EconomyService.Status.INSUFFICIENT_FUNDS
                            ? StartKind.NO_MONEY : StartKind.ERROR);
                }
                return insert(uuid, amount, roll(), duration).thenCompose(id -> {
                    if (id > 0) {
                        return CompletableFuture.completedFuture(StartKind.STARTED);
                    }
                    return economy().deposit(uuid, Currency.MONEY, amount, "INVEST_REFUND", "insert failed")
                            .thenApply(refund -> StartKind.ERROR);
                });
            });
        }).whenComplete((kind, error) -> SchiggyScheduler.entity(plugin, player, () -> {
            processing.remove(uuid);
            if (error != null || kind == null) {
                plugin.getLogger().log(Level.SEVERE, "Investition fehlgeschlagen", error);
                msg.send(player, "error");
            } else {
                switch (kind) {
                    case STARTED -> msg.send(player, "started", "amount", economy().format(Currency.MONEY, amount),
                            "time", Names.duration(duration));
                    case TOO_MANY -> msg.send(player, "too-many", "limit", String.valueOf(max));
                    case NO_MONEY -> msg.send(player, "no-money", "amount", economy().format(Currency.MONEY, amount));
                    default -> msg.send(player, "error");
                }
            }
            done.run();
        }));
    }

    private CompletableFuture<Integer> activeCount(UUID uuid) {
        return db.<Integer>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM fg_invest WHERE uuid = ? AND status = 'RUNNING'")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private CompletableFuture<Long> insert(UUID uuid, long amount, double multiplier, long duration) {
        return db.<Long>submit(c -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fg_invest(uuid, amount, multiplier, created, matures, status) VALUES(?,?,?,?,?, 'RUNNING')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, amount);
                ps.setDouble(3, multiplier);
                ps.setLong(4, now);
                ps.setLong(5, now + duration);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            }
        }).exceptionally(ex -> fail(ex, 0L));
    }

    // ------------------------------------------------------------------ Abholen

    private record Collected(boolean ok, long payout) {
    }

    /** Holt eine fertige Investition ab. Muss auf dem Thread des Spielers laufen. */
    void collect(Player player, Investment investment, Runnable done) {
        UUID uuid = player.getUniqueId();
        var msg = plugin.messages();
        if (investment.matures() > System.currentTimeMillis()) {
            msg.send(player, "not-ready", "time", Names.duration(investment.matures() - System.currentTimeMillis()));
            done.run();
            return;
        }
        if (!processing.add(uuid)) {
            return;
        }
        db.<Boolean>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE fg_invest SET status = 'PAID' "
                    + "WHERE id = ? AND uuid = ? AND status = 'RUNNING' AND matures <= ?")) {
                ps.setLong(1, investment.id());
                ps.setString(2, uuid.toString());
                ps.setLong(3, System.currentTimeMillis());
                return ps.executeUpdate() == 1;
            }
        }).thenCompose(claimed -> {
            if (!claimed) {
                return CompletableFuture.completedFuture(new Collected(false, 0));
            }
            long payout = BigDecimal.valueOf(investment.amount()).multiply(BigDecimal.valueOf(investment.multiplier()))
                    .setScale(0, RoundingMode.DOWN).longValue();
            if (payout <= 0) {
                return CompletableFuture.completedFuture(new Collected(true, 0));
            }
            return economy().deposit(uuid, Currency.MONEY, payout, "INVEST_PAYOUT", "id=" + investment.id())
                    .thenCompose(tx -> {
                        if (tx.status() == EconomyService.Status.SUCCESS) {
                            return CompletableFuture.completedFuture(new Collected(true, payout));
                        }
                        // Auszahlung fehlgeschlagen: wieder auf "laeuft" setzen, damit nichts verloren geht
                        return db.<Boolean>submit(c -> {
                            try (PreparedStatement ps = c.prepareStatement(
                                    "UPDATE fg_invest SET status = 'RUNNING' WHERE id = ? AND status = 'PAID'")) {
                                ps.setLong(1, investment.id());
                                ps.executeUpdate();
                            }
                            return true;
                        }).thenApply(reverted -> new Collected(false, 0));
                    });
        }).whenComplete((result, error) -> SchiggyScheduler.entity(plugin, player, () -> {
            processing.remove(uuid);
            if (error != null || result == null || !result.ok()) {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Abholen fehlgeschlagen", error);
                }
                msg.send(player, "error");
            } else {
                String key = result.payout() > investment.amount() ? "collect-profit"
                        : result.payout() < investment.amount() ? "collect-loss" : "collect-even";
                msg.send(player, key, "amount", economy().format(Currency.MONEY, investment.amount()),
                        "payout", economy().format(Currency.MONEY, result.payout()),
                        "multiplier", String.format(java.util.Locale.US, "%.2f", investment.multiplier()));
            }
            done.run();
        }));
    }

    private <T> T fail(Throwable ex, T fallback) {
        plugin.getLogger().log(Level.SEVERE, "Datenbankfehler in SchiggyGamble", ex);
        return fallback;
    }
}
