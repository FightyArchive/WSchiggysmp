package de.schiggy.gamble;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Names;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.gamble.InvestService.Investment;
import de.schiggy.gamble.InvestService.Outcome;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Investitions-Menue: oben die Schnellwahl-Betraege, darunter die laufenden Investitionen, unten die Erklaerung. */
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

    void open(Player player) {
        plugin.service().running(player.getUniqueId()).thenAccept(list ->
                SchiggyScheduler.entity(plugin, player, () -> render(player, list)));
    }

    private void render(Player player, List<Investment> running) {
        InvestService service = plugin.service();
        SchiggyGui gui = new SchiggyGui(msg().component("gui-title"), 5);

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
                    .lore(msg().rawList("gui-tier-lore", "amount", formatted,
                            "duration", Names.duration(service.durationMillis())))
                    .build(), event -> service.start(player, cents, () -> open(player)));
        }

        long now = System.currentTimeMillis();
        for (int i = 0; i < running.size() && i < 7; i++) {
            Investment investment = running.get(i);
            boolean ready = investment.matures() <= now;
            String amount = economy().format(Currency.MONEY, investment.amount());
            String time = Names.duration(investment.matures() - now);
            gui.set(28 + i, ItemBuilder.of(ready ? Material.EMERALD : Material.CLOCK)
                    .name(msg().raw(ready ? "gui-ready-name" : "gui-running-name", "amount", amount))
                    .lore(msg().rawList(ready ? "gui-ready-lore" : "gui-running-lore", "amount", amount, "time", time))
                    .build(), event -> service.collect(player, investment, () -> open(player)));
        }

        List<String> info = new ArrayList<>(msg().rawList("gui-info-lore"));
        double total = service.outcomes().stream().mapToDouble(Outcome::chance).sum();
        for (Outcome outcome : service.outcomes()) {
            info.add(msg().raw("gui-info-outcome",
                    "multiplier", String.format(Locale.US, "%.2f", outcome.multiplier()),
                    "chance", String.format(Locale.US, "%.1f", outcome.chance() / total * 100)));
        }
        info.addAll(msg().rawList("gui-info-footer", "limit", String.valueOf(service.maxActive())));
        gui.set(40, ItemBuilder.of(Material.BOOK).name(msg().raw("gui-info-name")).lore(info).build());
        gui.open(player);
    }
}
