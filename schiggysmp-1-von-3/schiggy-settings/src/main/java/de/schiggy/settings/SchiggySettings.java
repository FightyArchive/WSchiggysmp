package de.schiggy.settings;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SchiggySettings extends JavaPlugin {

    private static SchiggySettings instance;

    private MessageBundle messages;
    private SettingsService service;

    @Override
    public void onEnable() {
        instance = this;

        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggySettings wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new SettingsService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggySettings wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MobSpawnListener mobSpawns = new MobSpawnListener(this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new PrivateMessageListener(this), this);
        getServer().getPluginManager().registerEvents(mobSpawns, this);

        PluginCommand command = getCommand("settings");
        if (command != null) {
            command.setExecutor(new SettingsCommand(this));
        }

        // Falls das Plugin bei laufendem Server geladen wird: Spieler nachladen
        for (Player player : Bukkit.getOnlinePlayers()) {
            service.loadPlayer(player.getUniqueId());
            SchiggyScheduler.entity(this, player, () -> mobSpawns.track(player));
        }

        getLogger().info("SchiggySettings aktiviert.");
    }

    public static SchiggySettings getInstance() {
        return instance;
    }

    public SettingsService service() {
        return service;
    }

    public MessageBundle messages() {
        return messages;
    }
}
