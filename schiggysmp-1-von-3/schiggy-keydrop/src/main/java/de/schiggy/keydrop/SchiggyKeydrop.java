package de.schiggy.keydrop;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.crates.SchiggyCrates;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SchiggyKeydrop extends JavaPlugin implements Listener {

    private MessageBundle messages;
    private KeydropService service;
    private KeydropGui gui;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null || SchiggyCrates.getInstance() == null) {
            getLogger().severe("SchiggyCore oder SchiggyCrates ist nicht aktiv - SchiggyKeydrop wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new KeydropService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyKeydrop wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        gui = new KeydropGui(this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("keydrop");
        if (command != null) {
            KeydropCommand executor = new KeydropCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("SchiggyKeydrop aktiviert (" + service.presets().size() + " Presets).");
    }

    /** Spieler, die waehrend eines Keydrops beitreten, sehen die Bossbar. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.showTo(event.getPlayer());
    }

    public KeydropService service() {
        return service;
    }

    KeydropGui gui() {
        return gui;
    }

    public MessageBundle messages() {
        return messages;
    }
}
