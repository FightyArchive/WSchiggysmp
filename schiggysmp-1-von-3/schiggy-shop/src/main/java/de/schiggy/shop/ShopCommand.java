package de.schiggy.shop;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.shop.ShopCatalog.ShopCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ShopCommand implements TabExecutor {

    private final SchiggyShop plugin;

    public ShopCommand(SchiggyShop plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("schiggy.shop.admin")) {
                msg().send(sender, "no-permission");
                return true;
            }
            plugin.reloadConfig();
            msg().reload();
            plugin.service().reload();
            msg().send(sender, "reloaded", "count", String.valueOf(plugin.service().catalog().categories().size()));
            return true;
        }
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.shop.use")) {
            msg().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.gui().openMain(player);
            return true;
        }
        Optional<ShopCategory> category = plugin.service().catalog().category(args[0]);
        if (category.isEmpty()) {
            msg().send(player, "unknown-category");
            return true;
        }
        plugin.gui().openCategory(player, category.get(), 0);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (ShopCategory category : plugin.service().catalog().categories()) {
            if (category.id().toLowerCase(Locale.ROOT).startsWith(typed)) {
                out.add(category.id());
            }
        }
        if (sender.hasPermission("schiggy.shop.admin") && "reload".startsWith(typed)) {
            out.add("reload");
        }
        return out;
    }
}
