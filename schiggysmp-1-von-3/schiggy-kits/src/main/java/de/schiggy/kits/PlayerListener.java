package de.schiggy.kits;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class PlayerListener implements Listener {

    private final SchiggyKits plugin;

    public PlayerListener(SchiggyKits plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            plugin.service().loadPlayer(event.getUniqueId()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Kit-Daten von " + event.getName() + " konnten nicht geladen werden", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.messages().component("load-failed"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.service().unload(event.getPlayer().getUniqueId());
    }
}
