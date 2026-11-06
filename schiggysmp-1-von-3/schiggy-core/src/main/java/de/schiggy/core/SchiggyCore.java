package de.schiggy.core;

import de.schiggy.core.database.Database;
import de.schiggy.core.gui.GuiListener;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.message.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

/**
 * Zentrales Plugin: stellt Datenbank, Nachrichten-System, GUI-System und Scheduler-Helfer bereit.
 * Alle anderen Schiggy-Plugins haengen von diesem Plugin ab.
 */
public final class SchiggyCore extends JavaPlugin implements CommandExecutor {

    private static SchiggyCore instance;

    private Database database;
    private MessageService messages;
    private MessageBundle bundle;

    @Override
    public void onEnable() {
        instance = this;
        messages = new MessageService();
        bundle = messages.bundle(this);

        try {
            database = new Database(this, new File(getDataFolder(), "schiggy.db"));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank konnte nicht geoeffnet werden - SchiggyCore wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(new de.schiggy.core.util.ChatPrompt.ChatListener(), this);

        PluginCommand command = getCommand("schiggycore");
        if (command != null) {
            command.setExecutor(this);
        }

        getLogger().info("SchiggyCore aktiviert (Datenbank: SQLite).");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
        instance = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("schiggy.core.admin")) {
            bundle.send(sender, "no-permission");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            int count = messages.reloadAll();
            bundle.send(sender, "reloaded", "count", String.valueOf(count));
            return true;
        }
        if (args.length > 0) {
            bundle.send(sender, "usage");
            return true;
        }
        bundle.send(sender, "info-header", "version", getPluginMeta().getVersion());
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getName().startsWith("Schiggy") && plugin != this) {
                String key = plugin.isEnabled() ? "info-plugin-active" : "info-plugin-inactive";
                sender.sendMessage(bundle.component(key,
                        "name", plugin.getName(),
                        "version", plugin.getPluginMeta().getVersion()));
            }
        }
        return true;
    }

    public static SchiggyCore getInstance() {
        return instance;
    }

    public Database database() {
        return database;
    }

    public MessageService messages() {
        return messages;
    }
}
