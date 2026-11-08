package de.schiggy.crates;

import de.schiggy.crates.CrateService.Crate;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Optional;

public final class CrateListener implements Listener {

    private final SchiggyCrates plugin;

    public CrateListener(SchiggyCrates plugin) {
        this.plugin = plugin;
    }

    /** Linksklick = Vorschau, Rechtsklick = Crate oeffnen. */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        Action action = event.getAction();
        if (block == null || (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK)) {
            return;
        }
        Optional<Crate> crate = plugin.service().crateAt(block.getLocation());
        if (crate.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        if (!player.hasPermission("schiggy.crates.use")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        if (action == Action.LEFT_CLICK_BLOCK) {
            plugin.gui().preview(player, crate.get());
        } else {
            plugin.gui().open(player, crate.get());
        }
    }

    /** Crate-Bloecke koennen nur von Admins abgebaut werden. */
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (plugin.service().isCrateBlock(event.getBlock().getLocation())
                && !event.getPlayer().hasPermission("schiggy.crates.admin")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.service().processing().remove(event.getPlayer().getUniqueId());
    }
}
