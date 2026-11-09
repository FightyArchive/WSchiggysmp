package de.schiggy.auction;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SchiggyAuction extends JavaPlugin {

    private MessageBundle messages;
    private AuctionService service;
    private AuctionGui gui;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null || SchiggyEconomy.getInstance() == null) {
            getLogger().severe("SchiggyCore oder SchiggyEconomy ist nicht aktiv - SchiggyAuction wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new AuctionService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyAuction wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        gui = new AuctionGui(this);

        getServer().getPluginManager().registerEvents(new AuctionListener(this), this);

        PluginCommand command = getCommand("ah");
        if (command != null) {
            AuctionCommand executor = new AuctionCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("SchiggyAuction aktiviert.");
    }

    public AuctionService service() {
        return service;
    }

    AuctionGui gui() {
        return gui;
    }

    public MessageBundle messages() {
        return messages;
    }
}
