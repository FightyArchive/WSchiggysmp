package de.schiggy.gamble;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.TimeUnit;
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
        InvestCommand executor = new InvestCommand(this);
        for (String name : List.of("invest", "claiminvest", "deleteinvest", "autocollecttoggle", "investadmin")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            service.loadPlayer(player.getUniqueId());
        }
        getLogger().info("SchiggyGamble aktiviert (Zinssatz " + service.rate() + ", Intervall " + service.intervalTicks() + " Ticks).");
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.saveAll();
        }
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            service.loadPlayer(event.getUniqueId()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Investment von " + event.getName() + " konnte nicht geladen werden", e);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.unload(event.getPlayer().getUniqueId());
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
