
package de.schiggy.crates;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SchiggyCrates extends JavaPlugin {

    private static SchiggyCrates instance;

    private MessageBundle messages;
    private CrateService service;
    private CrateGui gui;

    @Override
    public void onEnable() {
        instance = this;

        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggyCrates wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            instance = null;
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new CrateService(this, core.database());

        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyCrates wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            instance = null;
            return;
        }

        gui = new CrateGui(this);

        getServer().getPluginManager().registerEvents(new CrateListener(this), this);

        PluginCommand command = getCommand("crate");
        if (command != null) {
            CrateCommand executor = new CrateCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("SchiggyCrates aktiviert (" + service.crates().size() + " Crates).");
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    public static SchiggyCrates getInstance() {
        return instance;
    }

    public CrateService service() {
        return service;
    }

    CrateGui gui() {
        return gui;
    }

    public MessageBundle messages() {
        return messages;
    }
}
