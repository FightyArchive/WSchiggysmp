package de.schiggy.shop;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SchiggyShop extends JavaPlugin {

    private MessageBundle messages;
    private ShopService service;
    private ShopGui gui;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null || SchiggyEconomy.getInstance() == null) {
            getLogger().severe("SchiggyCore oder SchiggyEconomy ist nicht aktiv - SchiggyShop wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new ShopService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyShop wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        gui = new ShopGui(this);

        getServer().getPluginManager().registerEvents(new ShopListener(this), this);

        PluginCommand command = getCommand("shop");
        if (command != null) {
            ShopCommand executor = new ShopCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("SchiggyShop aktiviert (" + service.catalog().categories().size() + " Kategorien).");
    }

    public ShopService service() {
        return service;
    }

    ShopGui gui() {
        return gui;
    }

    public MessageBundle messages() {
        return messages;
    }
}
