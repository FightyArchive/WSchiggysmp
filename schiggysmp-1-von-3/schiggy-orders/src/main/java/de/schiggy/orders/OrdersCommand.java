package de.schiggy.orders;

import de.schiggy.core.message.MessageBundle;
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

public final class OrdersCommand implements TabExecutor {

    private final SchiggyOrders plugin;

    public OrdersCommand(SchiggyOrders plugin) {
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
            if (!sender.hasPermission("schiggy.orders.admin")) {
                msg().send(sender, "no-permission");
                return true;
            }
            plugin.reloadConfig();
            plugin.gui().reloadItems();
            msg().reload();
            msg().send(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.orders.use")) {
            msg().send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "" -> plugin.gui().open(player, 0);
            case "mine" -> plugin.gui().openMine(player);
            case "create" -> create(player, args);
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

    /** /order create [Menge] [Preis pro Stueck] [Item] */
    private void create(Player player, String[] args) {
        if (args.length < 3) {
            msg().send(player, "create-usage");
            return;
        }
        Material material;
        if (args.length >= 4) {
            material = Material.matchMaterial(args[3]);
            if (material == null || !material.isItem() || material.isAir()) {
                msg().send(player, "create-unknown-item", "item", args[3]);
                return;
            }
        } else {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                msg().send(player, "create-no-item");
                return;
            }
            material = hand.getType();
        }
        if (plugin.getConfig().getStringList("blacklist").contains(material.name())) {
            msg().send(player, "create-blacklisted");
            return;
        }

        int maxAmount = Math.max(1, plugin.getConfig().getInt("max-amount", 2304));
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            amount = -1;
        }
        if (amount < 1 || amount > maxAmount) {
            msg().send(player, "create-invalid-amount", "max", String.valueOf(maxAmount));
            return;
        }

        OptionalLong parsed = AmountParser.parse(args[2], Currency.MONEY, false);
        if (parsed.isEmpty()) {
            msg().send(player, "create-invalid-price");
            return;
        }
        long unit = parsed.getAsLong();
        long min = Currency.MONEY.toMinor(plugin.getConfig().getDouble("min-unit-price", 0.5));
        long max = Currency.MONEY.toMinor(plugin.getConfig().getDouble("max-unit-price", 1_000_000));
        if (unit < min || unit > max) {
            msg().send(player, "create-price-range", "min", economy().format(Currency.MONEY, min),
                    "max", economy().format(Currency.MONEY, max));
            return;
        }
        plugin.service().createOrder(player, material, amount, unit);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("create", "search", "mine"));
            if (sender.hasPermission("schiggy.orders.admin")) {
                out.add("reload");
            }
            return out.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        return List.of();
    }
}
