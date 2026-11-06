package de.schiggy.settings;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SettingsCommand implements CommandExecutor {

    private final SchiggySettings plugin;

    public SettingsCommand(SchiggySettings plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.settings.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        new SettingsGui(plugin).open(player);
        return true;
    }
}
