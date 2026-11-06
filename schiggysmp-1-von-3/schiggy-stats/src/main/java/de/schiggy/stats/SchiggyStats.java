package de.schiggy.stats;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class SchiggyStats extends JavaPlugin implements Listener {

    private static SchiggyStats instance;

    private MessageBundle messages;
    private StatsService service;

    @Override
    public void onEnable() {
        instance = this;
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null || SchiggyEconomy.getInstance() == null) {
            getLogger().severe("SchiggyCore oder SchiggyEconomy ist nicht aktiv - SchiggyStats wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new StatsService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyStats wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        StatsCommand executor = new StatsCommand(this);
        for (String name : List.of("stats", "leaderboard")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            service.loadPlayer(player.getUniqueId(), player.getName());
        }
        getLogger().info("SchiggyStats aktiviert.");
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.flushAll().join();
        }
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            service.loadPlayer(event.getUniqueId(), event.getName()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Statistiken sind nicht kritisch: der Spieler darf trotzdem beitreten
            getLogger().log(Level.WARNING, "Statistiken von " + event.getName() + " konnten nicht geladen werden", e);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.unload(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        service.addDeath(victim.getUniqueId());
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            service.addKill(killer.getUniqueId());
        }
    }

    public static SchiggyStats getInstance() {
        return instance;
    }

    public StatsService service() {
        return service;
    }

    public MessageBundle messages() {
        return messages;
    }
}
