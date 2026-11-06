package de.schiggy.auction;

import de.schiggy.core.database.Database;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Inventories;
import de.schiggy.core.util.Names;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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
 * Auktionshaus. Sicherheit: Eine Auktion wird beim Kauf erst reserviert (nur einer gewinnt), dann wird das Geld
 * abgezogen. Schlaegt das fehl, wird die Reservierung zurueckgenommen. Items verlassen die Datenbank nie doppelt.
 */
public final class AuctionService {

    public static final int PAGE_SIZE = 45;

    public enum Sort {
        NEWEST("created DESC"), OLDEST("created ASC"), PRICE_ASC("price ASC"), PRICE_DESC("price DESC"),
        NAME("material ASC, created DESC"), WORTH("worth DESC, created DESC");

        private final String orderBy;

        Sort(String orderBy) {
            this.orderBy = orderBy;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public record Listing(long id, UUID seller, String sellerName, ItemStack item, long price,
                          long created, long expires, String status) {
    }

    public record Page(List<Listing> listings, int total) {
    }

    public enum CreateStatus { CREATED, LIMIT, ERROR }

    public record CreateResult(CreateStatus status, long id) {
    }

    private enum Kind { SOLD, GONE, NO_MONEY, ERROR }

    private record Outcome(Kind kind, Listing listing) {
    }

    private static final String COLUMNS = "id, seller, seller_name, item, price, created, expires, status";

    private final SchiggyAuction plugin;
    private final Database db;
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    public AuctionService(SchiggyAuction plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fa_listings ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, seller TEXT NOT NULL, seller_name TEXT NOT NULL, "
                        + "item BLOB NOT NULL, material TEXT NOT NULL, amount INTEGER NOT NULL, price INTEGER NOT NULL, "
                        + "created INTEGER NOT NULL, expires INTEGER NOT NULL, status TEXT NOT NULL, buyer TEXT)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fa_status ON fa_listings(status, expires)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fa_seller ON fa_listings(seller, status)");
                // Spalte "worth" (Verkaufswert des Stapels laut SchiggyWorth) bei aelteren Datenbanken nachruesten
                boolean hasWorth = false;
                try (ResultSet columns = st.executeQuery("PRAGMA table_info(fa_listings)")) {
                    while (columns.next()) {
                        if ("worth".equalsIgnoreCase(columns.getString("name"))) {
                            hasWorth = true;
                        }
                    }
                }
                if (!hasWorth) {
                    st.executeUpdate("ALTER TABLE fa_listings ADD COLUMN worth INTEGER NOT NULL DEFAULT 0");
                }
                // Reservierungen, die bei einem Absturz haengen geblieben sind, wieder freigeben
                st.executeUpdate("UPDATE fa_listings SET status = 'ACTIVE', buyer = NULL WHERE status = 'PENDING'");
            }
            return null;
        }).join();
        SchiggyScheduler.asyncRepeating(plugin, this::expire, 30, 60);
    }

    private void expire() {
        db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE fa_listings SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND expires <= ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> fail(ex, null));
    }

    public Set<UUID> processing() {
        return processing;
    }

    // ------------------------------------------------------------------ Lesen

    private Listing read(ResultSet rs) throws SQLException {
        return new Listing(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                ItemStack.deserializeBytes(rs.getBytes(4)), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getString(8));
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public CompletableFuture<Page> browse(String search, Sort sort, int page) {
        long now = System.currentTimeMillis();
        String like = search == null || search.isBlank() ? null
                : "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT).replace(' ', '_')) + "%";
        return db.<Page>submit(c -> {
            String where = "status = 'ACTIVE' AND expires > ?"
                    + (like == null ? "" : " AND LOWER(material) LIKE ? ESCAPE '\\'");
            int total = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM fa_listings WHERE " + where)) {
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
            List<Listing> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM fa_listings WHERE " + where
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

    /** Eigene aktive, abgelaufene und abgebrochene Auktionen (abholbar). */
    public CompletableFuture<List<Listing>> mine(UUID seller) {
        return db.<List<Listing>>submit(c -> {
            List<Listing> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM fa_listings WHERE seller = ? "
                    + "AND status IN ('ACTIVE', 'EXPIRED', 'CANCELLED') "
                    + "ORDER BY CASE status WHEN 'ACTIVE' THEN 1 ELSE 0 END, created DESC LIMIT " + PAGE_SIZE)) {
                ps.setString(1, seller.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(read(rs));
                    }
                }
            }
            return list;
        }).exceptionally(ex -> fail(ex, List.<Listing>of()));
    }

    // ------------------------------------------------------------------ Erstellen, Abbrechen, Abholen

    public CompletableFuture<CreateResult> create(UUID seller, String sellerName, ItemStack item, long price, int max) {
        long hours = Math.max(1, plugin.getConfig().getLong("duration-hours", 48));
        return db.<CreateResult>transaction(c -> {
            long now = System.currentTimeMillis();
            int active = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM fa_listings WHERE seller = ? AND status = 'ACTIVE' AND expires > ?")) {
                ps.setString(1, seller.toString());
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        active = rs.getInt(1);
                    }
                }
            }
            if (active >= max) {
                return new CreateResult(CreateStatus.LIMIT, 0);
            }
            long id = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fa_listings(seller, seller_name, item, material, amount, price, worth, created, expires, status) "
                            + "VALUES(?,?,?,?,?,?,?,?,?, 'ACTIVE')", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, seller.toString());
                ps.setString(2, sellerName);
                ps.setBytes(3, item.serializeAsBytes());
                ps.setString(4, item.getType().name());
                ps.setInt(5, item.getAmount());
                ps.setLong(6, price);
                ps.setLong(7, worthOf(item));
                ps.setLong(8, now);
                ps.setLong(9, now + hours * 3_600_000L);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        id = keys.getLong(1);
                    }
                }
            }
            return new CreateResult(CreateStatus.CREATED, id);
        }).exceptionally(ex -> fail(ex, new CreateResult(CreateStatus.ERROR, 0)));
    }

    /** true, wenn die aktive Auktion abgebrochen wurde (das Item ist danach abholbar). */
    public CompletableFuture<Boolean> cancel(UUID seller, long id) {
        return db.<Boolean>submit(c -> update(c,
                "UPDATE fa_listings SET status = 'CANCELLED' WHERE id = ? AND seller = ? AND status = 'ACTIVE'",
                id, seller.toString()) == 1).exceptionally(ex -> fail(ex, Boolean.FALSE));
    }

    /** Das Item einer abgelaufenen oder abgebrochenen Auktion, genau einmal. */
    public CompletableFuture<Optional<ItemStack>> claim(UUID seller, long id) {
        return db.<Optional<ItemStack>>transaction(c -> {
            ItemStack item = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT item FROM fa_listings WHERE id = ? AND seller = ? "
                    + "AND status IN ('EXPIRED', 'CANCELLED')")) {
                ps.setLong(1, id);
                ps.setString(2, seller.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        item = ItemStack.deserializeBytes(rs.getBytes(1));
                    }
                }
            }
            if (item == null) {
                return Optional.<ItemStack>empty();
            }
            int rows = update(c, "UPDATE fa_listings SET status = 'CLAIMED' WHERE id = ? AND seller = ? "
                    + "AND status IN ('EXPIRED', 'CANCELLED')", id, seller.toString());
            return rows == 1 ? Optional.of(item) : Optional.<ItemStack>empty();
        }).exceptionally(ex -> fail(ex, Optional.<ItemStack>empty()));
    }

    private int update(Connection c, String sql, long id, String text) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, text);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ Kaufen

    private CompletableFuture<Optional<Listing>> reserve(long id, UUID buyer) {
        return db.<Optional<Listing>>transaction(c -> {
            int rows;
            try (PreparedStatement ps = c.prepareStatement("UPDATE fa_listings SET status = 'PENDING', buyer = ? "
                    + "WHERE id = ? AND status = 'ACTIVE' AND expires > ? AND seller != ?")) {
                ps.setString(1, buyer.toString());
                ps.setLong(2, id);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, buyer.toString());
                rows = ps.executeUpdate();
            }
            if (rows != 1) {
                return Optional.<Listing>empty();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM fa_listings WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<Listing>empty();
                }
            }
        });
    }

    private CompletableFuture<Void> setStatus(long id, String from, String to, boolean clearBuyer) {
        return db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE fa_listings SET status = ?"
                    + (clearBuyer ? ", buyer = NULL" : "") + " WHERE id = ? AND status = ?")) {
                ps.setString(1, to);
                ps.setLong(2, id);
                ps.setString(3, from);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Verkaufswert des ganzen Stapels laut SchiggyWorth (0, wenn das Plugin fehlt). */
    private long worthOf(ItemStack item) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("SchiggyWorth")) {
            return 0;
        }
        try {
            return WorthHook.price(item.getType()) * item.getAmount();
        } catch (Throwable t) {
            return 0;
        }
    }


    /** Maximale Anzahl: Config-Standard oder das hoechste Recht schiggy.ah.slots.<Zahl> (z. B. per LuckPerms-Rang). */
    public int slotsFor(org.bukkit.entity.Player player) {
        int best = Math.max(1, plugin.getConfig().getInt("max-listings", 10));
        String prefix = "schiggy.ah.slots.";
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

    long fee(long price) {
        double percent = Math.max(0, Math.min(100, plugin.getConfig().getDouble("sale-fee-percent", 5)));
        return Math.round(price * percent / 100.0);
    }

    /** Muss auf dem Thread des Spielers laufen. */
    void purchase(Player player, Listing shown) {
        UUID uuid = player.getUniqueId();
        if (!processing.add(uuid)) {
            return;
        }
        if (!Inventories.fits(player, shown.item())) {
            processing.remove(uuid);
            plugin.messages().send(player, "inventory-full");
            return;
        }
        EconomyService economy = SchiggyEconomy.getInstance().economy();
        reserve(shown.id(), uuid).thenCompose(reserved -> {
            if (reserved.isEmpty()) {
                return CompletableFuture.completedFuture(new Outcome(Kind.GONE, null));
            }
            Listing listing = reserved.get();
            return economy.withdraw(uuid, Currency.MONEY, listing.price(), "AUCTION_BUY", "id=" + listing.id())
                    .thenCompose(tx -> {
                        if (tx.status() == EconomyService.Status.SUCCESS) {
                            long payout = listing.price() - fee(listing.price());
                            return setStatus(listing.id(), "PENDING", "SOLD", false)
                                    .thenCompose(done -> economy.deposit(listing.seller(), Currency.MONEY, payout,
                                            "AUCTION_SELL", "id=" + listing.id()))
                                    .thenApply(deposit -> {
                                        if (deposit.status() != EconomyService.Status.SUCCESS) {
                                            plugin.getLogger().severe("Auszahlung an " + listing.sellerName()
                                                    + " fuer Auktion " + listing.id() + " fehlgeschlagen: " + deposit.status());
                                        }
                                        return new Outcome(Kind.SOLD, listing);
                                    });
                        }
                        Kind kind = tx.status() == EconomyService.Status.INSUFFICIENT_FUNDS ? Kind.NO_MONEY : Kind.ERROR;
                        return setStatus(listing.id(), "PENDING", "ACTIVE", true)
                                .thenApply(done -> new Outcome(kind, listing));
                    });
        }).whenComplete((outcome, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Auktions-Kauf fehlgeschlagen", error);
            }
            SchiggyScheduler.entity(plugin, player, () -> finish(player, outcome, error));
        });
    }

    private void finish(Player player, Outcome outcome, Throwable error) {
        processing.remove(player.getUniqueId());
        var msg = plugin.messages();
        var economy = SchiggyEconomy.getInstance().economy();
        if (error != null || outcome == null) {
            msg.send(player, "error");
            return;
        }
        switch (outcome.kind()) {
            case GONE -> msg.send(player, "listing-gone");
            case NO_MONEY -> msg.send(player, "not-enough-money",
                    "price", economy.format(Currency.MONEY, outcome.listing().price()));
            case SOLD -> {
                Listing listing = outcome.listing();
                Inventories.give(player, listing.item());
                String item = Names.pretty(listing.item().getType());
                String amount = String.valueOf(listing.item().getAmount());
                msg.send(player, "bought", "amount", amount, "item", item,
                        "price", economy.format(Currency.MONEY, listing.price()));
                Player seller = Bukkit.getPlayer(listing.seller());
                if (seller != null && notificationsEnabled(listing.seller())) {
                    long fee = fee(listing.price());
                    SchiggyScheduler.entity(plugin, seller, () -> msg.send(seller, "sold-notify",
                            "buyer", player.getName(), "amount", amount, "item", item,
                            "payout", economy.format(Currency.MONEY, listing.price() - fee),
                            "fee", economy.format(Currency.MONEY, fee)));
                }
            }
            default -> msg.send(player, "error");
        }
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
        plugin.getLogger().log(Level.SEVERE, "Datenbankfehler im Auktionshaus", ex);
        return fallback;
    }
}
