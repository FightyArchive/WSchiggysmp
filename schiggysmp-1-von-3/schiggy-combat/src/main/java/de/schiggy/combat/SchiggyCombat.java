package de.schiggy.combat;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SchiggyCombat extends JavaPlugin {

    private static SchiggyCombat instance;

    private MessageBundle messages;
    private CombatService service;

    @Override
    public void onEnable() {
        instance = this;
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggyCombat wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new CombatService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyCombat wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        PluginCommand command = getCommand("combat");
        if (command != null) {
            CombatCommand executor = new CombatCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("SchiggyCombat aktiviert.");
    }

    /** Fuer andere Plugins: SchiggyCombat.getInstance().service().isTagged(uuid) */
    public static SchiggyCombat getInstance() {
        return instance;
    }

    public CombatService service() {
        return service;
    }

    public MessageBundle messages() {
        return messages;
    }
}
