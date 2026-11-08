package de.schiggy.settings;

import org.bukkit.Material;

/**
 * Alle persoenlichen Schalter. Die Texte stehen in der messages.yml (setting-<key>-name / -desc).
 * Andere Plugins fragen sie ueber SettingsService.isEnabled ab.
 */
public enum Setting {
    TPA("tpa", true, Material.ENDER_PEARL),
    MESSAGES("messages", true, Material.PAPER),
    CHAT("chat", true, Material.WRITABLE_BOOK),
    MOB_SPAWNING("mob_spawning", true, Material.ZOMBIE_HEAD),
    SCOREBOARD("scoreboard", true, Material.OAK_SIGN),
    TRADE("trade", true, Material.EMERALD),
    PAY_CONFIRM("pay_confirm", true, Material.GOLD_NUGGET),
    AH_NOTIFICATIONS("ah_notifications", true, Material.GOLD_INGOT),
    ORDER_NOTIFICATIONS("order_notifications", true, Material.HOPPER),
    NOTIFICATIONS("notifications", true, Material.BELL),
    SOUNDS("sounds", true, Material.NOTE_BLOCK),
    PARTICLES("particles", true, Material.BLAZE_POWDER);

    private final String key;
    private final boolean defaultValue;
    private final Material icon;

    Setting(String key, boolean defaultValue, Material icon) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.icon = icon;
    }

    public String key() {
        return key;
    }

    public boolean defaultValue() {
        return defaultValue;
    }

    public Material icon() {
        return icon;
    }

    /** Gibt null zurueck, wenn der Schluessel unbekannt ist. */
    public static Setting byKey(String key) {
        for (Setting setting : values()) {
            if (setting.key.equals(key)) {
                return setting;
            }
        }
        return null;
    }
}
