package de.schiggy.settings;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** "Private Nachrichten" aus: /msg an diesen Spieler wird blockiert. */
public final class PrivateMessageListener implements Listener {

    private final SchiggySettings plugin;

    public PrivateMessageListener(SchiggySettings plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message.length() < 2 || message.charAt(0) != '/') {
            return;
        }
        String[] parts = message.substring(1).trim().split("\\s+");
        if (parts.length < 2) {
            return;
        }
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (label.startsWith("minecraft:")) {
            label = label.substring("minecraft:".length());
        }
        Set<String> commands = new HashSet<>();
        List<String> configured = plugin.getConfig().getStringList("private-message-commands");
        for (String entry : configured) {
            commands.add(entry.toLowerCase(Locale.ROOT));
        }
        if (!commands.contains(label)) {
            return;
        }
        Player target = Bukkit.getPlayerExact(parts[1]);
        if (target == null || target.getUniqueId().equals(event.getPlayer().getUniqueId())) {
            return;
        }
        if (plugin.service().isEnabled(target.getUniqueId(), Setting.MESSAGES)
                || event.getPlayer().hasPermission("schiggy.settings.bypass")) {
            return;
        }
        event.setCancelled(true);
        plugin.messages().send(event.getPlayer(), "messages-disabled", "player", target.getName());
    }
}
