package de.schiggy.auction;

import de.schiggy.auction.AuctionService.CreateResult;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Inventories;
import de.schiggy.core.util.Names;
import de.schiggy.economy.AmountParser;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.stream.Collectors;

public final class AuctionCommand implements TabExecutor {

    private final SchiggyAuction plugin;

    public AuctionCommand(SchiggyAuction plugin) {
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
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("reload")) {
            if (!sender.hasPermission("schiggy.auction.admin")) {
                msg().send(sender, "no-permission");
                return true;
            }
            plugin.reloadConfig();
            msg().reload();
            msg().send(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.auction.use")) {
            msg().send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "" -> plugin.gui().open(player, 0);
            case "mine" -> plugin.gui().openMine(player);
            case "sell" -> sell(player, args);
            case "search" -> {
                String text = args.length > 1 ? String.join(" ", List.of(args).subList(1, args.length)) : null;
                plugin.gui().state(player).search = text;
                msg().send(player, "search-set", "text", text == null ? "-" : text);
                plugin.gui().open(player, 0);
            }
            default -> msg().send(player, "usage");
        }
        return true;
    }

    private void sell(Player player, String[] args) {
        if (args.length < 2) {
            msg().send(player, "sell-usage");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            msg().send(player, "sell-hold-item");
            return;
        }
        if (plugin.getConfig().getStringList("blacklist").contains(hand.getType().name())) {
            msg().send(player, "sell-blacklisted");
            return;
        }
        OptionalLong parsed = AmountParser.parse(args[1], Currency.MONEY, false);
        if (parsed.isEmpty()) {
            msg().send(player, "sell-invalid-price");
            return;
        }
        long price = parsed.getAsLong();
        long min = Currency.MONEY.toMinor(plugin.getConfig().getDouble("min-price", 1));
        long max = Currency.MONEY.toMinor(plugin.getConfig().getDouble("max-price", 1_000_000_000));
        if (price < min || price > max) {
            msg().send(player, "sell-price-range", "min", economy().format(Currency.MONEY, min),
                    "max", economy().format(Currency.MONEY, max));
            return;
        }

        // Item zuerst aus der Hand nehmen, danach speichern. Bei Fehlern wird es zurueckgegeben.
        ItemStack item = hand.clone();
        player.getInventory().setItemInMainHand(null);
        plugin.service().create(player.getUniqueId(), player.getName(), item, price, plugin.service().slotsFor(player)).thenAccept(result ->
                SchiggyScheduler.entity(plugin, player, () -> afterCreate(player, item, price, result)));
    }

    private void afterCreate(Player player, ItemStack item, long price, CreateResult result) {
        switch (result.status()) {
            case CREATED -> msg().send(player, "sell-success",
                    "amount", String.valueOf(item.getAmount()), "item", Names.pretty(item.getType()),
                    "price", economy().format(Currency.MONEY, price),
                    "fee", String.valueOf(plugin.getConfig().getDouble("sale-fee-percent", 5)),
                    "duration", plugin.getConfig().getLong("duration-hours", 48) + "h");
            case LIMIT -> {
                Inventories.give(player, item);
                msg().send(player, "sell-limit", "limit", String.valueOf(plugin.service().slotsFor(player)));
            }
            default -> {
                Inventories.give(player, item);
                msg().send(player, "error");
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("sell", "search", "mine"));
            if (sender.hasPermission("schiggy.auction.admin")) {
                out.add("reload");
            }
            return out.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("search")) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Material material : List.of(Material.DIAMOND, Material.NETHERITE_INGOT, Material.ELYTRA, Material.TOTEM_OF_UNDYING)) {
                if (material.name().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    out.add(material.name().toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }
        return List.of();
    }
}
