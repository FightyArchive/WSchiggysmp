package de.schiggy.sell;

import de.schiggy.core.util.SchiggyScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class SellListener implements Listener {

    private final SchiggySell plugin;

    public SellListener(SchiggySell plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ Spielerdaten

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            plugin.service().loadPlayer(event.getUniqueId()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Verkaufsdaten von " + event.getName() + " konnten nicht geladen werden", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.messages().component("load-failed"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.service().unload(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ Verkaufsmenue

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder holder) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw >= SellHolder.ITEM_SLOTS && raw < top.getSize()) {
            // untere Reihe: nie verschiebbar, nur Knoepfe
            event.setCancelled(true);
            if (raw == SellHolder.SELL_SLOT) {
                player.closeInventory();
            } else if (raw == SellHolder.CANCEL_SLOT) {
                holder.cancelled = true;
                player.closeInventory();
            }
            return;
        }
        scheduleRefresh(player, holder);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder holder) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw >= SellHolder.ITEM_SLOTS && raw < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
        scheduleRefresh(player, holder);
    }

    /** Erst nach dem Klick aktualisieren, damit der neue Inhalt schon im Menue liegt. */
    private void scheduleRefresh(Player player, SellHolder holder) {
        SchiggyScheduler.entity(plugin, player, () -> {
            if (!holder.processed) {
                plugin.gui().refresh(player, holder);
            }
        });
    }

    /** Beim Schliessen (auch per ESC) wird verkauft. Nicht verkaufbare Items bekommt der Spieler zurueck. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SellHolder holder)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (holder.processed) {
            return;
        }
        holder.processed = true;

        List<ItemStack> sellable = new ArrayList<>();
        List<ItemStack> unsellable = new ArrayList<>();
        Inventory inventory = holder.getInventory();
        for (int slot = 0; slot < SellHolder.ITEM_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            inventory.setItem(slot, null);
            if (!holder.cancelled && plugin.service().isSellable(item)) {
                sellable.add(item);
            } else {
                unsellable.add(item);
            }
        }
        if (!unsellable.isEmpty()) {
            plugin.service().giveBack(player, unsellable);
            if (!holder.cancelled) {
                plugin.messages().send(player, "returned");
            }
        }
        if (!sellable.isEmpty()) {
            plugin.service().sell(player, sellable);
        }
    }
}
