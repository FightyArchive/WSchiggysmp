package de.schiggy.gamble;

import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Investitions-Menue: oben die Schnellwahl-Betraege, unten dein Investment und die Abheben-Knoepfe. */
final class InvestGui {

    private static final int[] TIER_SLOTS = {10, 12, 14, 16};
    private static final Material[] TIER_ICONS = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK, Material.EMERALD_BLOCK};

    private final SchiggyGamble plugin;

    InvestGui(SchiggyGamble plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    /** Muss auf dem Thread des Spielers laufen. */
    void open(Player player) {
        InvestService service = plugin.service();
        SchiggyGui gui = new SchiggyGui(msg().component("gui-title"), 3);
        String percent = String.format(Locale.US, "%.2f", service.ratePercent());

        List<Double> amounts = new ArrayList<>();
        for (Object entry : plugin.getConfig().getList("amounts", List.of())) {
            if (entry instanceof Number number && number.doubleValue() > 0) {
                amounts.add(number.doubleValue());
            }
        }
        for (int i = 0; i < amounts.size() && i < TIER_SLOTS.length; i++) {
            long cents = Currency.MONEY.toMinor(amounts.get(i));
            String formatted = economy().format(Currency.MONEY, cents);
            gui.set(TIER_SLOTS[i], ItemBuilder.of(TIER_ICONS[i])
                    .name(msg().raw("gui-tier-name", "amount", formatted))
                    .lore(msg().rawList("gui-tier-lore", "amount", formatted, "percent", percent))
                    .build(), event -> service.invest(player, cents, () -> open(player)));
        }

        long total = service.amountOf(player.getUniqueId());
        long income = service.incomePerSecond(total);
        gui.set(22, ItemBuilder.of(Material.CLOCK).name(msg().raw("gui-status-name"))
                .lore(msg().rawList("gui-status-lore",
                        "total", economy().format(Currency.MONEY, total),
                        "income", economy().format(Currency.MONEY, income),
                        "income_minute", economy().format(Currency.MONEY, income * 60),
                        "percent", percent)).build());
        gui.set(20, ItemBuilder.of(Material.RED_CONCRETE).name(msg().raw("gui-withdraw-all-name"))
                .lore(msg().rawList("gui-withdraw-all-lore", "total", economy().format(Currency.MONEY, total))).build(),
                event -> service.withdraw(player, 0, () -> open(player)));
        gui.set(24, ItemBuilder.of(Material.ORANGE_CONCRETE).name(msg().raw("gui-withdraw-half-name"))
                .lore(msg().rawList("gui-withdraw-half-lore", "half", economy().format(Currency.MONEY, total / 2))).build(),
                event -> service.withdraw(player, Math.max(1, total / 2), () -> open(player)));
        gui.open(player);
    }
}
