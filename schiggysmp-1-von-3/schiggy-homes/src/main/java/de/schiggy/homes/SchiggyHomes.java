package de.schiggy.homes;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Cooldowns;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

public final class SchiggyHomes extends JavaPlugin {

    private MessageBundle messages;
    private HomeService homes;
    private final Cooldowns cooldowns = new Cooldowns();

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggyHomes wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        homes = new HomeService(this, core.database());
        try {
            homes.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyHomes wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        HomeCommand executor = new HomeCommand(this);
        for (String name : List.of("sethome", "home", "homes", "delhome")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            homes.loadNames(player.getUniqueId());
        }

        getLogger().info("SchiggyHomes aktiviert.");
    }

    public HomeService homes() {
        return homes;
    }

    public MessageBundle messages() {
        return messages;
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }
}
