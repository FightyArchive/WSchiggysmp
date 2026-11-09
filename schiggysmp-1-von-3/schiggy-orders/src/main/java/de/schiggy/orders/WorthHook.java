package de.schiggy.orders;

import de.schiggy.worth.SchiggyWorth;
import org.bukkit.Material;

/** Nur aufrufen, wenn SchiggyWorth aktiv ist (sonst NoClassDefFoundError). */
final class WorthHook {

    private WorthHook() {
    }

    /** Verkaufswert pro Stueck in Cent. */
    static long price(Material material) {
        return SchiggyWorth.getInstance().service().getPrice(material);
    }
}
