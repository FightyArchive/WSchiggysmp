package de.schiggy.worth;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

public final class SchiggyWorth extends JavaPlugin {

    private static SchiggyWorth instance;

    private MessageBundle messages;
    private WorthService service;

    @Override
    public void onEnable() {
        instance = this;

        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null || SchiggyEconomy.getInstance() == null) {
            getLogger().severe("SchiggyCore oder SchiggyEconomy ist nicht aktiv - SchiggyWorth wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        if (!new File(getDataFolder(), "prices.yml").exists()) {
            saveResource("prices.yml", false);
        }
        messages = core.messages().bundle(this);
        service = new WorthService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Setup fehlgeschlagen - SchiggyWorth wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand command = getCommand("worth");
        if (command != null) {
            WorthCommand executor = new WorthCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("SchiggyWorth aktiviert (" + service.size() + " Preise).");
    }

    public static SchiggyWorth getInstance() {
        return instance;
    }

    /** Zentrale Preisquelle fuer andere Plugins (SchiggySell, SchiggyTools, ...). */
    public WorthService service() {
        return service;
    }

    public MessageBundle messages() {
        return messages;
    }
}
