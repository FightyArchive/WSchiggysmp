package de.schiggy.shop;

import de.schiggy.core.database.Database;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Names;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.shop.ShopCatalog.ShopItem;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Kauflogik. Reihenfolge: Tageslimit reservieren, Geld abziehen (atomar in der Datenbank von SchiggyEconomy),
 * erst danach das Item geben. Schlaegt der Abzug fehl, wird das Limit wieder freigegeben.
 * Pro Spieler laeuft immer nur ein Kauf gleichzeitig (Schutz vor Doppelklicks).
 */
public final class ShopService {

    private final SchiggyShop plugin;
    private final Database db;
    private volatile ShopCatalog catalog;
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    public ShopService(SchiggyShop plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS fshop_limits ("
                        + "uuid TEXT NOT NULL, item TEXT NOT NULL, day INTEGER NOT NULL, bought INTEGER NOT NULL, "
                        + "PRIMARY KEY (uuid, item))");
            }
            return null;
        }).join();
        reload();
    }

    public void reload() {
        this.catalog = ShopCatalog.load(plugin);
    }

    ShopCatalog catalog() {
        return catalog;
    }

    public void clearProcessing(UUID uuid) {
        processing.remove(uuid);
    }

    // ------------------------------------------------------------------ Tageslimits

    private static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    /** true, wenn die Menge innerhalb des Tageslimits liegt und reserviert wurde. */
    private CompletableFuture<Boolean> reserve(UUID uuid, String key, int amount, int limit) {
        if (limit <= 0) {
            return CompletableFuture.completedFuture(true);
        }
        return db.<Boolean>transaction(c -> {
            long today = today();
            long bought = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT day, bought FROM fshop_limits WHERE uuid = ? AND item = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong(1) == today) {
                        bought = rs.getLong(2);
                    }
                }
            }
            if (bought + amount > limit) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fshop_limits(uuid, item, day, bought) VALUES(?,?,?,?) "
                            + "ON CONFLICT(uuid, item) DO UPDATE SET day = excluded.day, bought = excluded.bought")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, key);
                ps.setLong(3, today);
                ps.setLong(4, bought + amount);
                ps.executeUpdate();
            }
            return true;
        });
    }

    private void release(UUID uuid, String key, int amount, int limit) {
        if (limit <= 0) {
            return;
        }
        db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE fshop_limits SET bought = MAX(bought - ?, 0) WHERE uuid = ? AND item = ? AND day = ?")) {
                ps.setInt(1, amount);
                ps.setString(2, uuid.toString());
                ps.setString(3, key);
                ps.setLong(4, today());
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Tageslimit konnte nicht freigegeben werden", ex);
            return null;
        });
    }

    // ------------------------------------------------------------------ Kaufen

    /** Klartext-Name eines Items fuer Nachrichten. */
    String plainName(ShopItem item) {
        return item.name() != null ? MiniMessage.miniMessage().stripTags(item.name()) : Names.pretty(item.material());
    }

    private static boolean fits(Player player, Material material, int amount) {
        int max = material.getMaxStackSize();
        int free = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                free += max;
            } else if (stack.getType() == material && stack.isSimilar(new ItemStack(material))) {
                free += Math.max(0, max - stack.getAmount());
            }
            if (free >= amount) {
                return true;
            }
        }
        return false;
    }

    /** Muss auf dem Thread des Spielers laufen. */
    void purchase(Player player, ShopItem item, int amount) {
        MessageBundle msg = plugin.messages();
        UUID uuid = player.getUniqueId();
        if (amount < 1 || !processing.add(uuid)) {
            return;
        }
        long total;
        try {
            total = Math.multiplyExact(item.unitPrice(), (long) amount);
        } catch (ArithmeticException e) {
            processing.remove(uuid);
            msg.send(player, "error");
            return;
        }
        if (!item.isCommand() && !fits(player, item.material(), amount)) {
            processing.remove(uuid);
            msg.send(player, "inventory-full");
            return;
        }
        EconomyService economy = SchiggyEconomy.getInstance().economy();
        CompletableFuture<EconomyService.TxResult> chain = reserve(uuid, item.key(), amount, item.dailyLimit())
                .thenCompose(ok -> ok
                        ? economy.withdraw(uuid, item.currency(), total, "SHOP", item.key() + " x" + amount)
                        : CompletableFuture.<EconomyService.TxResult>completedFuture(null));

        chain.whenComplete((result, error) -> {
            boolean success = error == null && result != null && result.status() == EconomyService.Status.SUCCESS;
            if (!success && !(error == null && result == null)) {
                // Limit war reserviert, der Abzug hat aber nicht geklappt
                release(uuid, item.key(), amount, item.dailyLimit());
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Kauf fehlgeschlagen", error);
            }
            SchiggyScheduler.entity(plugin, player, () -> finish(player, item, amount, total, result, error));
        });
    }

    private void finish(Player player, ShopItem item, int amount, long total,
                        EconomyService.TxResult result, Throwable error) {
        processing.remove(player.getUniqueId());
        MessageBundle msg = plugin.messages();
        EconomyService economy = SchiggyEconomy.getInstance().economy();
        String price = economy.format(item.currency(), total);
        if (error != null) {
            msg.send(player, "error");
            return;
        }
        if (result == null) {
            msg.send(player, "limit-reached", "limit", String.valueOf(item.dailyLimit()));
            return;
        }
        switch (result.status()) {
            case SUCCESS -> {
                deliver(player, item, amount);
                msg.send(player, "bought", "amount", String.valueOf(amount), "item", plainName(item), "price", price);
            }
            case INSUFFICIENT_FUNDS -> msg.send(player,
                    item.currency() == Currency.SHARDS ? "not-enough-shards" : "not-enough-money", "price", price);
            default -> msg.send(player, "error");
        }
    }

    private void deliver(Player player, ShopItem item, int amount) {
        if (item.isCommand()) {
            for (String template : item.commands()) {
                String command = template.replace("{player}", player.getName())
                        .replace("{amount}", String.valueOf(amount));
                SchiggyScheduler.global(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            }
            return;
        }
        int remaining = amount;
        int max = item.material().getMaxStackSize();
        while (remaining > 0) {
            int part = Math.min(max, remaining);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(item.material(), part));
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            remaining -= part;
        }
    }
}
