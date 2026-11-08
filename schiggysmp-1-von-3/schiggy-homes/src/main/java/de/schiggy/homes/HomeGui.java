package de.schiggy.homes;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.homes.HomeService.Home;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Homes-Menue im Stil von DonutSMP: zwei Bloecke mit je 5 Betten, darunter jeweils ein Farbstoff
 * (limettengruen = Home gesetzt, grau = freier Platz). Beides ist anklickbar.
 * Linksklick = Teleport, Rechtsklick = Loeschen (mit Bestaetigung), freier Platz = Home hier setzen.
 */
final class HomeGui {

    /** Erster Bett-Slot der beiden Bloecke (Reihe 2 und Reihe 4). */
    private static final int[] BLOCK_START = {11, 29};
    private static final int MAX_SLOTS = 10;

    private final SchiggyHomes plugin;
    private final HomeCommand command;

    HomeGui(SchiggyHomes plugin, HomeCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    // ------------------------------------------------------------------ Uebersicht

    /** Muss auf dem Thread des Spielers laufen. */
    void open(Player player, List<Home> homes) {
        int limit = command.limitFor(player);
        int count = homes.size();
        String limitText = HomeCommand.limitText(limit);

        SchiggyGui gui = new SchiggyGui(msg().component("gui-home-title",
                "count", String.valueOf(count), "limit", limitText), 6);

        // Alle 10 Plaetze werden immer gezeigt: gesetzt (gruen), frei (grau) oder gesperrt (rot, hoeherer Rang noetig)
        for (int i = 0; i < MAX_SLOTS; i++) {
            int bed = BLOCK_START[i / 5] + (i % 5);
            int dye = bed + 9;
            if (i < count) {
                setHome(gui, player, homes.get(i), bed, dye);
            } else if (limit < 0 || i < limit) {
                setFree(gui, player, homes, bed, dye);
            } else {
                setLocked(gui, limitText, bed, dye);
            }
        }
        gui.open(player);
    }

    private void setHome(SchiggyGui gui, Player player, Home home, int bedSlot, int dyeSlot) {
        String[] placeholders = {
                "name", home.name(),
                "world", home.world(),
                "x", String.valueOf(Math.round(home.x())),
                "y", String.valueOf(Math.round(home.y())),
                "z", String.valueOf(Math.round(home.z()))
        };
        ItemStack bed = ItemBuilder.of(Material.LIME_BED)
                .name(msg().raw("gui-home-name", placeholders))
                .lore(msg().rawList("gui-home-lore", placeholders))
                .build();
        ItemStack dye = ItemBuilder.of(Material.LIME_DYE)
                .name(msg().raw("gui-home-dye-set-name", placeholders))
                .lore(msg().rawList("gui-home-lore", placeholders))
                .build();
        var handler = (java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent>) event -> {
            if (event.getClick().isRightClick()) {
                if (plugin.getConfig().getBoolean("confirm-delete", true)) {
                    openDeleteConfirm(player, home);
                } else {
                    deleteAndReopen(player, home);
                }
            } else {
                player.closeInventory();
                command.teleportTo(player, home);
            }
        };
        gui.set(bedSlot, bed, handler);
        gui.set(dyeSlot, dye, handler);
    }

    private void setFree(SchiggyGui gui, Player player, List<Home> homes, int bedSlot, int dyeSlot) {
        ItemStack bed = ItemBuilder.of(Material.GRAY_BED)
                .name(msg().raw("gui-home-empty-name"))
                .lore(msg().rawList("gui-home-empty-lore"))
                .build();
        ItemStack dye = ItemBuilder.of(Material.GRAY_DYE)
                .name(msg().raw("gui-home-dye-free-name"))
                .lore(msg().rawList("gui-home-empty-lore"))
                .build();
        var handler = (java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent>) event ->
                command.setHome(player, nextFreeName(homes), true);
        gui.set(bedSlot, bed, handler);
        gui.set(dyeSlot, dye, handler);
    }

    private void setLocked(SchiggyGui gui, String limitText, int bedSlot, int dyeSlot) {
        ItemStack bed = ItemBuilder.of(Material.RED_BED)
                .name(msg().raw("gui-home-locked-name"))
                .lore(msg().rawList("gui-home-locked-lore", "limit", limitText))
                .build();
        ItemStack dye = ItemBuilder.of(Material.RED_DYE)
                .name(msg().raw("gui-home-dye-locked-name"))
                .lore(msg().rawList("gui-home-locked-lore", "limit", limitText))
                .build();
        gui.set(bedSlot, bed);
        gui.set(dyeSlot, dye);
    }

    /** home, home2, home3, ... : der erste Name, den es noch nicht gibt. */
    private static String nextFreeName(List<Home> homes) {
        Set<String> used = new HashSet<>();
        for (Home home : homes) {
            used.add(home.name().toLowerCase(Locale.ROOT));
        }
        if (!used.contains("home")) {
            return "home";
        }
        int number = 2;
        while (used.contains("home" + number)) {
            number++;
        }
        return "home" + number;
    }

    // ------------------------------------------------------------------ Loeschen

    private void openDeleteConfirm(Player player, Home home) {
        SchiggyGui gui = new SchiggyGui(msg().component("gui-delete-title"), 3);
        gui.set(11, ItemBuilder.of(Material.LIME_CONCRETE)
                .name(msg().raw("gui-delete-confirm-name"))
                .lore(msg().rawList("gui-delete-confirm-lore", "name", home.name()))
                .build(), event -> deleteAndReopen(player, home));
        gui.set(13, ItemBuilder.of(Material.LIME_BED)
                .name(msg().raw("gui-delete-home-name", "name", home.name()))
                .build());
        gui.set(15, ItemBuilder.of(Material.RED_CONCRETE)
                .name(msg().raw("gui-delete-cancel-name"))
                .lore(msg().rawList("gui-delete-cancel-lore"))
                .build(), event -> command.openGui(player));
        gui.open(player);
    }

    private void deleteAndReopen(Player player, Home home) {
        UUID uuid = player.getUniqueId();
        plugin.homes().delete(uuid, home.name()).thenAccept(deleted -> command.reply(player, () -> {
            if (deleted) {
                msg().send(player, "home-deleted", "name", home.name());
            } else {
                msg().send(player, "home-not-found", "name", home.name());
            }
            command.openGui(player);
        }));
    }
}
