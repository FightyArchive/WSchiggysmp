package de.schiggy.gamble;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.economy.AmountParser;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.gamble.InvestService.View;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/** /invest, /claiminvest, /deleteinvest, /autocollecttoggle und /investadmin. */
public final class InvestCommand implements TabExecutor {

    private final SchiggyGamble plugin;

    public InvestCommand(SchiggyGamble plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("investadmin")) {
            admin(sender, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.invest.use")) {
            msg().send(sender, "no-permission");
            return true;
        }
        Runnable none = () -> { };
        switch (name) {
            case "claiminvest" -> plugin.service().claim(player, none);
            case "deleteinvest" -> plugin.service().delete(player, none);
            case "autocollecttoggle" -> plugin.service().toggleAuto(player, none);
            default -> {
                if (args.length == 0) {
                    plugin.gui().open(player);
                    return true;
                }
                OptionalLong parsed = AmountParser.parse(args[0], Currency.MONEY, false);
                if (parsed.isEmpty()) {
                    msg().send(player, "usage");
                    return true;
                }
                plugin.service().invest(player, parsed.getAsLong(), none);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ /investadmin

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("schiggy.invest.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "set" -> set(sender, args);
            case "view" -> withPlayer(sender, args, (uuid, name) -> plugin.service().adminView(uuid).thenAccept(view ->
                    SchiggyScheduler.forSender(plugin, sender, () -> showView(sender, name, view))));
            case "reset" -> withPlayer(sender, args, (uuid, name) -> {
                boolean refund = args.length > 2 && args[2].equalsIgnoreCase("refund");
                plugin.service().adminReset(uuid, refund).thenAccept(amount -> SchiggyScheduler.forSender(plugin, sender, () ->
                        msg().send(sender, "admin-reset", "player", name, "amount", economy().format(Currency.MONEY, amount))));
            });
            case "setearned" -> {
                if (args.length < 3) {
                    sendUsage(sender);
                    return;
                }
                OptionalLong parsed = AmountParser.parse(args[2], Currency.MONEY, true);
                if (parsed.isEmpty()) {
                    msg().send(sender, "admin-invalid");
                    return;
                }
                long cents = parsed.getAsLong();
                withPlayer(sender, args, (uuid, name) -> plugin.service().adminSetEarned(uuid, cents).thenRun(() ->
                        SchiggyScheduler.forSender(plugin, sender, () -> msg().send(sender, "admin-setearned",
                                "player", name, "amount", economy().format(Currency.MONEY, cents)))));
            }
            case "reload" -> {
                plugin.reloadConfig();
                msg().reload();
                msg().send(sender, "reloaded");
            }
            default -> sendUsage(sender);
        }
    }

    private void sendUsage(CommandSender sender) {
        for (String line : msg().rawList("admin-usage")) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                    line.replace("{prefix}", msg().raw("prefix"))));
        }
    }

    private interface PlayerAction {
        void run(java.util.UUID uuid, String name);
    }

    private void withPlayer(CommandSender sender, String[] args, PlayerAction action) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        economy().findPlayer(args[1]).thenAccept(found -> {
            if (found.isEmpty()) {
                SchiggyScheduler.forSender(plugin, sender, () -> msg().send(sender, "admin-unknown-player", "player", args[1]));
            } else {
                action.run(found.get().uuid(), found.get().name());
            }
        });
    }

    private void showView(CommandSender sender, String name, View view) {
        msg().send(sender, "admin-view-header", "player", name);
        msg().send(sender, "admin-view-line", "label", msg().raw("label-invested"), "value", economy().format(Currency.MONEY, view.invested()));
        msg().send(sender, "admin-view-line", "label", msg().raw("label-pending"), "value", economy().format(Currency.MONEY, view.pending()));
        msg().send(sender, "admin-view-line", "label", msg().raw("label-earned"), "value", economy().format(Currency.MONEY, view.earned()));
        msg().send(sender, "admin-view-line", "label", msg().raw("label-auto"), "value", view.auto() ? "an" : "aus");
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendUsage(sender);
            return;
        }
        String setting = args[1].toLowerCase(Locale.ROOT);
        String value = args[2];
        try {
            switch (setting) {
                case "rate" -> {
                    double rate = Double.parseDouble(value);
                    if (rate < 0 || rate > 1 || Double.isNaN(rate)) {
                        throw new NumberFormatException();
                    }
                    plugin.getConfig().set("rate", rate);
                }
                case "interval" -> {
                    long ticks = Long.parseLong(value);
                    if (ticks < 1 || ticks > 72_000) {
                        throw new NumberFormatException();
                    }
                    plugin.getConfig().set("interval-ticks", ticks);
                }
                case "max" -> {
                    OptionalLong cents = AmountParser.parse(value, Currency.MONEY, false);
                    if (cents.isEmpty()) {
                        throw new NumberFormatException();
                    }
                    plugin.getConfig().set("max-investment", cents.getAsLong() / 100.0);
                }
                default -> {
                    sendUsage(sender);
                    return;
                }
            }
        } catch (NumberFormatException e) {
            msg().send(sender, "admin-invalid");
            return;
        }
        plugin.saveConfig();
        msg().send(sender, "admin-set", "setting", setting, "value", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (command.getName().equalsIgnoreCase("investadmin") && sender.hasPermission("schiggy.invest.admin")) {
            if (args.length == 1) {
                out.addAll(List.of("set", "view", "reset", "setearned", "reload"));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                out.addAll(List.of("rate", "interval", "max"));
            } else if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    out.add(online.getName());
                }
            } else if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
                out.add("refund");
            }
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
