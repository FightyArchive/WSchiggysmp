package de.schiggy.auction;

import de.schiggy.settings.SchiggySettings;
import de.schiggy.settings.Setting;

import java.util.UUID;

/** Nur aufrufen, wenn SchiggySettings aktiv ist (sonst NoClassDefFoundError). */
final class SettingsHook {

    private SettingsHook() {
    }

    static boolean enabled(UUID uuid) {
        SchiggySettings settings = SchiggySettings.getInstance();
        return settings == null || settings.service().isEnabled(uuid, Setting.AH_NOTIFICATIONS);
    }
}
