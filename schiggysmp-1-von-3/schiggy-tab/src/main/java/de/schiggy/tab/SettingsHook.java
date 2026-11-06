package de.schiggy.tab;

import de.schiggy.settings.SchiggySettings;
import de.schiggy.settings.Setting;
import org.bukkit.entity.Player;

/** Nur aufrufen, wenn SchiggySettings aktiv ist (sonst NoClassDefFoundError). */
final class SettingsHook {

    private SettingsHook() {
    }

    static boolean scoreboardEnabled(Player player) {
        SchiggySettings settings = SchiggySettings.getInstance();
        return settings == null || settings.service().isEnabled(player.getUniqueId(), Setting.SCOREBOARD);
    }
}
