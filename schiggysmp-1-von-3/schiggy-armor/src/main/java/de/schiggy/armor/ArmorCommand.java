package de.schiggy.armor;

import de.schiggy.armor.ArmorSets.ArmorSet;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Inventories;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ArmorCommand implements TabExecutor {

    private final SchiggyArmor plugin;

    public ArmorCommand(SchiggyArmor plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("schiggy.armor.admin")) {
            msg().send(sender, "no-permission");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "list" -> {
                msg().send(sender, "list-header");
                for (ArmorSet set : plugin.sets().all().values()) {
                    msg().send(sender, "list-line", "id", set.id(), "pieces", String.valueOf(set.pieces().size()));
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                msg().reload();
                plugin.sets().reload();
                msg().send(sender, "reloaded", "count", String.valueOf(plugin.sets().all().size()));
            }
            case "give" -> give(sender, args);
            default -> msg().send(sender, "usage");
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg().send(sender, "usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg().send(sender, "player-offline");
            return;
        }
        ArmorSet set = plugin.sets().get(args[2]);
        if (set == null) {
            msg().send(sender, "unknown-set", "set", args[2], "sets", String.join(", ", plugin.sets().all().keySet()));
            return;
        }
        String which = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "all";
        List<String> pieces = new ArrayList<>();
        if (which.equals("all")) {
            pieces.addAll(set.pieces().keySet());
        } else if (set.pieces().containsKey(which)) {
            pieces.add(which);
        } else {
            msg().send(sender, "unknown-piece", "piece", which);
            return;
        }
        // Items muessen auf dem Thread des Spielers vergeben werden
        target.getScheduler().run(plugin, task -> {
            for (String piece : pieces) {
                ItemStack item = plugin.sets().create(set, piece, msg());
                Inventories.give(target, item);
            }
            msg().send(target, "received", "set", set.id());
        }, null);
        msg().send(sender, "given", "player", target.getName(), "count", String.valueOf(pieces.size()), "set", set.id());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("schiggy.armor.admin")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            out.addAll(List.of("give", "list", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                out.add(player.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            out.addAll(plugin.sets().all().keySet());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            out.addAll(List.of("all", "helmet", "chestplate", "leggings", "boots"));
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
