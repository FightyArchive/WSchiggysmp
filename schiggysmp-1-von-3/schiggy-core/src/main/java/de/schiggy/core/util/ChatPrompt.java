package de.schiggy.core.util;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fragt einen Spieler nach einem Text: Seine naechste Chatnachricht wird NICHT im Chat gezeigt, sondern an den
 * Callback gegeben (z. B. fuer Suchbegriffe, Preise oder Namen in Menues). "cancel" oder "abbrechen" bricht ab.
 * Der Callback laeuft auf dem Thread des Spielers und bekommt null bei Abbruch oder nach 90 Sekunden.
 */
public final class ChatPrompt {

    private record Prompt(Plugin plugin, Consumer<String> callback) {
    }

    private static final Map<UUID, Prompt> PENDING = new ConcurrentHashMap<>();

    private ChatPrompt() {
    }

    public static void ask(Plugin plugin, Player player, Consumer<String> callback) {
        UUID id = player.getUniqueId();
        Prompt prompt = new Prompt(plugin, callback);
        PENDING.put(id, prompt);
        player.getScheduler().runDelayed(plugin, task -> {
            if (PENDING.remove(id, prompt)) {
                callback.accept(null);
            }
        }, null, 20L * 90);
    }

    public static boolean isPending(UUID id) {
        return PENDING.containsKey(id);
    }

    /** Wird von SchiggyCore registriert. */
    public static final class ChatListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onChat(AsyncChatEvent event) {
            Player player = event.getPlayer();
            Prompt prompt = PENDING.remove(player.getUniqueId());
            if (prompt == null) {
                return;
            }
            event.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            boolean cancelled = text.isEmpty() || text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("abbrechen");
            SchiggyScheduler.entity(prompt.plugin(), player, () -> prompt.callback().accept(cancelled ? null : text));
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            PENDING.remove(event.getPlayer().getUniqueId());
        }
    }
}
