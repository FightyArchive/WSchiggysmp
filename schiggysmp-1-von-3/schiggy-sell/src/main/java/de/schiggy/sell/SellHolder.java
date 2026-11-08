package de.schiggy.sell;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Das Verkaufsmenue: Slots 0-44 sind frei, die untere Reihe enthaelt Anzeigen und Knoepfe. */
final class SellHolder implements InventoryHolder {

    static final int ITEM_SLOTS = 45;
    static final int INFO_SLOT = 45;
    static final int STATS_SLOT = 47;
    static final int SELL_SLOT = 49;
    static final int CANCEL_SLOT = 53;

    private final Inventory inventory;
    /** true: Items werden ohne Verkauf zurueckgegeben. */
    boolean cancelled;
    /** Schutz davor, dass beim Schliessen zweimal ausgezahlt wird. */
    boolean processed;

    SellHolder(Component title) {
        this.inventory = Bukkit.createInventory(this, 54, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
