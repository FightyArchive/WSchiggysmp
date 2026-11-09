package de.schiggy.extras;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.worth.SchiggyWorth;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Sammel-Plugin fuer die Zusatz-Systeme: Spawner, Amethyst-Werkzeuge (spaeter auch Waffen, Duell, RTPQ).
 * Alles in wenigen Dateien: Spawners.java, Tools.java und diese Hauptklasse.
 */
public final class SchiggyExtras extends JavaPlugin {

    /**
     * Solange dieses Flag in einem Thread gesetzt ist, ignorieren unsere eigenen Listener Block-Events.
     * Wird gebraucht, wenn wir selbst BlockBreakEvents ausloesen (Schutzregionen pruefen).
     */
    static final ThreadLocal<Boolean> INTERNAL = ThreadLocal.withInitial(() -> false);

    private MessageBundle messages;
    private Spawners spawners;
    private Tools tools;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null || SchiggyEconomy.getInstance() == null
                || SchiggyWorth.getInstance() == null) {
            getLogger().severe("SchiggyCore, SchiggyEconomy oder SchiggyWorth ist nicht aktiv - SchiggyExtras wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        messages = core.messages().bundle(this);
        spawners = new Spawners(this, core.database());
        try {
            spawners.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyExtras wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        tools = new Tools(this, spawners);

        getServer().getPluginManager().registerEvents(spawners, this);
        getServer().getPluginManager().registerEvents(tools, this);
        PluginCommand spawnerCommand = getCommand("spawner");
        if (spawnerCommand != null) {
            spawnerCommand.setExecutor(spawners);
            spawnerCommand.setTabCompleter(spawners);
        }
        PluginCommand toolsCommand = getCommand("tools");
        if (toolsCommand != null) {
            toolsCommand.setExecutor(tools);
            toolsCommand.setTabCompleter(tools);
        }
        getLogger().info("SchiggyExtras aktiviert.");
    }

    @Override
    public void onDisable() {
        if (spawners != null) {
            spawners.flush().join();
        }
    }

    void reload() {
        reloadConfig();
        messages.reload();
        spawners.reloadMobs();
    }

    MessageBundle messages() {
        return messages;
    }

    Spawners spawners() {
        return spawners;
    }

    Tools tools() {
        return tools;
    }
}
