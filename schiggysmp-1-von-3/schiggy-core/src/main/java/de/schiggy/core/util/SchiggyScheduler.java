package de.schiggy.core.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Scheduler-Helfer, die auf Paper UND Folia funktionieren.
 * Regel: Aktionen an einem Spieler/Entity immer ueber entity(), an einem Ort ueber region().
 */
public final class SchiggyScheduler {

    private SchiggyScheduler() {
    }

    public static void global(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
    }

    public static void entity(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, scheduled -> task.run(), null);
    }

    public static void region(Plugin plugin, Location location, Runnable task) {
        Bukkit.getRegionScheduler().run(plugin, location, scheduled -> task.run());
    }

    public static void async(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    public static void asyncRepeating(Plugin plugin, Runnable task, long delaySeconds, long periodSeconds) {
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduled -> task.run(),
                delaySeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /** Fuehrt die Aufgabe auf dem passenden Thread des Absenders aus (Spieler: sein Region-Thread, sonst global). */
    public static void forSender(Plugin plugin, CommandSender sender, Runnable task) {
        if (sender instanceof Entity entity) {
            entity(plugin, entity, task);
        } else {
            global(plugin, task);
        }
    }
}
