package de.schiggy.teleport;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.teleport.RtpService.Target;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/** /rtp-Menue wie auf DonutSMP: Oberwelt, Nether und End zur Auswahl. Alle Texte stehen in der messages.yml. */
final class RtpGui {

    private final SchiggyTeleport plugin;
    private final TeleportCommand command;

    RtpGui(SchiggyTeleport plugin, TeleportCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    /** Muss auf dem Thread des Spielers laufen. */
    void open(Player player) {
        SchiggyGui gui = new SchiggyGui(plugin.messages().component("gui-rtp-title"), 3);
        place(gui, player, "overworld", 11, Material.GRASS_BLOCK);
        place(gui, player, "nether", 13, Material.NETHERRACK);
        place(gui, player, "end", 15, Material.END_STONE);
        gui.open(player);
    }

    private void place(SchiggyGui gui, Player player, String id, int slot, Material icon) {
        MessageBundle msg = plugin.messages();
        Optional<Target> target = plugin.rtp().target(id);
        if (target.isEmpty()) {
            gui.set(slot, ItemBuilder.of(Material.BARRIER)
                    .name(msg.raw("gui-rtp-unavailable-name"))
                    .lore(msg.rawList("gui-rtp-unavailable-lore"))
                    .build());
            return;
        }
        Target t = target.get();
        String[] placeholders = {"min", String.valueOf(t.minRadius()), "max", String.valueOf(t.maxRadius())};
        ItemStack item = ItemBuilder.of(icon)
                .name(msg.raw("gui-rtp-" + id + "-name", placeholders))
                .lore(msg.rawList("gui-rtp-" + id + "-lore", placeholders))
                .build();
        gui.set(slot, item, event -> {
            player.closeInventory();
            command.startRtp(player, t);
        });
    }
}
