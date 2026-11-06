package de.schiggy.bounty;

import de.schiggy.core.database.Database;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Kopfgelder. Das Geld wird beim Setzen sofort abgezogen (Escrow). Beim Kill werden alle offenen Kopfgelder auf das
 * Opfer atomar als "eingeloest" markiert, danach wird dem Moerder das Geld gutgeschrieben. Schlaegt das fehl,
 * werden die Kopfgelder wieder freigegeben.
 */
public final class BountyService {

    public record Target(UUID uuid, String name, long total, int count) {
    }

    private enum PlaceKind { PLACED, NO_MONEY, ERROR }

    private final SchiggyBounty plugin;
    private final Database db;

    public BountyService(SchiggyBounty plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fb_bounties ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, target TEXT NOT NULL, target_name TEXT NOT NULL, "
                        + "placer TEXT NOT NULL, placer_name TEXT NOT NULL, amount INTEGER NOT NULL, "
                        + "created INTEGER NOT NULL, status TEXT NOT NULL, claimer TEXT, claimed INTEGER)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fb_target ON fb_bounties(target, status)");
            }
            return null;
        }).join();
        int days = plugin.getConfig().getInt("expire-days", 0);
        if (days > 0) {
            SchiggyScheduler.asyncRepeating(plugin, this::expire, 60, 600);
        }
    }

    // ------------------------------------------------------------------ Lesen

    public CompletableFuture<Long> total(UUID target) {
        return db.<Long>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(amount), 0) FROM fb_bounties WHERE target = ? AND status = 'ACTIVE'")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }).exceptionally(ex -> fail(ex, 0L));
    }

    /** Die Spieler mit den hoechsten Kopfgeldern. */
    public CompletableFuture<List<Target>> top(int limit) {
        return db.<List<Target>>submit(c -> {
            List<Target> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT target, MAX(target_name), SUM(amount), COUNT(*) FROM fb_bounties WHERE status = 'ACTIVE' "
                            + "GROUP BY target ORDER BY SUM(amount) DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Target(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getInt(4)));
                    }
                }
            }
            return list;
        }).exceptionally(ex -> fail(ex, List.<Target>of()));
    }

    // ------------------------------------------------------------------ Setzen

    /** Muss auf dem Thread des Spielers laufen. */
    void place(Player placer, UUID target, String targetName, long amount) {
        var msg = plugin.messages();
        UUID uuid = placer.getUniqueId();
        economy().withdraw(uuid, Currency.MONEY, amount, "BOUNTY_SET", "target=" + targetName).thenCompose(tx -> {
            if (tx.status() != EconomyService.Status.SUCCESS) {
                return CompletableFuture.completedFuture(tx.status() == EconomyService.Status.INSUFFICIENT_FUNDS
                        ? PlaceKind.NO_MONEY : PlaceKind.ERROR);
            }
            return insert(target, targetName, uuid, placer.getName(), amount).thenCompose(ok -> {
                if (ok) {
                    return CompletableFuture.completedFuture(PlaceKind.PLACED);
                }
                return economy().deposit(uuid, Currency.MONEY, amount, "BOUNTY_REFUND", "insert failed")
                        .thenApply(refund -> PlaceKind.ERROR);
            });
        }).thenCompose(kind -> total(target).thenApply(total -> new Object[]{kind, total}))
                .whenComplete((result, error) -> SchiggyScheduler.entity(plugin, placer, () -> {
                    if (error != null || result == null) {
                        plugin.getLogger().log(Level.SEVERE, "Kopfgeld konnte nicht gesetzt werden", error);
                        msg.send(placer, "error");
                        return;
                    }
                    PlaceKind kind = (PlaceKind) result[0];
                    long total = (Long) result[1];
                    switch (kind) {
                        case PLACED -> placed(placer, target, targetName, amount, total);
                        case NO_MONEY -> msg.send(placer, "no-money", "amount", economy().format(Currency.MONEY, amount));
                        default -> msg.send(placer, "error");
                    }
                }));
    }

    private CompletableFuture<Boolean> insert(UUID target, String targetName, UUID placer, String placerName, long amount) {
        return db.<Boolean>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fb_bounties(target, target_name, placer, placer_name, amount, created, status) "
                            + "VALUES(?,?,?,?,?,?, 'ACTIVE')")) {
                ps.setString(1, target.toString());
                ps.setString(2, targetName);
                ps.setString(3, placer.toString());
                ps.setString(4, placerName);
                ps.setLong(5, amount);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
                return true;
            }
        }).exceptionally(ex -> fail(ex, Boolean.FALSE));
    }

    private void placed(Player placer, UUID target, String targetName, long amount, long total) {
        var msg = plugin.messages();
        String formatted = economy().format(Currency.MONEY, amount);
        msg.send(placer, "placed", "amount", formatted, "player", targetName,
                "total", economy().format(Currency.MONEY, total));
        Player victim = Bukkit.getPlayer(target);
        if (victim != null) {
            SchiggyScheduler.entity(plugin, victim, () -> msg.send(victim, "you-were-targeted", "amount", formatted));
        }
        if (plugin.getConfig().getBoolean("announce-new", true)) {
            var message = msg.component("announce", "placer", placer.getName(), "amount", formatted,
                    "player", targetName, "total", economy().format(Currency.MONEY, total));
            for (Player online : Bukkit.getOnlinePlayers()) {
                SchiggyScheduler.entity(plugin, online, () -> online.sendMessage(message));
            }
        }
    }

    // ------------------------------------------------------------------ Einloesen

    private record Claim(long total, long token) {
    }

    /** Wird beim Tod eines Spielers aufgerufen. Der Moerder bekommt alle Kopfgelder auf das Opfer. */
    void claim(Player victim, Player killer) {
        UUID victimId = victim.getUniqueId();
        UUID killerId = killer.getUniqueId();
        String victimName = victim.getName();
        String killerName = killer.getName();
        long token = System.currentTimeMillis();

        db.<Long>transaction(c -> {
            long sum = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(amount), 0) FROM fb_bounties WHERE target = ? AND status = 'ACTIVE'")) {
                ps.setString(1, victimId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        sum = rs.getLong(1);
                    }
                }
            }
            if (sum <= 0) {
                return 0L;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE fb_bounties SET status = 'CLAIMED', claimer = ?, claimed = ? WHERE target = ? AND status = 'ACTIVE'")) {
                ps.setString(1, killerId.toString());
                ps.setLong(2, token);
                ps.setString(3, victimId.toString());
                ps.executeUpdate();
            }
            return sum;
        }).thenCompose(total -> {
            if (total <= 0) {
                return CompletableFuture.completedFuture(new Claim(0, token));
            }
            double percent = Math.max(0, Math.min(100, plugin.getConfig().getDouble("claim-fee-percent", 10)));
            long payout = total - Math.round(total * percent / 100.0);
            return economy().deposit(killerId, Currency.MONEY, payout, "BOUNTY_CLAIM", "target=" + victimName)
                    .thenCompose(tx -> {
                        if (tx.status() == EconomyService.Status.SUCCESS) {
                            return CompletableFuture.completedFuture(new Claim(total, token));
                        }
                        // Auszahlung fehlgeschlagen: Kopfgelder wieder freigeben
                        return db.<Boolean>submit(c -> {
                            try (PreparedStatement ps = c.prepareStatement("UPDATE fb_bounties SET status = 'ACTIVE', "
                                    + "claimer = NULL, claimed = NULL WHERE target = ? AND claimer = ? AND claimed = ?")) {
                                ps.setString(1, victimId.toString());
                                ps.setString(2, killerId.toString());
                                ps.setLong(3, token);
                                ps.executeUpdate();
                            }
                            return true;
                        }).thenApply(reverted -> new Claim(0, token));
                    });
        }).whenComplete((claim, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Kopfgeld konnte nicht eingeloest werden", error);
                return;
            }
            if (claim == null || claim.total() <= 0) {
                return;
            }
            var msg = plugin.messages();
            double percent = Math.max(0, Math.min(100, plugin.getConfig().getDouble("claim-fee-percent", 10)));
            long fee = Math.round(claim.total() * percent / 100.0);
            String total = economy().format(Currency.MONEY, claim.total());
            SchiggyScheduler.entity(plugin, killer, () -> msg.send(killer, "claimed", "player", victimName,
                    "payout", economy().format(Currency.MONEY, claim.total() - fee),
                    "fee", economy().format(Currency.MONEY, fee)));
            SchiggyScheduler.entity(plugin, victim, () -> msg.send(victim, "lost", "killer", killerName));
            var broadcast = msg.component("claimed-broadcast", "killer", killerName, "total", total, "player", victimName);
            for (Player online : Bukkit.getOnlinePlayers()) {
                SchiggyScheduler.entity(plugin, online, () -> online.sendMessage(broadcast));
            }
        });
    }

    // ------------------------------------------------------------------ Ablauf

    /** Zahlt abgelaufene Kopfgelder an die Absender zurueck (genau einmal pro Eintrag). */
    private void expire() {
        long cutoff = System.currentTimeMillis() - plugin.getConfig().getInt("expire-days", 0) * 86_400_000L;
        try {
            List<long[]> ids = new ArrayList<>();
            List<String[]> info = new ArrayList<>();
            db.<Void>submit(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT id, amount, placer, target_name FROM fb_bounties "
                        + "WHERE status = 'ACTIVE' AND created < ?")) {
                    ps.setLong(1, cutoff);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ids.add(new long[]{rs.getLong(1), rs.getLong(2)});
                            info.add(new String[]{rs.getString(3), rs.getString(4)});
                        }
                    }
                }
                return null;
            }).join();
            for (int i = 0; i < ids.size(); i++) {
                long id = ids.get(i)[0];
                long amount = ids.get(i)[1];
                boolean changed = db.<Boolean>submit(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE fb_bounties SET status = 'EXPIRED' WHERE id = ? AND status = 'ACTIVE'")) {
                        ps.setLong(1, id);
                        return ps.executeUpdate() == 1;
                    }
                }).join();
                if (changed) {
                    UUID placer = UUID.fromString(info.get(i)[0]);
                    String targetName = info.get(i)[1];
                    economy().deposit(placer, Currency.MONEY, amount, "BOUNTY_EXPIRE", "id=" + id);
                    Player online = Bukkit.getPlayer(placer);
                    if (online != null) {
                        SchiggyScheduler.entity(plugin, online, () -> plugin.messages().send(online, "expired",
                                "player", targetName, "amount", economy().format(Currency.MONEY, amount)));
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Ablauf der Kopfgelder fehlgeschlagen", t);
        }
    }

    private <T> T fail(Throwable ex, T fallback) {
        plugin.getLogger().log(Level.SEVERE, "Datenbankfehler im Kopfgeld-System", ex);
        return fallback;
    }
}
