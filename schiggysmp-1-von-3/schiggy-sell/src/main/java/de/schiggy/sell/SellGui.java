package de.schiggy.sell;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.sell.SellService.Quote;
import de.schiggy.sell.SellService.Stats;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Baut das Verkaufsmenue und aktualisiert Wert und Statistik. Alle Texte stehen in der messages.yml. */
final class SellGui {

    private final SchiggySell plugin;

    SellGui(SchiggySell plugin) {
        this.plugin = plugin;
    }

    /** Muss auf dem Thread des Spielers laufen. */
    void open(Player player) {
        MessageBundle msg = plugin.messages();
        SellHolder holder = new SellHolder(msg.component("gui-sell-title"));
        Inventory inventory = holder.getInventory();

        ItemStack filler = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name("<gray> ").build();
        for (int slot = SellHolder.ITEM_SLOTS; slot < 54; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(SellHolder.SELL_SLOT, ItemBuilder.of(Material.LIME_CONCRETE)
                .name(msg.raw("gui-sell-button-name"))
                .lore(msg.rawList("gui-sell-button-lore"))
                .build());
        inventory.setItem(SellHolder.CANCEL_SLOT, ItemBuilder.of(Material.RED_CONCRETE)
                .name(msg.raw("gui-sell-cancel-name"))
                .lore(msg.rawList("gui-sell-cancel-lore"))
                .build());
        refresh(player, holder);
        player.openInventory(inventory);
    }

    /** Aktualisiert Wert und Statistik. Muss auf dem Thread des Spielers laufen. */
    void refresh(Player player, SellHolder holder) {
        MessageBundle msg = plugin.messages();
        EconomyService economy = SchiggyEconomy.getInstance().economy();
        Inventory inventory = holder.getInventory();

        List<ItemStack> contents = new ArrayList<>();
        for (int slot = 0; slot < SellHolder.ITEM_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null) {
                contents.add(item);
            }
        }
        Quote quote = plugin.service().quote(player, contents);
        inventory.setItem(SellHolder.INFO_SLOT, ItemBuilder.of(Material.GOLD_INGOT)
                .name(msg.raw("gui-sell-info-name"))
                .lore(msg.rawList("gui-sell-info-lore",
                        "items", String.valueOf(quote.items()),
                        "base", economy.format(Currency.MONEY, quote.baseCents()),
                        "multiplier", SellService.formatMultiplier(quote.multiplier()),
                        "total", economy.format(Currency.MONEY, quote.payoutCents())))
                .build());

        Stats stats = plugin.service().stats(player);
        String next = stats.nextRemainingCents() == null
                ? msg.raw("gui-next-max")
                : economy.format(Currency.MONEY, stats.nextRemainingCents());
        inventory.setItem(SellHolder.STATS_SLOT, ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
                .name(msg.raw("gui-sell-stats-name"))
                .lore(msg.rawList("gui-sell-stats-lore",
                        "level", String.valueOf(stats.level().number()),
                        "multiplier", SellService.formatMultiplier(stats.totalMultiplier()),
                        "earned", economy.format(Currency.MONEY, stats.earnedCents()),
                        "next", next))
                .build());
    }

    /** Zeigt die letzten Verkaeufe. Muss auf dem Thread des Spielers laufen. */
    void openHistory(Player player, List<SellService.Sale> sales) {
        MessageBundle msg = plugin.messages();
        EconomyService economy = SchiggyEconomy.getInstance().economy();
        de.schiggy.core.gui.SchiggyGui gui = new de.schiggy.core.gui.SchiggyGui(msg.component("gui-history-title"), 6);
        for (int i = 0; i < sales.size() && i < 45; i++) {
            SellService.Sale sale = sales.get(i);
            gui.set(i, ItemBuilder.of(Material.PAPER)
                    .name(msg.raw("gui-history-name", "time",
                            de.schiggy.core.util.Names.duration(System.currentTimeMillis() - sale.time())))
                    .lore(msg.rawList("gui-history-lore",
                            "items", String.valueOf(sale.items()),
                            "base", economy.format(Currency.MONEY, sale.baseCents()),
                            "multiplier", SellService.formatMultiplier(sale.multiplier()),
                            "payout", economy.format(Currency.MONEY, sale.payoutCents())))
                    .build());
        }
        if (sales.isEmpty()) {
            gui.set(22, ItemBuilder.of(Material.BARRIER).name(msg.raw("gui-history-empty")).build());
        }
        gui.open(player);
    }

    /** /sellmulti: alle Stufen mit Multiplikator, Voraussetzung und Fortschritt. */
    void openMulti(Player player) {
        MessageBundle msg = plugin.messages();
        EconomyService economy = SchiggyEconomy.getInstance().economy();
        Stats stats = plugin.service().stats(player);
        List<SellService.Level> levels = plugin.service().levels();
        int count = Math.min(28, levels.size());
        int rows = Math.min(6, Math.max(3, (count + 6) / 7 + 2));
        SchiggyGui gui = new SchiggyGui(msg.component("gui-multi-title"), rows);

        for (int i = 0; i < count; i++) {
            SellService.Level level = levels.get(i);
            int slot = (1 + i / 7) * 9 + 1 + (i % 7);
            int current = stats.level().number();
            Material icon = level.number() < current ? Material.EMERALD
                    : level.number() == current ? Material.NETHER_STAR : Material.GRAY_DYE;
            List<String> lore = new ArrayList<>(msg.rawList("gui-multi-level-lore",
                    "level", String.valueOf(level.number()),
                    "multiplier", SellService.formatMultiplier(level.multiplier()),
                    "required", economy.format(Currency.MONEY, level.requiredCents())));
            if (level.number() < current) {
                lore.add(msg.raw("gui-multi-status-reached"));
            } else if (level.number() == current) {
                lore.add(msg.raw("gui-multi-status-current"));
                if (stats.nextRemainingCents() != null) {
                    lore.add(msg.raw("gui-multi-remaining", "remaining",
                            economy.format(Currency.MONEY, stats.nextRemainingCents())));
                }
            } else {
                lore.add(msg.raw("gui-multi-status-locked"));
            }
            gui.set(slot, ItemBuilder.of(icon)
                    .name(msg.raw("gui-multi-level-name", "level", String.valueOf(level.number()),
                            "multiplier", SellService.formatMultiplier(level.multiplier())))
                    .lore(lore).build());
        }
        gui.set(rows * 9 - 5, ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
                .name(msg.raw("gui-multi-info-name"))
                .lore(msg.rawList("gui-multi-info-lore",
                        "level", String.valueOf(stats.level().number()),
                        "multiplier", SellService.formatMultiplier(stats.totalMultiplier()),
                        "earned", economy.format(Currency.MONEY, stats.earnedCents())))
                .build());
        gui.open(player);
    }
}
