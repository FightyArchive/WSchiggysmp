package de.schiggy.tab;

import de.schiggy.stats.SchiggyStats;
import de.schiggy.stats.StatsService.Snapshot;
import org.bukkit.entity.Player;

/** Nur aufrufen, wenn SchiggyStats aktiv ist (sonst NoClassDefFoundError). */
final class StatsHook {

    private StatsHook() {
    }

    static Snapshot snapshot(Player player) {
        return SchiggyStats.getInstance().service().cached(player.getUniqueId()).orElse(new Snapshot(0, 0, 0));
    }
}
