package de.schiggy.combat;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CombatCommand implements TabExecutor {

    private final SchiggyCombat plugin;

    public CombatCommand(SchiggyCombat plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "status" -> status(sender, args);
            case "remove" -> remove(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("schiggy.combat.admin")) {
                    msg().send(sender, "no-permission");
                } else {
                    plugin.reloadConfig();
                    msg().reload();
                    plugin.service().loadRestrictions();
                    msg().send(sender, "reloaded");
                }
            }
            default -> msg().send(sender, "usage");
        }
        return true;
    }

    private void status(CommandSender sender, String[] args) {
        if (args.length > 1) {
            if (!sender.hasPermission("schiggy.combat.admin")) {
                msg().send(sender, "no-permission");
                return;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg().send(sender, "player-offline");
                return;
            }
            long remaining = plugin.service().remainingMillis(target.getUniqueId());
            if (remaining > 0) {
                msg().send(sender, "status-other-active", "player", target.getName(),
                        "seconds", String.valueOf((remaining + 999) / 1000));
            } else {
                msg().send(sender, "status-other-idle", "player", target.getName());
            }
            return;
        }
        if (!(sender instanceof Player player)) {
            msg().send(sender, "usage");
            return;
        }
        if (!player.hasPermission("schiggy.combat.use")) {
            msg().send(sender, "no-permission");
            return;
        }
        long remaining = plugin.service().remainingMillis(player.getUniqueId());
        if (remaining > 0) {
            msg().send(player, "status-self-active", "seconds", String.valueOf((remaining + 999) / 1000));
        } else {
            msg().send(player, "status-self-idle");
        }
        if (plugin.getServer().getPluginManager().isPluginEnabled("SchiggyStats")) {
            try {
                var snapshot = StatsHook.snapshot(player);
                msg().send(player, "status-stats", "kills", String.valueOf(snapshot.kills()),
                        "deaths", String.valueOf(snapshot.deaths()));
            } catch (Throwable ignored) {
                // Statistiken sind nur eine Zusatzanzeige
            }
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("schiggy.combat.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            msg().send(sender, "usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg().send(sender, "player-offline");
            return;
        }
        if (!plugin.service().isTagged(target.getUniqueId())) {
            msg().send(sender, "not-tagged", "player", target.getName());
            return;
        }
        SchiggyScheduler.entity(plugin, target, () -> {
            plugin.service().untag(target, false);
            msg().send(target, "removed-notify");
        });
        msg().send(sender, "removed", "player", target.getName());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("schiggy.combat.admin");
        if (args.length == 1) {
            out.add("status");
            if (admin) {
                out.addAll(List.of("remove", "reload"));
            }
        } else if (args.length == 2 && admin && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("status"))) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                out.add(online.getName());
            }
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
