package de.schiggy.shop;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Names;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.shop.ShopCatalog.ShopCategory;
import de.schiggy.shop.ShopCatalog.ShopItem;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Hauptmenue, Kategorien und Kaufmenue. Alle festen Texte stehen in der messages.yml. */
final class ShopGui {

    /** Kategorie-Menue: 27 Slots (3 Reihen). Oben 18 Items, unten die Leiste. */
    private static final int PER_PAGE = 18;

    private final SchiggyShop plugin;

    ShopGui(SchiggyShop plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    private ItemStack balanceItem(Player player) {
        long money = economy().getCachedBalance(player.getUniqueId(), Currency.MONEY).orElse(0L);
        long shards = economy().getCachedBalance(player.getUniqueId(), Currency.SHARDS).orElse(0L);
        return ItemBuilder.of(Material.GOLD_INGOT)
                .name(msg().raw("gui-shop-balance-name"))
                .lore(msg().rawList("gui-shop-balance-lore",
                        "money", economy().format(Currency.MONEY, money),
                        "shards", economy().format(Currency.SHARDS, shards)))
                .build();
    }

    // ------------------------------------------------------------------ Hauptmenue

    /** Muss auf dem Thread des Spielers laufen. */
    void openMain(Player player) {
        ShopCatalog catalog = plugin.service().catalog();
        int rows = catalog.menuRows();
        SchiggyGui gui = new SchiggyGui(msg().component("gui-shop-title"), rows);

        for (ShopCategory category : catalog.categories()) {
            if (category.slot() >= rows * 9) {
                continue;
            }
            gui.set(category.slot(), ItemBuilder.of(category.icon())
                    .name(category.name())
                    .lore(msg().rawList("gui-shop-category-lore", "count", String.valueOf(category.items().size())))
                    .build(), event -> openCategory(player, category, 0));
        }
        gui.open(player);
    }

    // ------------------------------------------------------------------ Kategorie

    void openCategory(Player player, ShopCategory category, int requestedPage) {
        List<ShopItem> items = category.items();
        int pages = Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
        final int page = Math.max(0, Math.min(requestedPage, pages - 1));

        SchiggyGui gui = new SchiggyGui(msg().component("gui-shop-category-title",
                "category", MiniMessage.miniMessage().stripTags(category.name()),
                "page", String.valueOf(page + 1), "pages", String.valueOf(pages)), 3);

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= items.size()) {
                break;
            }
            ShopItem item = items.get(index);
            gui.set(i, display(item), event -> openBuy(player, category, item, page, 1));
        }

        gui.set(18, ItemBuilder.of(Material.ARROW).name(msg().raw("gui-shop-back-name")).build(),
                event -> openMain(player));
        if (page > 0) {
            gui.set(21, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE).name(msg().raw("gui-shop-prev-name")).build(),
                    event -> openCategory(player, category, page - 1));
        }
        gui.set(22, balanceItem(player));
        if (pages > 1) {
            // Seiten ueberspringen: erste Seite, 5 zurueck, 5 vor, letzte Seite
            gui.set(19, ItemBuilder.of(Material.SPECTRAL_ARROW).name(msg().raw("gui-shop-first-name")).build(),
                    event -> openCategory(player, category, 0));
            if (page > 0) {
                gui.set(20, ItemBuilder.of(Material.ARROW).name(msg().raw("gui-shop-skip-back-name")).build(),
                        event -> openCategory(player, category, Math.max(0, page - 5)));
            }
            if (page < pages - 1) {
                gui.set(24, ItemBuilder.of(Material.ARROW).name(msg().raw("gui-shop-skip-next-name")).build(),
                        event -> openCategory(player, category, Math.min(pages - 1, page + 5)));
            }
            gui.set(25, ItemBuilder.of(Material.SPECTRAL_ARROW).name(msg().raw("gui-shop-last-name")).build(),
                    event -> openCategory(player, category, pages - 1));
        }
        if (page < pages - 1) {
            gui.set(23, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE).name(msg().raw("gui-shop-next-name")).build(),
                    event -> openCategory(player, category, page + 1));
        }
        gui.set(26, ItemBuilder.of(Material.BARRIER).name(msg().raw("gui-close-name")).build(),
                event -> player.closeInventory());
        gui.open(player);
    }

    private ItemStack display(ShopItem item) {
        String name = item.name() != null ? item.name()
                : msg().raw("gui-shop-item-name", "item", Names.pretty(item.material()));
        List<String> lore = new ArrayList<>(msg().rawList("gui-shop-item-lore",
                "price", economy().format(item.currency(), item.unitPrice())));
        if (item.dailyLimit() > 0) {
            lore.add(msg().raw("gui-shop-item-limit", "limit", String.valueOf(item.dailyLimit())));
        }
        lore.add("");
        lore.add(msg().raw("gui-shop-item-click"));
        return ItemBuilder.of(item.material()).name(name).lore(lore).build();
    }

    // ------------------------------------------------------------------ Kaufmenue

    void openBuy(Player player, ShopCategory category, ShopItem item, int page, int initialAmount) {
        SchiggyGui gui = new SchiggyGui(msg().component("gui-buy-title"), 3);
        int[] amount = {initialAmount};
        renderBuy(gui, player, category, item, page, amount);
        gui.open(player);
    }

    private void renderBuy(SchiggyGui gui, Player player, ShopCategory category, ShopItem item, int page, int[] amount) {
        int max = Math.max(1, plugin.getConfig().getInt("max-purchase-amount", 576));
        int current = Math.max(1, Math.min(max, amount[0]));
        amount[0] = current;
        long total;
        try {
            total = Math.multiplyExact(item.unitPrice(), (long) current);
        } catch (ArithmeticException e) {
            total = Long.MAX_VALUE;
        }
        String[] placeholders = {
                "item", plugin.service().plainName(item),
                "amount", String.valueOf(current),
                "unit", economy().format(item.currency(), item.unitPrice()),
                "total", economy().format(item.currency(), total)
        };

        gui.set(13, ItemBuilder.of(item.material()).amount(current)
                .name(msg().raw("gui-buy-item-name", placeholders))
                .lore(msg().rawList("gui-buy-item-lore", placeholders))
                .build());

        Runnable rerender = () -> renderBuy(gui, player, category, item, page, amount);
        button(gui, 9, Material.YELLOW_STAINED_GLASS_PANE, msg().raw("gui-buy-set", "n", "1"), amount, v -> 1, rerender);
        button(gui, 10, Material.RED_STAINED_GLASS_PANE, msg().raw("gui-buy-remove", "n", "10"), amount, v -> v - 10, rerender);
        button(gui, 11, Material.RED_STAINED_GLASS_PANE, msg().raw("gui-buy-remove", "n", "1"), amount, v -> v - 1, rerender);
        button(gui, 15, Material.LIME_STAINED_GLASS_PANE, msg().raw("gui-buy-add", "n", "1"), amount, v -> v + 1, rerender);
        button(gui, 16, Material.LIME_STAINED_GLASS_PANE, msg().raw("gui-buy-add", "n", "10"), amount, v -> v + 10, rerender);
        button(gui, 17, Material.YELLOW_STAINED_GLASS_PANE, msg().raw("gui-buy-set", "n", "64"), amount, v -> 64, rerender);

        gui.set(24, ItemBuilder.of(Material.LIME_CONCRETE)
                .name(msg().raw("gui-buy-confirm-name"))
                .lore(msg().rawList("gui-buy-confirm-lore", placeholders))
                .build(), event -> plugin.service().purchase(player, item, amount[0]));
        gui.set(20, ItemBuilder.of(Material.RED_CONCRETE)
                .name(msg().raw("gui-buy-cancel-name"))
                .lore(msg().rawList("gui-buy-cancel-lore"))
                .build(), event -> openCategory(player, category, page));
    }

    private void button(SchiggyGui gui, int slot, Material material, String name, int[] amount,
                        IntUnaryOperator change, Runnable rerender) {
        gui.set(slot, ItemBuilder.of(material).name(name).build(), event -> {
            amount[0] = change.applyAsInt(amount[0]);
            rerender.run();
        });
    }
}
