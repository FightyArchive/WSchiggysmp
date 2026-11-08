package de.schiggy.settings;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** "Oeffentlicher Chat" aus: Der Spieler sieht keine Chatnachrichten anderer Spieler mehr. */
public final class ChatListener implements Listener {

    private final SchiggySettings plugin;

    public ChatListener(SchiggySettings plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        event.viewers().removeIf(viewer -> viewer instanceof Player player
                && !player.getUniqueId().equals(sender.getUniqueId())
                && !plugin.service().isEnabled(player.getUniqueId(), Setting.CHAT));
    }
}
