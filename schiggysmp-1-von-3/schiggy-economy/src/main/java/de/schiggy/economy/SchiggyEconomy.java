package de.schiggy.economy;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

public final class SchiggyEconomy extends JavaPlugin {

    private static SchiggyEconomy instance;

    private MessageBundle messages;
    private EconomyService economy;

    @Override
    public void onEnable() {
        instance = this;

        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggyEconomy wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        economy = new EconomyService(this, core.database());

        try {
            economy.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyEconomy wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        EconomyCommand executor = new EconomyCommand(this);
        for (String name : List.of("balance", "shards", "pay", "baltop", "eco")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        }

        // Falls das Plugin bei laufendem Server geladen wird: bereits verbundene Spieler nachladen
        for (Player player : Bukkit.getOnlinePlayers()) {
            economy.loadPlayer(player.getUniqueId(), player.getName());
        }

        economy.startBaltopRefresh();
        getLogger().info("SchiggyEconomy aktiviert.");
    }

    public static SchiggyEconomy getInstance() {
        return instance;
    }

    public EconomyService economy() {
        return economy;
    }

    public MessageBundle messages() {
        return messages;
    }
}
