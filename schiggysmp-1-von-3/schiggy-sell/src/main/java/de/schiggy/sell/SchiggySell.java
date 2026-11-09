package de.schiggy.sell;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.worth.SchiggyWorth;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SchiggySell extends JavaPlugin {

    private MessageBundle messages;
    private SellService service;
    private SellGui gui;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null
                || SchiggyEconomy.getInstance() == null || SchiggyWorth.getInstance() == null) {
            getLogger().severe("SchiggyCore, SchiggyEconomy oder SchiggyWorth ist nicht aktiv - SchiggySell wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new SellService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggySell wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        gui = new SellGui(this);

        getServer().getPluginManager().registerEvents(new SellListener(this), this);

        SellCommand executor = new SellCommand(this);
        for (String name : java.util.List.of("sell", "sellmulti", "sellhistory")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
            }
        }

        // Falls das Plugin bei laufendem Server geladen wird: Spielerdaten nachladen
        for (Player player : Bukkit.getOnlinePlayers()) {
            service.loadPlayer(player.getUniqueId());
        }
        getLogger().info("SchiggySell aktiviert.");
    }

    public SellService service() {
        return service;
    }

    SellGui gui() {
        return gui;
    }

    public MessageBundle messages() {
        return messages;
    }
}
