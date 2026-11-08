package de.schiggy.gamble;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.AmountParser;
import de.schiggy.economy.Currency;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
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
        if (args[0].equalsIgnoreCase("withdraw")) {
            long amount = 0;
            if (args.length > 1 && !args[1].equalsIgnoreCase("all")) {
                OptionalLong parsed = AmountParser.parse(args[1], Currency.MONEY, false);
                if (parsed.isEmpty()) {
                    msg().send(player, "invalid-amount");
                    return true;
                }
                amount = parsed.getAsLong();
            }
            plugin.service().withdraw(player, amount, () -> { });
            return true;
        }
        OptionalLong parsed = AmountParser.parse(args[0], Currency.MONEY, false);
        if (parsed.isEmpty()) {
            msg().send(player, "invalid-amount");
            return true;
        }
        plugin.service().invest(player, parsed.getAsLong(), () -> { });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && "withdraw".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("withdraw");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("withdraw")) {
            return List.of("all");
        }
        return List.of();
    }
}
