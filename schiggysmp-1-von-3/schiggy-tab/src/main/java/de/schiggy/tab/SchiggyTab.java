package de.schiggy.tab;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchiggyTab extends JavaPlugin implements CommandExecutor {

    private TabManager manager;
    private MessageBundle messages;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggyTab wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        manager = new TabManager(this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        PluginCommand command = getCommand("schiggytab");
        if (command != null) {
            command.setExecutor(this);
        }

        // Falls das Plugin bei laufendem Server geladen wird: Spieler nachziehen
        for (Player player : Bukkit.getOnlinePlayers()) {
            SchiggyScheduler.entity(this, player, () -> manager.start(player));
        }
        getLogger().info("SchiggyTab aktiviert.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("schiggy.tab.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            messages.reload();
            manager.reload();
            messages.send(sender, "reloaded");
            return true;
        }
        messages.send(sender, "usage");
        return true;
    }

    public TabManager manager() {
        return manager;
    }

    public MessageBundle messages() {
        return messages;
    }
}
