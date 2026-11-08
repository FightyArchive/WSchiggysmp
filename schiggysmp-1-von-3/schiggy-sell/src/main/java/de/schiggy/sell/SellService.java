package de.schiggy.sell;

import de.schiggy.core.database.Database;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.worth.SchiggyWorth;
import de.schiggy.worth.WorthService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verkaufslogik. Sicherheit gegen Duplikate: Items werden zuerst aus dem Inventar bzw. Menue genommen,
 * danach wird das Geld ueber die Datenbank-Transaktion von SchiggyEconomy ausgezahlt. Schlaegt die Auszahlung
 * fehl, bekommt der Spieler seine Items zurueck.
 */
public final class SellService {

    private static final String MULTIPLIER_PREFIX = "schiggy.sell.multiplier.";

    public record Level(int number, long requiredCents, double multiplier) {
    }

    public record Quote(int items, long baseCents, long payoutCents, double multiplier) {
    }

    public record Stats(Level level, double totalMultiplier, long earnedCents, Long nextRemainingCents) {
    }

    /** Ein Eintrag im Verkaufs-Verlauf. */
    public record Sale(long time, int items, long baseCents, long payoutCents, double multiplier) {
    }

    private final SchiggySell plugin;
    private final Database db;
    /** earned (Cent), items */
    private final Map<UUID, long[]> cache = new ConcurrentHashMap<>();
    private volatile List<Level> levels = List.of(new Level(1, 0, 1.0));

    public SellService(SchiggySell plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    // ------------------------------------------------------------------ Setup

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fsell_players ("
                        + "uuid TEXT PRIMARY KEY, earned INTEGER NOT NULL, items INTEGER NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fsell_history ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, time INTEGER NOT NULL, "
                        + "items INTEGER NOT NULL, base INTEGER NOT NULL, payout INTEGER NOT NULL, multiplier REAL NOT NULL)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fsell_history ON fsell_history(uuid, time)");
            }
            int days = plugin.getConfig().getInt("history-days", 30);
            if (days > 0) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM fsell_history WHERE time < ?")) {
                    ps.setLong(1, System.currentTimeMillis() - days * 86_400_000L);
                    ps.executeUpdate();
                }
            }
            return null;
        }).join();
        reloadLevels();
    }

    public void reloadLevels() {
        List<double[]> raw = new ArrayList<>();
        for (Map<?, ?> map : plugin.getConfig().getMapList("levels")) {
            Object required = map.get("required");
            Object multiplier = map.get("multiplier");
            if (required instanceof Number r && multiplier instanceof Number m) {
                raw.add(new double[]{Math.round(r.doubleValue() * 100), m.doubleValue()});
            }
        }
        raw.sort(Comparator.comparingDouble((double[] entry) -> entry[0]));
        List<Level> parsed = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            parsed.add(new Level(i + 1, (long) raw.get(i)[0], raw.get(i)[1]));
        }
        if (parsed.isEmpty()) {
            parsed.add(new Level(1, 0, 1.0));
        }
        this.levels = List.copyOf(parsed);
    }

    /** Beim Login (async) aufgerufen. */
    public CompletableFuture<Void> loadPlayer(UUID uuid) {
        return db.<Void>submit(c -> {
            long earned = 0;
            long items = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT earned, items FROM fsell_players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        earned = rs.getLong(1);
                        items = rs.getLong(2);
                    }
                }
            }
            cache.put(uuid, new long[]{earned, items});
            return null;
        });
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    // ------------------------------------------------------------------ Levels und Statistik

    private long earned(UUID uuid) {
        long[] data = cache.get(uuid);
        return data == null ? 0 : data[0];
    }

    public List<Level> levels() {
        return levels;
    }

    public Level levelFor(long earnedCents) {
        Level result = levels.get(0);
        for (Level level : levels) {
            if (earnedCents >= level.requiredCents()) {
                result = level;
            }
        }
        return result;
    }

    /** Muss auf dem Thread des Spielers laufen (liest Rechte). */
    public Stats stats(Player player) {
        long earned = earned(player.getUniqueId());
        Level level = levelFor(earned);
        Long next = null;
        for (Level candidate : levels) {
            if (candidate.number() == level.number() + 1) {
                next = Math.max(0, candidate.requiredCents() - earned);
            }
        }
        return new Stats(level, level.multiplier() * permissionMultiplier(player), earned, next);
    }

    private double permissionMultiplier(Player player) {
        double best = 1.0;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String permission = info.getPermission().toLowerCase(Locale.ROOT);
            if (permission.startsWith(MULTIPLIER_PREFIX)) {
                try {
                    best = Math.max(best, Double.parseDouble(permission.substring(MULTIPLIER_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // kein Zahlenwert
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ Verkaufen

    private WorthService worth() {
        return SchiggyWorth.getInstance().service();
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    /** Nur unveraenderte Items mit Preis (keine Verzauberungen, keine Namen, keine beschaedigten Werkzeuge). */
    public boolean isSellable(ItemStack item) {
        return item != null && !item.getType().isAir() && worth().hasPrice(item.getType())
                && item.isSimilar(new ItemStack(item.getType()));
    }

    /** Berechnet den Wert, ohne etwas zu veraendern. Muss auf dem Thread des Spielers laufen. */
    public Quote quote(Player player, Collection<ItemStack> items) {
        double multiplier = stats(player).totalMultiplier();
        long base = 0;
        int count = 0;
        try {
            for (ItemStack item : items) {
                if (isSellable(item)) {
                    base = Math.addExact(base, Math.multiplyExact(worth().getPrice(item.getType()), (long) item.getAmount()));
                    count += item.getAmount();
                }
            }
        } catch (ArithmeticException e) {
            return new Quote(0, 0, 0, multiplier);
        }
        long payout = BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(multiplier))
                .setScale(0, RoundingMode.DOWN).longValue();
        return new Quote(count, base, payout, multiplier);
    }

    /**
     * Verkauft Items, die bereits aus dem Inventar bzw. Menue entfernt wurden.
     * Muss auf dem Thread des Spielers laufen. Bei Fehlern werden die Items zurueckgegeben.
     */
    public void sell(Player player, List<ItemStack> sold) {
        MessageBundle msg = plugin.messages();
        UUID uuid = player.getUniqueId();
        Quote quote = quote(player, sold);
        if (quote.payoutCents() <= 0) {
            giveBack(player, sold);
            msg.send(player, "nothing-sellable");
            return;
        }
        int levelBefore = levelFor(earned(uuid)).number();
        economy().deposit(uuid, Currency.MONEY, quote.payoutCents(), "SELL", "items=" + quote.items())
                .thenAccept(result -> SchiggyScheduler.entity(plugin, player, () -> {
                    if (result.status() == EconomyService.Status.SUCCESS) {
                        recordSale(uuid, quote);
                        msg.send(player, "sold", "items", String.valueOf(quote.items()),
                                "amount", economy().format(Currency.MONEY, quote.payoutCents()),
                                "multiplier", formatMultiplier(quote.multiplier()));
                        Level after = levelFor(earned(uuid));
                        if (after.number() > levelBefore) {
                            msg.send(player, "level-up", "level", String.valueOf(after.number()),
                                    "multiplier", formatMultiplier(after.multiplier()));
                        }
                    } else {
                        giveBack(player, sold);
                        msg.send(player, result.status() == EconomyService.Status.LIMIT_EXCEEDED
                                ? "sell-limit" : "error");
                    }
                }));
    }

    /** Statistik sofort im Cache erhoehen, danach asynchron speichern. */
    private void recordSale(UUID uuid, Quote quote) {
        cache.merge(uuid, new long[]{quote.payoutCents(), quote.items()},
                (old, add) -> new long[]{old[0] + add[0], old[1] + add[1]});
        db.<Void>transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fsell_players(uuid, earned, items) VALUES(?,?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET earned = earned + excluded.earned, "
                            + "items = items + excluded.items")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, quote.payoutCents());
                ps.setLong(3, quote.items());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fsell_history(uuid, time, items, base, payout, multiplier) VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, quote.items());
                ps.setLong(4, quote.baseCents());
                ps.setLong(5, quote.payoutCents());
                ps.setDouble(6, quote.multiplier());
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Verkaufs-Statistik konnte nicht gespeichert werden", ex);
            return null;
        });
    }

    /** Die letzten 45 Verkaeufe des Spielers, neueste zuerst. */
    public CompletableFuture<List<Sale>> history(UUID uuid) {
        return db.<List<Sale>>submit(c -> {
            List<Sale> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT time, items, base, payout, multiplier "
                    + "FROM fsell_history WHERE uuid = ? ORDER BY time DESC LIMIT 45")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Sale(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getDouble(5)));
                    }
                }
            }
            return list;
        }).exceptionally(ex -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Verkaufs-Verlauf konnte nicht geladen werden", ex);
            return List.<Sale>of();
        });
    }

    /** Gibt Items zurueck, ueberzaehlige fallen vor die Fuesse des Spielers. Auf dem Thread des Spielers. */
    public void giveBack(Player player, Collection<ItemStack> items) {
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

    public static String formatMultiplier(double multiplier) {
        return String.format(Locale.US, "%.2f", multiplier).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
