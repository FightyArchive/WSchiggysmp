package de.schiggy.combat;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

final class CombatListener implements Listener {

    private final SchiggyCombat plugin;

    CombatListener(SchiggyCombat plugin) {
        this.plugin = plugin;
    }

    private CombatService service() {
        return plugin.service();
    }

    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /** Gueltiger PvP-Treffer: nur wenn der Schaden nicht abgebrochen wurde und wirklich Schaden entsteht. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0) {
            return;
        }
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        service().hit(victim, attacker);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!service().isTagged(player.getUniqueId()) || player.hasPermission("schiggy.combat.bypass")) {
            return;
        }
        String message = event.getMessage();
        if (message.length() < 2) {
            return;
        }
        String label = message.substring(1).trim().split("\\s+")[0];
        if (service().isCommandBlocked(label)) {
            event.setCancelled(true);
            plugin.messages().send(player, "command-blocked", "seconds",
                    String.valueOf((service().remainingMillis(player.getUniqueId()) + 999) / 1000));
        }
    }

    /** Teleports durch andere Plugins oder Befehle waehrend des Combats. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!service().isTagged(player.getUniqueId()) || player.hasPermission("schiggy.combat.bypass")
                || !service().isCauseBlocked(event.getCause())) {
            return;
        }
        event.setCancelled(true);
        player.sendActionBar(plugin.messages().component("teleport-blocked", "seconds",
                String.valueOf((service().remainingMillis(player.getUniqueId()) + 999) / 1000)));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        service().untag(event.getEntity(), false);
    }

    /** Combat Log: Wer sich im Kampf ausloggt, wird bestraft. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean tagged = service().isTagged(id) && !player.hasPermission("schiggy.combat.bypass");
        boolean killed = false;
        if (tagged) {
            UUID opponent = service().lastOpponent(id);
            if (plugin.getConfig().getBoolean("combat-log.broadcast", true)) {
                var message = plugin.messages().component("combat-log", "player", player.getName());
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    de.schiggy.core.util.SchiggyScheduler.entity(plugin, online, () -> online.sendMessage(message));
                }
            }
            if (opponent != null) {
                Player other = plugin.getServer().getPlayer(opponent);
                if (other != null) {
                    de.schiggy.core.util.SchiggyScheduler.entity(plugin, other,
                            () -> plugin.messages().send(other, "combat-log-victim", "player", player.getName()));
                }
            }
            for (String command : plugin.getConfig().getStringList("combat-log.penalty-commands")) {
                String resolved = command.replace("{player}", player.getName());
                de.schiggy.core.util.SchiggyScheduler.global(plugin,
                        () -> plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), resolved));
            }
            if (plugin.getConfig().getBoolean("combat-log.kill-player", true)) {
                player.setHealth(0.0);
                killed = true;
            }
        }
        boolean keep = tagged && !killed && plugin.getConfig().getBoolean("combat-log.keep-tag-after-rejoin", true);
        service().saveOnQuit(id, keep);
        service().untag(player, false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service().loadOnJoin(event.getPlayer());
    }
}
