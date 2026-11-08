package de.schiggy.orders;

import de.schiggy.core.database.Database;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Inventories;
import de.schiggy.core.util.Names;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Auftragsmarkt. Beim Erstellen wird das Geld der ganzen Order reserviert (Escrow). Lieferungen werden atomar in der
 * Datenbank verbucht (keine Ueberzahlung, keine doppelte Erfuellung), danach bekommt der Lieferant sein Geld.
 * Bestellte Items holt der Besteller selbst ab.
 */
public final class OrdersService {

    public static final int PAGE_SIZE = 45;

    public enum Sort {
        NEWEST("created DESC"), OLDEST("created ASC"), PRICE_DESC("unit_price DESC"), PRICE_ASC("unit_price ASC"),
        NAME("material ASC, created DESC"), WORTH("worth DESC, created DESC");

        private final String orderBy;

        Sort(String orderBy) {
            this.orderBy = orderBy;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public record Order(long id, UUID buyer, String buyerName, Material material, int total, int filled,
                        int claimed, long unitPrice, long created, long expires, String status) {
        public int remaining() {
            return total - filled;
        }

        public int claimable() {
            return filled - claimed;
        }
    }

    public record Page(List<Order> orders, int total) {
    }

    public enum CreateKind { CREATED, LIMIT, NO_MONEY, ERROR }

    private enum FulfillKind { DONE, GONE, PAYOUT_FAILED }

    private record Fulfillment(FulfillKind kind, Order order, long payout, long fee) {
    }

    private static final String COLUMNS =
            "id, buyer, buyer_name, material, total, filled, claimed, unit_price, created, expires, status";

    private final SchiggyOrders plugin;
    private final Database db;
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    public OrdersService(SchiggyOrders plugin, Database db) {
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
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fo_orders ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, buyer TEXT NOT NULL, buyer_name TEXT NOT NULL, "
                        + "material TEXT NOT NULL, total INTEGER NOT NULL, filled INTEGER NOT NULL, "
                        + "claimed INTEGER NOT NULL, unit_price INTEGER NOT NULL, created INTEGER NOT NULL, "
                        + "expires INTEGER NOT NULL, status TEXT NOT NULL)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fo_status ON fo_orders(status, expires)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fo_buyer ON fo_orders(buyer, status)");
                // Spalte "worth" (Verkaufswert pro Stueck laut SchiggyWorth) bei aelteren Datenbanken nachruesten
                boolean hasWorth = false;
                try (ResultSet columns = st.executeQuery("PRAGMA table_info(fo_orders)")) {
                    while (columns.next()) {
                        if ("worth".equalsIgnoreCase(columns.getString("name"))) {
                            hasWorth = true;
                        }
                    }
                }
                if (!hasWorth) {
                    st.executeUpdate("ALTER TABLE fo_orders ADD COLUMN worth INTEGER NOT NULL DEFAULT 0");
                }
            }
            return null;
        }).join();
        SchiggyScheduler.asyncRepeating(plugin, this::expireDue, 30, 60);
    }

    // ------------------------------------------------------------------ Ablauf

    /** Beendet abgelaufene Orders und zahlt das uebrige Geld zurueck (genau einmal pro Order). */
    private void expireDue() {
        try {
            long now = System.currentTimeMillis();
            List<Order> due = db.<List<Order>>submit(c -> {
                List<Order> list = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT " + COLUMNS + " FROM fo_orders WHERE status = 'ACTIVE' AND expires <= ?")) {
                    ps.setLong(1, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            list.add(read(rs));
                        }
                    }
                }
                return list;
            }).join();
            for (Order order : due) {
                boolean changed = db.<Boolean>submit(c -> setStatus(c, order.id(), "ACTIVE", "EXPIRED")).join();
                if (changed) {
                    long refund = order.unitPrice() * order.remaining();
                    if (refund > 0) {
                        economy().deposit(order.buyer(), Currency.MONEY, refund, "ORDER_EXPIRE", "id=" + order.id());
                    }
                    Player buyer = Bukkit.getPlayer(order.buyer());
                    if (buyer != null && notificationsEnabled(order.buyer())) {
                        SchiggyScheduler.entity(plugin, buyer, () -> plugin.messages().send(buyer, "expired-notify",
                                "item", Names.pretty(order.material()),
                                "refund", economy().format(Currency.MONEY, refund)));
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Ablauf der Orders fehlgeschlagen", t);
        }
    }

    private boolean setStatus(Connection c, long id, String from, String to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE fo_orders SET status = ? WHERE id = ? AND status = ?")) {
            ps.setString(1, to);
            ps.setLong(2, id);
            ps.setString(3, from);
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ Lesen

    private Order read(ResultSet rs) throws SQLException {
        Material material = Material.matchMaterial(rs.getString(4));
        return new Order(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                material == null ? Material.BARRIER : material, rs.getInt(5), rs.getInt(6), rs.getInt(7),
                rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getString(11));
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public CompletableFuture<Page> browse(String search, Sort sort, int page) {
        long now = System.currentTimeMillis();
        String like = search == null || search.isBlank() ? null
                : "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT).replace(' ', '_')) + "%";
        return db.<Page>submit(c -> {
            String where = "status = 'ACTIVE' AND expires > ? AND filled < total"
                    + (like == null ? "" : " AND LOWER(material) LIKE ? ESCAPE '\\'");
            int total = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM fo_orders WHERE " + where)) {
                ps.setLong(1, now);
                if (like != null) {
                    ps.setString(2, like);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getInt(1);
                    }
                }
            }
            List<Order> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM fo_orders WHERE " + where
                    + " ORDER BY " + sort.orderBy + " LIMIT " + PAGE_SIZE + " OFFSET ?")) {
                ps.setLong(1, now);
                int index = 2;
                if (like != null) {
                    ps.setString(index++, like);
                }
                ps.setInt(index, Math.max(0, page) * PAGE_SIZE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(read(rs));
                    }
                }
            }
            return new Page(list, total);
        }).exceptionally(ex -> fail(ex, new Page(List.of(), 0)));
    }

    /** Eigene aktive Orders und Orders mit abholbereiten Items. */
    public CompletableFuture<List<Order>> mine(UUID buyer) {
        return db.<List<Order>>submit(c -> {
            List<Order> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM fo_orders WHERE buyer = ? "
                    + "AND (status = 'ACTIVE' OR filled > claimed) ORDER BY created DESC LIMIT " + PAGE_SIZE)) {
                ps.setString(1, buyer.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(read(rs));
                    }
                }
            }
            return list;
        }).exceptionally(ex -> fail(ex, List.<Order>of()));
    }

    // ------------------------------------------------------------------ Erstellen

    /** Muss auf dem Thread des Spielers laufen. Zieht das Geld ab und legt die Order an. */
    void createOrder(Player player, Material material, int amount, long unitPrice) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long total;
        try {
            total = Math.multiplyExact(unitPrice, (long) amount);
        } catch (ArithmeticException e) {
            plugin.messages().send(player, "error");
            return;
        }
        int max = slotsFor(player);
        long hours = Math.max(1, plugin.getConfig().getLong("duration-hours", 72));
        long reserved = total;

        activeCount(uuid).thenCompose(count -> {
            if (count >= max) {
                return CompletableFuture.completedFuture(CreateKind.LIMIT);
            }
            return economy().withdraw(uuid, Currency.MONEY, reserved, "ORDER_CREATE", material.name() + " x" + amount)
                    .thenCompose(tx -> {
                        if (tx.status() != EconomyService.Status.SUCCESS) {
                            return CompletableFuture.completedFuture(
                                    tx.status() == EconomyService.Status.INSUFFICIENT_FUNDS
                                            ? CreateKind.NO_MONEY : CreateKind.ERROR);
                        }
                        return insert(uuid, name, material, amount, unitPrice, worthOf(material), hours).thenCompose(id -> {
                            if (id > 0) {
                                return CompletableFuture.completedFuture(CreateKind.CREATED);
                            }
                            return economy().deposit(uuid, Currency.MONEY, reserved, "ORDER_REFUND", "insert failed")
                                    .thenApply(refund -> CreateKind.ERROR);
                        });
                    });
        }).whenComplete((kind, error) -> SchiggyScheduler.entity(plugin, player, () -> {
            var msg = plugin.messages();
            if (error != null || kind == null) {
                plugin.getLogger().log(Level.SEVERE, "Order konnte nicht erstellt werden", error);
                msg.send(player, "error");
                return;
            }
            switch (kind) {
                case CREATED -> msg.send(player, "create-success",
                        "amount", String.valueOf(amount), "item", Names.pretty(material),
                        "unit", economy().format(Currency.MONEY, unitPrice),
                        "total", economy().format(Currency.MONEY, reserved), "duration", hours + "h");
                case LIMIT -> msg.send(player, "create-limit", "limit", String.valueOf(max));
                case NO_MONEY -> msg.send(player, "create-no-money", "total", economy().format(Currency.MONEY, reserved));
                default -> msg.send(player, "error");
            }
        }));
    }

    private CompletableFuture<Integer> activeCount(UUID buyer) {
        return db.<Integer>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM fo_orders WHERE buyer = ? AND status = 'ACTIVE' AND expires > ?")) {
                ps.setString(1, buyer.toString());
                ps.setLong(2, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private CompletableFuture<Long> insert(UUID buyer, String name, Material material, int amount, long unit,
                                           long worth, long hours) {
        return db.<Long>submit(c -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fo_orders(buyer, buyer_name, material, total, filled, claimed, unit_price, worth, created, "
                            + "expires, status) VALUES(?,?,?,?,0,0,?,?,?,?, 'ACTIVE')", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, buyer.toString());
                ps.setString(2, name);
                ps.setString(3, material.name());
                ps.setInt(4, amount);
                ps.setLong(5, unit);
                ps.setLong(6, worth);
                ps.setLong(7, now);
                ps.setLong(8, now + hours * 3_600_000L);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            }
        }).exceptionally(ex -> fail(ex, 0L));
    }

    // ------------------------------------------------------------------ Liefern

    /** Verkaufswert pro Stueck laut SchiggyWorth (0, wenn das Plugin fehlt). */
    private long worthOf(Material material) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("SchiggyWorth")) {
            return 0;
        }
        try {
            return WorthHook.price(material);
        } catch (Throwable t) {
            return 0;
        }
    }


    /** Maximale Anzahl: Config-Standard oder das hoechste Recht schiggy.order.slots.<Zahl> (z. B. per LuckPerms-Rang). */
    public int slotsFor(org.bukkit.entity.Player player) {
        int best = Math.max(1, plugin.getConfig().getInt("max-orders", 5));
        String prefix = "schiggy.order.slots.";
        for (org.bukkit.permissions.PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String permission = info.getPermission().toLowerCase(java.util.Locale.ROOT);
            if (info.getValue() && permission.startsWith(prefix)) {
                try {
                    best = Math.max(best, Integer.parseInt(permission.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // keine Zahl
                }
            }
        }
        return best;
    }

    long fee(long gross) {
        double percent = Math.max(0, Math.min(100, plugin.getConfig().getDouble("fee-percent", 5)));
        return Math.round(gross * percent / 100.0);
    }

    /** Muss auf dem Thread des Spielers laufen. Nimmt passende Items aus dem Inventar und liefert sie. */
    void fulfill(Player player, Order shown) {
        UUID uuid = player.getUniqueId();
        var msg = plugin.messages();
        if (shown.buyer().equals(uuid)) {
            msg.send(player, "own-order");
            return;
        }
        if (!processing.add(uuid)) {
            return;
        }
        int have = Inventories.countPlain(player, shown.material());
        int want = Math.min(have, shown.remaining());
        if (want <= 0) {
            processing.remove(uuid);
            msg.send(player, "no-items", "item", Names.pretty(shown.material()));
            return;
        }
        int removed = Inventories.removePlain(player, shown.material(), want);
        if (removed <= 0) {
            processing.remove(uuid);
            return;
        }

        deliver(shown.id(), removed, uuid).thenCompose(updated -> {
            if (updated.isEmpty()) {
                return CompletableFuture.completedFuture(new Fulfillment(FulfillKind.GONE, null, 0, 0));
            }
            Order order = updated.get();
            long gross = order.unitPrice() * removed;
            long fee = fee(gross);
            return economy().deposit(uuid, Currency.MONEY, gross - fee, "ORDER_FULFILL", "id=" + order.id())
                    .thenCompose(deposit -> {
                        if (deposit.status() == EconomyService.Status.SUCCESS) {
                            return CompletableFuture.completedFuture(
                                    new Fulfillment(FulfillKind.DONE, order, gross - fee, fee));
                        }
                        return revertDelivery(order.id(), removed)
                                .thenApply(done -> new Fulfillment(FulfillKind.PAYOUT_FAILED, order, 0, 0));
                    });
        }).whenComplete((result, error) -> SchiggyScheduler.entity(plugin, player, () -> {
            processing.remove(uuid);
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Lieferung fehlgeschlagen", error);
            }
            if (error != null || result == null || result.kind() != FulfillKind.DONE) {
                Inventories.giveMaterial(player, shown.material(), removed);
                msg.send(player, error == null && result != null && result.kind() == FulfillKind.GONE
                        ? "order-gone" : "error");
                return;
            }
            String item = Names.pretty(shown.material());
            msg.send(player, "fulfilled", "amount", String.valueOf(removed), "item", item,
                    "payout", economy().format(Currency.MONEY, result.payout()),
                    "fee", economy().format(Currency.MONEY, result.fee()));
            Player buyer = Bukkit.getPlayer(result.order().buyer());
            if (buyer != null && notificationsEnabled(result.order().buyer())) {
                SchiggyScheduler.entity(plugin, buyer, () -> msg.send(buyer, "filled-notify",
                        "player", player.getName(), "amount", String.valueOf(removed), "item", item));
            }
        }));
    }

    /** Verbucht eine Lieferung atomar. Leer, wenn die Order nicht mehr passt (z. B. schon voll oder abgelaufen). */
    private CompletableFuture<Optional<Order>> deliver(long id, int amount, UUID fulfiller) {
        return db.<Optional<Order>>transaction(c -> {
            int rows;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE fo_orders SET filled = filled + ?, "
                            + "status = CASE WHEN filled + ? >= total THEN 'COMPLETED' ELSE 'ACTIVE' END "
                            + "WHERE id = ? AND status = 'ACTIVE' AND filled + ? <= total AND buyer != ? AND expires > ?")) {
                ps.setInt(1, amount);
                ps.setInt(2, amount);
                ps.setLong(3, id);
                ps.setInt(4, amount);
                ps.setString(5, fulfiller.toString());
                ps.setLong(6, System.currentTimeMillis());
                rows = ps.executeUpdate();
            }
            if (rows != 1) {
                return Optional.<Order>empty();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM fo_orders WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<Order>empty();
                }
            }
        });
    }

    private CompletableFuture<Void> revertDelivery(long id, int amount) {
        return db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE fo_orders SET filled = filled - ?, status = 'ACTIVE' "
                    + "WHERE id = ? AND status IN ('ACTIVE', 'COMPLETED')")) {
                ps.setInt(1, amount);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ Abbrechen und Abholen

    /** Bricht ab und zahlt das uebrige Geld zurueck. Muss auf dem Thread des Spielers laufen. */
    void cancel(Player player, Order order, Runnable done) {
        UUID uuid = player.getUniqueId();
        db.<Long>transaction(c -> {
            if (!setStatus(c, order.id(), "ACTIVE", "CANCELLED")) {
                return -1L;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT unit_price * (total - filled) FROM fo_orders WHERE id = ? AND buyer = ?")) {
                ps.setLong(1, order.id());
                ps.setString(2, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }).thenCompose(refund -> {
            if (refund < 0) {
                return CompletableFuture.completedFuture(refund);
            }
            if (refund == 0) {
                return CompletableFuture.completedFuture(0L);
            }
            return economy().deposit(uuid, Currency.MONEY, refund, "ORDER_CANCEL", "id=" + order.id())
                    .thenApply(tx -> refund);
        }).whenComplete((refund, error) -> SchiggyScheduler.entity(plugin, player, () -> {
            if (error != null || refund == null || refund < 0) {
                plugin.messages().send(player, "cancel-failed");
            } else {
                plugin.messages().send(player, "cancelled", "refund", economy().format(Currency.MONEY, refund));
            }
            done.run();
        }));
    }

    /** Holt die gelieferten Items ab (genau einmal). Muss auf dem Thread des Spielers laufen. */
    void claim(Player player, Order order, Runnable done) {
        UUID uuid = player.getUniqueId();
        db.<Integer>transaction(c -> {
            int filled;
            int claimed;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT filled, claimed FROM fo_orders WHERE id = ? AND buyer = ?")) {
                ps.setLong(1, order.id());
                ps.setString(2, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return 0;
                    }
                    filled = rs.getInt(1);
                    claimed = rs.getInt(2);
                }
            }
            int available = filled - claimed;
            if (available <= 0) {
                return 0;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE fo_orders SET claimed = ? WHERE id = ? AND buyer = ? AND claimed = ? AND filled = ?")) {
                ps.setInt(1, filled);
                ps.setLong(2, order.id());
                ps.setString(3, uuid.toString());
                ps.setInt(4, claimed);
                ps.setInt(5, filled);
                return ps.executeUpdate() == 1 ? available : 0;
            }
        }).whenComplete((amount, error) -> SchiggyScheduler.entity(plugin, player, () -> {
            if (error != null || amount == null || amount <= 0) {
                plugin.messages().send(player, "claim-failed");
            } else {
                Inventories.giveMaterial(player, order.material(), amount);
                plugin.messages().send(player, "claimed", "amount", String.valueOf(amount),
                        "item", Names.pretty(order.material()));
            }
            done.run();
        }));
    }


    /** Hat der Spieler diese Meldungen in /settings eingeschaltet? */
    private boolean notificationsEnabled(java.util.UUID uuid) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("SchiggySettings")) {
            return true;
        }
        try {
            return SettingsHook.enabled(uuid);
        } catch (Throwable t) {
            return true;
        }
    }

    private <T> T fail(Throwable ex, T fallback) {
        plugin.getLogger().log(Level.SEVERE, "Datenbankfehler im Auftragsmarkt", ex);
        return fallback;
    }
}
