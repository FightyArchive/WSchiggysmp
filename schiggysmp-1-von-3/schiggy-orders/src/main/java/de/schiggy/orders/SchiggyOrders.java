package de.schiggy.orders;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SchiggyOrders extends JavaPlugin {

    private MessageBundle messages;
    private OrdersService service;
    private OrdersGui gui;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null || SchiggyEconomy.getInstance() == null) {
            getLogger().severe("SchiggyCore oder SchiggyEconomy ist nicht aktiv - SchiggyOrders wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new OrdersService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyOrders wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        gui = new OrdersGui(this);

        getServer().getPluginManager().registerEvents(new OrdersListener(this), this);

        PluginCommand command = getCommand("order");
        if (command != null) {
            OrdersCommand executor = new OrdersCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("SchiggyOrders aktiviert.");
    }

    public OrdersService service() {
        return service;
    }

    OrdersGui gui() {
        return gui;
    }

    public MessageBundle messages() {
        return messages;
    }
}
