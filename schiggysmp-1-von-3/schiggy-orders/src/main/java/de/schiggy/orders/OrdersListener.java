package de.schiggy.orders;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class OrdersListener implements Listener {

    private final SchiggyOrders plugin;

    public OrdersListener(SchiggyOrders plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.gui().forget(event.getPlayer().getUniqueId());
        plugin.service().processing().remove(event.getPlayer().getUniqueId());
    }
}
