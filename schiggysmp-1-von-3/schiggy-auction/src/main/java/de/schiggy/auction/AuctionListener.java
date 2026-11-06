package de.schiggy.auction;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class AuctionListener implements Listener {

    private final SchiggyAuction plugin;

    public AuctionListener(SchiggyAuction plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.gui().forget(event.getPlayer().getUniqueId());
        plugin.service().processing().remove(event.getPlayer().getUniqueId());
    }
}
