package de.schiggy.worth;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
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

public final class WorthCommand implements TabExecutor {

    private final SchiggyWorth plugin;
    private final WorthGui gui;

    public WorthCommand(SchiggyWorth plugin) {
        this.plugin = plugin;
        this.gui = new WorthGui(plugin);
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private WorthService service() {
        return plugin.service();
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    private void reply(CommandSender sender, Runnable task) {
        SchiggyScheduler.forSender(plugin, sender, task);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "list" -> list(sender, args);
            case "set" -> set(sender, args);
            case "remove" -> remove(sender, args);
            case "import" -> importPrices(sender, args);
            default -> lookup(sender, args);
        }
        return true;
    }

    // ------------------------------------------------------------------ /worth [Item]

    private void lookup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("schiggy.worth.use")) {
            msg().send(sender, "no-permission");
            return;
        }
        Material material;
        int handAmount = 0;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                msg().send(sender, "players-only");
                return;
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                msg().send(sender, "hold-item");
                return;
            }
            material = hand.getType();
            handAmount = hand.getAmount();
        } else {
            material = Material.matchMaterial(args[0]);
            if (material == null || !material.isItem() || material.isAir()) {
                msg().send(sender, "unknown-item", "item", args[0]);
                return;
            }
        }
        String item = Names.pretty(material);
        long price = service().getPrice(material);
        if (price <= 0) {
            msg().send(sender, "worth-none", "item", item);
            return;
        }
        msg().send(sender, "worth-info", "item", item,
                "price", economy().format(Currency.MONEY, price),
                "stack", economy().format(Currency.MONEY, price * material.getMaxStackSize()));
        if (handAmount > 1) {
            msg().send(sender, "worth-hand", "amount", String.valueOf(handAmount),
                    "total", economy().format(Currency.MONEY, price * handAmount));
        }
    }

    // ------------------------------------------------------------------ /worth list

    private void list(CommandSender sender, String[] args) {
        if (!sender.hasPermission("schiggy.worth.use")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        int page = 0;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]) - 1;
            } catch (NumberFormatException ignored) {
                // ungueltige Seite: erste Seite
            }
        }
        gui.open(player, page);
    }

    // ------------------------------------------------------------------ Admin

    private void set(CommandSender sender, String[] args) {
        if (!sender.hasPermission("schiggy.worth.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        Material material;
        String priceArg;
        if (args.length == 2) {
            if (!(sender instanceof Player player)) {
                msg().send(sender, "players-only");
                return;
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                msg().send(sender, "hold-item");
                return;
            }
            material = hand.getType();
            priceArg = args[1];
        } else if (args.length >= 3) {
            material = Material.matchMaterial(args[1]);
            if (material == null || !material.isItem() || material.isAir()) {
                msg().send(sender, "unknown-item", "item", args[1]);
                return;
            }
            priceArg = args[2];
        } else {
            msg().send(sender, "usage-set");
            return;
        }
        OptionalLong cents = AmountParser.parse(priceArg, Currency.MONEY, false);
        if (cents.isEmpty()) {
            msg().send(sender, "invalid-price");
            return;
        }
        long price = cents.getAsLong();
        Material target = material;
        service().setPrice(target, price).thenRun(() -> reply(sender, () ->
                msg().send(sender, "price-set", "item", Names.pretty(target),
                        "price", economy().format(Currency.MONEY, price))));
    }

    private void remove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("schiggy.worth.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        Material material;
        if (args.length >= 2) {
            material = Material.matchMaterial(args[1]);
            if (material == null || !material.isItem() || material.isAir()) {
                msg().send(sender, "unknown-item", "item", args[1]);
                return;
            }
        } else if (sender instanceof Player player && !player.getInventory().getItemInMainHand().getType().isAir()) {
            material = player.getInventory().getItemInMainHand().getType();
        } else {
            msg().send(sender, "usage-remove");
            return;
        }
        Material target = material;
        service().removePrice(target).thenAccept(existed -> reply(sender, () -> {
            if (existed) {
                msg().send(sender, "price-removed", "item", Names.pretty(target));
            } else {
                msg().send(sender, "price-not-set", "item", Names.pretty(target));
            }
        }));
    }

    private void importPrices(CommandSender sender, String[] args) {
        if (!sender.hasPermission("schiggy.worth.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        boolean overwrite = args.length > 1 && args[1].equalsIgnoreCase("overwrite");
        service().importFile(overwrite).thenAccept(count -> reply(sender, () ->
                msg().send(sender, "imported", "count", String.valueOf(count))));
    }

    // ------------------------------------------------------------------ Tab-Completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            out.add("list");
            if (sender.hasPermission("schiggy.worth.admin")) {
                out.addAll(List.of("set", "remove", "import"));
            }
        } else if (args.length == 2 && sender.hasPermission("schiggy.worth.admin")) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("set") || sub.equals("remove")) {
                for (var entry : service().sorted()) {
                    out.add(entry.getKey().name().toLowerCase(Locale.ROOT));
                }
            } else if (sub.equals("import")) {
                out.add("overwrite");
            }
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out.size() > 50 ? out.subList(0, 50) : out;
    }
}
