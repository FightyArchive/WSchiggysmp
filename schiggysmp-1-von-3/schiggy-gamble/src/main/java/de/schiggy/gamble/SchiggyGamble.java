package de.schiggy.gamble;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SchiggyGamble extends JavaPlugin implements Listener {

    private MessageBundle messages;
    private InvestService service;
    private InvestGui gui;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null || SchiggyEconomy.getInstance() == null) {
            getLogger().severe("SchiggyCore oder SchiggyEconomy ist nicht aktiv - SchiggyGamble wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new InvestService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyGamble wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        gui = new InvestGui(this);

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("invest");
        if (command != null) {
            InvestCommand executor = new InvestCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("SchiggyGamble aktiviert.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.processing().remove(event.getPlayer().getUniqueId());
    }

    public InvestService service() {
        return service;
    }

    InvestGui gui() {
        return gui;
    }

    public MessageBundle messages() {
        return messages;
    }
}
