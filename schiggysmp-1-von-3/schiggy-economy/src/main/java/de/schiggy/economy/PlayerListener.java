package de.schiggy.economy;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class PlayerListener implements Listener {

    private final SchiggyEconomy plugin;

    public PlayerListener(SchiggyEconomy plugin) {
        this.plugin = plugin;
    }

    /** Laeuft async: hier darf blockiert werden. Der Kontostand ist geladen, bevor der Spieler den Server betritt. */
    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            plugin.economy().loadPlayer(event.getUniqueId(), event.getName()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Konto von " + event.getName() + " konnte nicht geladen werden", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.messages().component("load-failed"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.economy().unload(event.getPlayer().getUniqueId());
    }
}
