package de.schiggy.settings;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Mob-Spawning" aus: In der Naehe des Spielers spawnen keine natuerlichen Mobs.
 * Folia-sicher: Die Position jedes betroffenen Spielers wird auf seinem eigenen Region-Thread aktualisiert,
 * das Spawn-Event liest nur diese gespeicherten Positionen.
 */
public final class MobSpawnListener implements Listener {

    private record Spot(UUID world, double x, double y, double z) {
    }

    private final SchiggySettings plugin;
    private final Map<UUID, Spot> spots = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();

    public MobSpawnListener(SchiggySettings plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        track(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        untrack(event.getPlayer().getUniqueId());
    }

    /** Muss auf dem Thread des Spielers laufen. */
    public void track(Player player) {
        UUID id = player.getUniqueId();
        if (tasks.containsKey(id)) {
            return;
        }
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!player.isOnline()) {
                untrack(id);
                return;
            }
            if (plugin.service().isEnabled(id, Setting.MOB_SPAWNING)) {
                spots.remove(id);
                return;
            }
            Location location = player.getLocation();
            spots.put(id, new Spot(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ()));
        }, () -> untrack(id), 20L, 20L);
        if (task != null) {
            tasks.put(id, task);
        }
    }

    private void untrack(UUID id) {
        spots.remove(id);
        ScheduledTask task = tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (spots.isEmpty() || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        Location location = event.getLocation();
        UUID world = location.getWorld().getUID();
        double radius = plugin.getConfig().getDouble("mob-spawning.radius", 64);
        double radiusSquared = radius * radius;
        for (Spot spot : spots.values()) {
            if (!spot.world().equals(world)) {
                continue;
            }
            double dx = spot.x() - location.getX();
            double dy = spot.y() - location.getY();
            double dz = spot.z() - location.getZ();
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
