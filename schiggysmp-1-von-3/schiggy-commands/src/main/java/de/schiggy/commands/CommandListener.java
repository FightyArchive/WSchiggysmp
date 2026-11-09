package de.schiggy.commands;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

/** Blockiert gesperrte Befehle und entfernt sie aus der Tab-Vervollstaendigung. */
final class CommandListener implements Listener {

    private final SchiggyCommands plugin;

    CommandListener(SchiggyCommands plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message.length() < 2) {
            return;
        }
        String label = message.substring(1).trim().split("\\s+")[0];
        if (!plugin.allowed(event.getPlayer(), label)) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandList(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        event.getCommands().removeIf(name -> !plugin.allowed(player, name));
    }
}
