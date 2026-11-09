package de.schiggy.gamble;

import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.ChatPrompt;
import de.schiggy.core.util.Names;
import de.schiggy.economy.AmountParser;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.gamble.InvestService.View;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/** Investment-Menue: Info, Betrag investieren, Einkommen abholen, Auto-Einsammeln, Investment loeschen. */
final class InvestGui {

    private static final int[] TIER_SLOTS = {11, 12, 14, 15};
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

    private String money(long cents) {
        return economy().format(Currency.MONEY, cents);
    }

    /** Muss auf dem Thread des Spielers laufen. */
    void open(Player player) {
        InvestService service = plugin.service();
        View view = service.view(player.getUniqueId());
        SchiggyGui gui = new SchiggyGui(msg().component("gui-title"), 3);
        Runnable reopen = () -> open(player);

        long balance = economy().getCachedBalance(player.getUniqueId(), Currency.MONEY).orElse(0L);
        gui.set(13, ItemBuilder.of(Material.GOLD_BLOCK).name(msg().raw("gui-info-name"))
                .lore(msg().rawList("gui-info-lore",
                        "invested", money(view.invested()),
                        "balance", money(balance),
                        "earned", money(view.earned()),
                        "pending", money(view.pending()),
                        "rate", String.format(Locale.US, "%.4f", service.rate() * 100).replaceAll("0+$", "").replaceAll("\\.$", ""),
                        "interval", Names.duration(service.intervalMillis()),
                        "next", view.invested() > 0 ? Names.duration(view.nextMillis()) : "-",
                        "max", money(service.maxInvestment()))).build());

        gui.set(10, ItemBuilder.of(Material.NAME_TAG).name(msg().raw("gui-custom-name"))
                .lore(msg().rawList("gui-custom-lore")).build(), event -> {
                    player.closeInventory();
                    msg().send(player, "amount-prompt");
                    ChatPrompt.ask(plugin, player, text -> {
                        if (text == null) {
                            open(player);
                            return;
                        }
                        OptionalLong parsed = AmountParser.parse(text, Currency.MONEY, false);
                        if (parsed.isEmpty()) {
                            msg().send(player, "invalid-amount");
                            open(player);
                        } else {
                            service.invest(player, parsed.getAsLong(), reopen);
                        }
                    });
                });

        List<Double> amounts = new ArrayList<>();
        for (Object entry : plugin.getConfig().getList("quick-amounts", List.of())) {
            if (entry instanceof Number number && number.doubleValue() > 0) {
                amounts.add(number.doubleValue());
            }
        }
        for (int i = 0; i < amounts.size() && i < TIER_SLOTS.length; i++) {
            long cents = Currency.MONEY.toMinor(amounts.get(i));
            gui.set(TIER_SLOTS[i], ItemBuilder.of(TIER_ICONS[i])
                    .name(msg().raw("gui-tier-name", "amount", money(cents)))
                    .lore(msg().rawList("gui-tier-lore", "income", money(service.incomePerInterval(cents)))).build(),
                    event -> service.invest(player, cents, reopen));
        }

        gui.set(19, ItemBuilder.of(Material.LIME_CONCRETE).name(msg().raw("gui-claim-name"))
                .lore(msg().rawList("gui-claim-lore", "pending", money(view.pending()))).build(),
                event -> service.claim(player, reopen));
        gui.set(21, ItemBuilder.of(view.auto() ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(msg().raw(view.auto() ? "gui-auto-on-name" : "gui-auto-off-name"))
                .lore(msg().rawList("gui-auto-lore")).build(), event -> service.toggleAuto(player, reopen));
        gui.set(23, ItemBuilder.of(Material.RED_CONCRETE).name(msg().raw("gui-delete-name"))
                .lore(msg().rawList("gui-delete-lore", "invested", money(view.invested()), "pending", money(view.pending()))).build(),
                event -> service.delete(player, reopen));
        gui.open(player);
    }
}
