package de.schiggy.teleport;

import de.schiggy.core.util.SafeLocation;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/** Sucht zufaellige sichere Orte in Oberwelt, Nether und End, ohne den Server zu blockieren (Folia-sicher). */
public final class RtpService {

    /** Die Ziele im /rtp-Menue. */
    public static final List<String> IDS = List.of("overworld", "nether", "end");

    public record Target(String id, World world, int minRadius, int maxRadius, int attempts) {
    }

    private final SchiggyTeleport plugin;

    public RtpService(SchiggyTeleport plugin) {
        this.plugin = plugin;
    }

    /** Leer, wenn das Ziel ausgeschaltet ist oder die Welt nicht geladen ist. */
    public Optional<Target> target(String id) {
        if (!IDS.contains(id)) {
            return Optional.empty();
        }
        FileConfiguration cfg = plugin.getConfig();
        String path = "rtp.worlds." + id;
        if (!cfg.getBoolean(path + ".enabled", true)) {
            return Optional.empty();
        }
        World world = Bukkit.getWorld(cfg.getString(path + ".world", ""));
        if (world == null) {
            return Optional.empty();
        }
        int min = Math.max(0, cfg.getInt(path + ".min-radius", 250));
        int max = Math.max(min + 1, cfg.getInt(path + ".max-radius", 5000));
        int attempts = Math.max(1, cfg.getInt(path + ".attempts", 15));
        return Optional.of(new Target(id, world, min, max, attempts));
    }

    public CompletableFuture<Optional<Location>> find(Target target) {
        return find(target, target.attempts());
    }

    private CompletableFuture<Optional<Location>> find(Target target, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        FileConfiguration cfg = plugin.getConfig();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double min = target.minRadius();
        double max = target.maxRadius();
        double radius = Math.sqrt(random.nextDouble() * (max * max - min * min) + min * min);
        double angle = random.nextDouble() * Math.PI * 2;
        int x = (int) Math.floor(cfg.getDouble("rtp.center-x", 0) + radius * Math.cos(angle));
        int z = (int) Math.floor(cfg.getDouble("rtp.center-z", 0) + radius * Math.sin(angle));
        World world = target.world();

        CompletableFuture<Optional<Location>> candidate = new CompletableFuture<>();
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            Location probe = new Location(world, x + 0.5, 64, z + 0.5);
            Bukkit.getRegionScheduler().execute(plugin, probe, () -> {
                try {
                    candidate.complete(Optional.ofNullable(search(world, x, z)));
                } catch (Throwable t) {
                    candidate.complete(Optional.empty());
                }
            });
        }).exceptionally(ex -> {
            candidate.complete(Optional.empty());
            return null;
        });

        return candidate.thenCompose(result -> result.isPresent()
                ? CompletableFuture.completedFuture(result)
                : find(target, attemptsLeft - 1));
    }

    /** Laeuft auf dem Region-Thread des Ortes. Gibt null zurueck, wenn der Ort nicht sicher ist. */
    private Location search(World world, int x, int z) {
        if (!world.getWorldBorder().isInside(new Location(world, x + 0.5, 64, z + 0.5))) {
            return null;
        }
        switch (world.getEnvironment()) {
            case NETHER -> {
                // unter der Nether-Decke von oben nach unten nach einem freien Platz mit festem Boden suchen
                for (int y = 120; y > 32; y--) {
                    Location loc = new Location(world, x + 0.5, y, z + 0.5);
                    if (SafeLocation.check(loc, false)) {
                        return loc;
                    }
                }
                return null;
            }
            default -> {
                // Oberwelt und End: hoechster Block ohne Blaetter, ein Block darueber stehen
                int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
                return SafeLocation.check(loc, false) ? loc : null;
            }
        }
    }
}
