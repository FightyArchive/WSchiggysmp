package de.schiggy.core.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Prueft, ob ein Zielort sicher ist (Platz fuer den Spieler, fester Boden, keine Lava/Feuer/Kaktus).
 * Folia-sicher: der Chunk wird async geladen, die Pruefung laeuft auf dem Region-Thread des Ortes.
 */
public final class SafeLocation {

    private static final Set<Material> HAZARDS = EnumSet.of(
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.CACTUS,
            Material.FIRE, Material.SOUL_FIRE, Material.LAVA, Material.POWDER_SNOW,
            Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.COBWEB);

    private SafeLocation() {
    }

    /** Laedt den Chunk und prueft den Ort auf dem richtigen Thread. */
    public static CompletableFuture<Boolean> isSafe(Plugin plugin, Location loc, boolean allowWater) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        World world = loc.getWorld();
        if (world == null) {
            result.complete(false);
            return result;
        }
        world.getChunkAtAsync(loc).thenAccept(chunk ->
                Bukkit.getRegionScheduler().execute(plugin, loc, () -> {
                    try {
                        result.complete(check(loc, allowWater));
                    } catch (Throwable t) {
                        result.complete(false);
                    }
                })
        ).exceptionally(ex -> {
            result.complete(false);
            return null;
        });
        return result;
    }

    /** Direkte Pruefung. Muss auf dem Region-Thread des Ortes laufen (Chunk muss geladen sein). */
    public static boolean check(Location loc, boolean allowWater) {
        World world = loc.getWorld();
        if (world == null || loc.getY() < world.getMinHeight() + 1 || loc.getY() > world.getMaxHeight() - 2) {
            return false;
        }
        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        if (feet.isLiquid() || head.isLiquid()) {
            return false;
        }
        if (HAZARDS.contains(feet.getType()) || HAZARDS.contains(head.getType())) {
            return false;
        }
        for (int i = 1; i <= 4; i++) {
            Block below = feet.getRelative(0, -i, 0);
            Material type = below.getType();
            if (below.isLiquid()) {
                return allowWater && type == Material.WATER;
            }
            if (!below.isPassable()) {
                return !HAZARDS.contains(type);
            }
            if (HAZARDS.contains(type)) {
                return false;
            }
        }
        return false;
    }
}
