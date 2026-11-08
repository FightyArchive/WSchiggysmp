package de.schiggy.bounty;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.economy.AmountParser;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.bounty.BountyService.Target;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

public final class BountyCommand implements TabExecutor {

    private final SchiggyBounty plugin;

    public BountyCommand(SchiggyBounty plugin) {
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
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.bounty.use")) {
            msg().send(sender, "no-permission");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "" , "list" -> openList(player);
            case "set", "add" -> set(player, args);
            case "check" -> check(player, args);
            default -> msg().send(player, "usage");
        }
        return true;
    }

    private void set(Player player, String[] args) {
        if (args.length < 3) {
            msg().send(player, "usage");
            return;
        }
        OptionalLong parsed = AmountParser.parse(args[2], Currency.MONEY, false);
        if (parsed.isEmpty()) {
            msg().send(player, "invalid-amount");
            return;
        }
        long amount = parsed.getAsLong();
        long min = Currency.MONEY.toMinor(plugin.getConfig().getDouble("min-amount", 1000));
        long max = Currency.MONEY.toMinor(plugin.getConfig().getDouble("max-amount", 1_000_000_000));
        if (amount < min || amount > max) {
            msg().send(player, "amount-range", "min", economy().format(Currency.MONEY, min),
                    "max", economy().format(Currency.MONEY, max));
            return;
        }
        economy().findPlayer(args[1]).thenAccept(found -> SchiggyScheduler.entity(plugin, player, () -> {
            if (found.isEmpty()) {
                msg().send(player, "player-unknown", "player", args[1]);
            } else if (found.get().uuid().equals(player.getUniqueId())) {
                msg().send(player, "self");
            } else {
                plugin.service().place(player, found.get().uuid(), found.get().name(), amount);
            }
        }));
    }

    private void check(Player player, String[] args) {
        if (args.length < 2) {
            msg().send(player, "usage");
            return;
        }
        economy().findPlayer(args[1]).thenAccept(found -> {
            if (found.isEmpty()) {
                SchiggyScheduler.entity(plugin, player, () -> msg().send(player, "player-unknown", "player", args[1]));
                return;
            }
            plugin.service().total(found.get().uuid()).thenAccept(total -> SchiggyScheduler.entity(plugin, player, () -> {
                if (total <= 0) {
                    msg().send(player, "check-none", "player", found.get().name());
                } else {
                    msg().send(player, "check", "player", found.get().name(),
                            "total", economy().format(Currency.MONEY, total));
                }
            }));
        });
    }

    private void openList(Player player) {
        plugin.service().top(45).thenAccept(list -> SchiggyScheduler.entity(plugin, player, () -> {
            SchiggyGui gui = new SchiggyGui(msg().component("gui-title"), 6);
            for (int i = 0; i < list.size() && i < 45; i++) {
                Target target = list.get(i);
                gui.set(i, ItemBuilder.head(Bukkit.getOfflinePlayer(target.uuid()))
                        .name(msg().raw("gui-head-name", "player", target.name()))
                        .lore(msg().rawList("gui-head-lore",
                                "total", economy().format(Currency.MONEY, target.total()),
                                "count", String.valueOf(target.count())))
                        .build());
            }
            if (list.isEmpty()) {
                gui.set(22, ItemBuilder.of(Material.BARRIER).name(msg().raw("gui-empty")).build());
            }
            gui.set(53, ItemBuilder.of(Material.OAK_SIGN).name(msg().raw("gui-info-name"))
                    .lore(msg().rawList("gui-info-lore")).build());
            gui.open(player);
        }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            out.addAll(List.of("list", "set", "check"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("check"))) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                out.add(online.getName());
            }
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
