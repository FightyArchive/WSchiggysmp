
package de.schiggy.combat;

import de.schiggy.stats.SchiggyStats;
import de.schiggy.stats.StatsService.Snapshot;
import org.bukkit.entity.Player;

/**
 * Verbindung zwischen SchiggyCombat und SchiggyStats.
 * Stats werden aus dem Cache gelesen.
 */
final class StatsHook {

    private StatsHook() {
    }

    /**
     * Liest die gecachten Statistiken eines Spielers.
     */
    static Snapshot snapshot(Player player) {
        SchiggyStats stats = SchiggyStats.getInstance();

        if (stats == null || stats.service() == null) {
            return new Snapshot(0, 0, 0);
        }

        return stats.service()
                .cached(player.getUniqueId())
                .orElse(new Snapshot(0, 0, 0));
    }
}
