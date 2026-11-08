package de.schiggy.core.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;

/** Inventar-Helfer. Alle Methoden muessen auf dem Thread des Spielers laufen. */
public final class Inventories {

    private Inventories() {
    }

    /** Anzahl unveraenderter Items (ohne Namen, Verzauberungen, Schaden) dieses Materials im Inventar. */
    public static int countPlain(Player player, Material material) {
        int count = 0;
        ItemStack plain = new ItemStack(material);
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material && stack.isSimilar(plain)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Entfernt bis zu amount unveraenderte Items und gibt zurueck, wie viele entfernt wurden. */
    public static int removePlain(Player player, Material material, int amount) {
        PlayerInventory inventory = player.getInventory();
        ItemStack plain = new ItemStack(material);
        int remaining = amount;
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() != material || !stack.isSimilar(plain)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            if (take == stack.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                ItemStack rest = stack.clone();
                rest.setAmount(stack.getAmount() - take);
                inventory.setItem(slot, rest);
            }
            remaining -= take;
        }
        return amount - remaining;
    }

    /** Anzahl komplett freier Slots im Inventar (ohne Ruestung und Zweithand). */
    public static int freeSlots(Player player) {
        int free = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                free++;
            }
        }
        return free;
    }

    /** Passt dieser Stapel noch ins Inventar? */
    public static boolean fits(Player player, ItemStack item) {
        int max = item.getMaxStackSize();
        int free = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                free += max;
            } else if (stack.isSimilar(item)) {
                free += Math.max(0, max - stack.getAmount());
            }
            if (free >= item.getAmount()) {
                return true;
            }
        }
        return false;
    }

    /** Gibt den Stapel, ueberzaehliges faellt vor die Fuesse des Spielers. */
    public static void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    /** Gibt amount unveraenderte Items eines Materials. */
    public static void giveMaterial(Player player, Material material, int amount) {
        int remaining = amount;
        int max = material.getMaxStackSize();
        while (remaining > 0) {
            int part = Math.min(max, remaining);
            give(player, new ItemStack(material, part));
            remaining -= part;
        }
    }
}
