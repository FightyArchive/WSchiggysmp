package de.schiggy.core.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Basis fuer alle Schiggy-GUIs. Klicks im oberen Inventar sind immer gesperrt,
 * pro Slot kann ein Klick-Handler gesetzt werden.
 * open() muss auf dem Thread des Spielers laufen (bei Folia: SchiggyScheduler.entity).
 */
public class SchiggyGui implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    private Consumer<InventoryCloseEvent> closeHandler;

    public SchiggyGui(Component title, int rows) {
        int size = Math.max(1, Math.min(6, rows)) * 9;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public SchiggyGui set(int slot, ItemStack item) {
        inventory.setItem(slot, item);
        handlers.remove(slot);
        return this;
    }

    public SchiggyGui set(int slot, ItemStack item, Consumer<InventoryClickEvent> onClick) {
        inventory.setItem(slot, item);
        handlers.put(slot, onClick);
        return this;
    }

    public SchiggyGui fill(ItemStack item) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, item);
            }
        }
        return this;
    }

    public SchiggyGui onClose(Consumer<InventoryCloseEvent> handler) {
        this.closeHandler = handler;
        return this;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) {
            return;
        }
        Consumer<InventoryClickEvent> handler = handlers.get(event.getSlot());
        if (handler != null) {
            handler.accept(event);
        }
    }

    void handleClose(InventoryCloseEvent event) {
        if (closeHandler != null) {
            closeHandler.accept(event);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
