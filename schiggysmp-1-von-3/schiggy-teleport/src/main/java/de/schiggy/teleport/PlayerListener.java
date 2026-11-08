package de.schiggy.teleport;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public final class PlayerListener implements Listener {

    private final SchiggyTeleport plugin;

    public PlayerListener(SchiggyTeleport plugin) {
        this.plugin = plugin;
    }

    /** Offene Anfragen eines Spielers verfallen, wenn er den Server verlaesst. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.requests().remove(uuid);
        plugin.requests().values().removeIf(request -> request.from().equals(uuid));
    }
}
