package de.schiggy.shop;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ShopListener implements Listener {

    private final SchiggyShop plugin;

    public ShopListener(SchiggyShop plugin) {
        this.plugin = plugin;
    }

    /** Ein beim Verlassen noch laufender Kauf darf den Spieler beim naechsten Beitritt nicht blockieren. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.service().clearProcessing(event.getPlayer().getUniqueId());
    }
}
