package de.schiggy.core.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Teleport mit Wartezeit: bricht ab, wenn sich der Spieler bewegt. Funktioniert auf Paper und Folia.
 * start() muss auf dem Thread des Spielers aufgerufen werden. Die Ergebnis-Callbacks laufen ebenfalls
 * auf dem Thread des Spielers.
 */
public final class WarmupTeleport {

    public enum Result { SUCCESS, MOVED, UNSAFE, FAILED, ALREADY_PENDING }

    private static final Map<UUID, ScheduledTask> ACTIVE = new ConcurrentHashMap<>();
    /** Quadrat der erlaubten Bewegung in Bloecken waehrend der Wartezeit. */
    private static final double MOVE_TOLERANCE_SQ = 0.3;

    private WarmupTeleport() {
    }

    public static boolean isPending(UUID id) {
        return ACTIVE.containsKey(id);
    }

    public static void start(Plugin plugin, Player player, Location dest, int seconds, boolean checkSafety,
                             boolean allowWater, IntConsumer countdown, Consumer<Result> result) {
        UUID id = player.getUniqueId();
        if (ACTIVE.containsKey(id)) {
            result.accept(Result.ALREADY_PENDING);
            return;
        }
        if (seconds <= 0) {
            finish(plugin, player, dest, checkSafety, allowWater, result);
            return;
        }
        Location origin = player.getLocation();
        int[] remaining = {seconds};
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!player.isOnline()) {
                ACTIVE.remove(id);
                scheduled.cancel();
                return;
            }
            Location now = player.getLocation();
            if (now.getWorld() != origin.getWorld() || now.distanceSquared(origin) > MOVE_TOLERANCE_SQ) {
                ACTIVE.remove(id);
                scheduled.cancel();
                result.accept(Result.MOVED);
                return;
            }
            remaining[0]--;
            if (remaining[0] <= 0) {
                ACTIVE.remove(id);
                scheduled.cancel();
                finish(plugin, player, dest, checkSafety, allowWater, result);
            } else {
                countdown.accept(remaining[0]);
            }
        }, () -> ACTIVE.remove(id), 20L, 20L);
        if (task != null) {
            ACTIVE.put(id, task);
        }
    }

    private static void finish(Plugin plugin, Player player, Location dest, boolean checkSafety,
                               boolean allowWater, Consumer<Result> result) {
        if (!checkSafety) {
            doTeleport(plugin, player, dest, result);
            return;
        }
        SafeLocation.isSafe(plugin, dest, allowWater).thenAccept(safe -> {
            if (!safe) {
                SchiggyScheduler.entity(plugin, player, () -> result.accept(Result.UNSAFE));
                return;
            }
            doTeleport(plugin, player, dest, result);
        });
    }

    private static void doTeleport(Plugin plugin, Player player, Location dest, Consumer<Result> result) {
        player.teleportAsync(dest)
                .thenAccept(ok -> SchiggyScheduler.entity(plugin, player,
                        () -> result.accept(ok ? Result.SUCCESS : Result.FAILED)))
                .exceptionally(ex -> {
                    SchiggyScheduler.entity(plugin, player, () -> result.accept(Result.FAILED));
                    return null;
                });
    }
}
