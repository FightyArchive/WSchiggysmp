package de.schiggy.gamble;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.AmountParser;
import de.schiggy.economy.Currency;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.gamble.InvestService.Investment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.OptionalLong;

public final class InvestCommand implements TabExecutor {

    private final SchiggyGamble plugin;

    public InvestCommand(SchiggyGamble plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.gamble.invest")) {
            msg().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.gui().open(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("collect")) {
            collectFirstReady(player);
            return true;
        }
        OptionalLong parsed = AmountParser.parse(args[0], Currency.MONEY, false);
        if (parsed.isEmpty()) {
            msg().send(player, "invalid-amount");
            return true;
        }
        long amount = parsed.getAsLong();
        var economy = SchiggyEconomy.getInstance().economy();
        long min = Currency.MONEY.toMinor(plugin.getConfig().getDouble("min-amount", 100));
        long max = Currency.MONEY.toMinor(plugin.getConfig().getDouble("max-amount", 100_000_000));
        if (amount < min || amount > max) {
            msg().send(player, "amount-range", "min", economy.format(Currency.MONEY, min),
                    "max", economy.format(Currency.MONEY, max));
            return true;
        }
        plugin.service().start(player, amount, () -> { });
        return true;
    }

    /** /invest collect holt die erste fertige Investition ab. */
    private void collectFirstReady(Player player) {
        plugin.service().running(player.getUniqueId()).thenAccept(list ->
                de.schiggy.core.util.SchiggyScheduler.entity(plugin, player, () -> {
                    long now = System.currentTimeMillis();
                    for (Investment investment : list) {
                        if (investment.matures() <= now) {
                            plugin.service().collect(player, investment, () -> { });
                            return;
                        }
                    }
                    msg().send(player, "nothing-ready");
                }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return "collect".startsWith(args[0].toLowerCase()) ? List.of("collect") : List.of();
        }
        return List.of();
    }
}
