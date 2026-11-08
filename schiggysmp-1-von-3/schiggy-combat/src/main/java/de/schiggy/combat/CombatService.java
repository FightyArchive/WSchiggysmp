package de.schiggy.combat;

import de.schiggy.core.database.Database;
import de.schiggy.core.util.SchiggyScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Combat Tags. Pro Spieler laeuft eine Aufgabe auf dessen eigenem Region-Thread (Paper und Folia),
 * die Bossbar und Actionbar aktualisiert und den Tag nach Ablauf entfernt.
 * Die Methoden isTagged und remainingMillis sind fuer andere Plugins (Duell, RTPQ, ...) gedacht.
 */
public final class CombatService {

    private final SchiggyCombat plugin;
    private final Database db;
    private final Map<UUID, Long> until = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> opponents = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private volatile Set<String> blockedCommands = Set.of();
    private volatile Set<String> blockedCauses = Set.of();

    public CombatService(SchiggyCombat plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS sc_combat (uuid TEXT PRIMARY KEY, until INTEGER NOT NULL)");
            }
            return null;
        }).join();
        loadRestrictions();
    }

    public void loadRestrictions() {
        File file = new File(plugin.getDataFolder(), "restrictions.yml");
        if (!file.exists()) {
            plugin.saveResource("restrictions.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Set<String> commands = new HashSet<>();
        for (String entry : yaml.getStringList("blocked-commands")) {
            commands.add(normalize(entry));
        }
        Set<String> causes = new HashSet<>();
        for (String entry : yaml.getStringList("blocked-teleport-causes")) {
            causes.add(entry.toUpperCase(Locale.ROOT));
        }
        this.blockedCommands = commands;
        this.blockedCauses = causes;
    }

    private static String normalize(String raw) {
        String label = raw.trim().toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        int colon = label.indexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }

    // ------------------------------------------------------------------ Abfragen (auch fuer andere Plugins)

    public long durationMillis() {
        return Math.max(1, plugin.getConfig().getLong("duration-seconds", 15)) * 1000L;
    }

    public boolean isTagged(UUID uuid) {
        Long end = until.get(uuid);
        return end != null && end > System.currentTimeMillis();
    }

    public long remainingMillis(UUID uuid) {
        Long end = until.get(uuid);
        return end == null ? 0 : Math.max(0, end - System.currentTimeMillis());
    }

    public UUID lastOpponent(UUID uuid) {
        return opponents.get(uuid);
    }

    public boolean isCommandBlocked(String rawLabel) {
        String label = normalize(rawLabel);
        Set<String> blocked = blockedCommands;
        if (blocked.contains(label)) {
            return true;
        }
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (command != null) {
            if (blocked.contains(command.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
            for (String alias : command.getAliases()) {
                if (blocked.contains(alias.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isCauseBlocked(PlayerTeleportEvent.TeleportCause cause) {
        return blockedCauses.contains(cause.name());
    }

    private boolean disabledWorld(Player player) {
        return plugin.getConfig().getStringList("disabled-worlds").contains(player.getWorld().getName());
    }

    // ------------------------------------------------------------------ Tag setzen und entfernen

    /** Ein gueltiger PvP-Treffer zwischen zwei Spielern. Muss auf dem Thread des Opfers laufen. */
    void hit(Player victim, Player attacker) {
        if (!victim.hasPermission("schiggy.combat.bypass") && !disabledWorld(victim)) {
            tag(victim, attacker.getUniqueId());
        }
        SchiggyScheduler.entity(plugin, attacker, () -> {
            if (!attacker.hasPermission("schiggy.combat.bypass") && !disabledWorld(attacker)) {
                tag(attacker, victim.getUniqueId());
            }
        });
    }

    /** Muss auf dem Thread des Spielers laufen. */
    void tag(Player player, UUID opponent) {
        UUID id = player.getUniqueId();
        boolean wasTagged = isTagged(id);
        until.put(id, System.currentTimeMillis() + durationMillis());
        opponents.put(id, opponent);
        if (!wasTagged) {
            plugin.messages().send(player, "combat-start");
        }
        startTask(player);
    }

    /** Stellt einen gespeicherten Tag nach dem Wiedereinloggen wieder her. */
    void restore(Player player, long endsAt) {
        until.put(player.getUniqueId(), endsAt);
        startTask(player);
    }

    private void startTask(Player player) {
        UUID id = player.getUniqueId();
        if (tasks.containsKey(id)) {
            return;
        }
        if (plugin.getConfig().getBoolean("bossbar.enabled", true)) {
            bars.computeIfAbsent(id, key -> {
                BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f, barColor(), barOverlay());
                player.showBossBar(bar);
                return bar;
            });
        }
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> tick(player), () -> clear(id), 1L, 10L);
        if (task != null) {
            tasks.put(id, task);
        }
    }

    private void tick(Player player) {
        UUID id = player.getUniqueId();
        long remaining = remainingMillis(id);
        if (!player.isOnline()) {
            clear(id);
            return;
        }
        if (remaining <= 0) {
            untag(player, true);
            return;
        }
        String seconds = String.valueOf((remaining + 999) / 1000);
        BossBar bar = bars.get(id);
        if (bar != null) {
            bar.name(plugin.messages().component("bossbar", "seconds", seconds));
            bar.progress((float) Math.max(0, Math.min(1, remaining / (double) durationMillis())));
        }
        if (plugin.getConfig().getBoolean("actionbar.enabled", true)) {
            player.sendActionBar(plugin.messages().component("actionbar", "seconds", seconds));
        }
    }

    /** Entfernt den Tag. Muss auf dem Thread des Spielers laufen. */
    public void untag(Player player, boolean notify) {
        UUID id = player.getUniqueId();
        boolean was = until.containsKey(id);
        clear(id);
        BossBar bar = bars.remove(id);
        if (bar != null) {
            player.hideBossBar(bar);
        }
        if (was && notify) {
            plugin.messages().send(player, "combat-ended");
        }
    }

    /** Raeumt alle Daten eines Spielers auf (auch ohne Spieler-Thread). */
    private void clear(UUID id) {
        until.remove(id);
        opponents.remove(id);
        ScheduledTask task = tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    // ------------------------------------------------------------------ Speichern

    /** Beim Verlassen: den Tag speichern (oder die Zeile loeschen). */
    void saveOnQuit(UUID id, boolean keep) {
        long end = until.getOrDefault(id, 0L);
        db.<Void>submit(c -> {
            if (keep && end > System.currentTimeMillis()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO sc_combat(uuid, until) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET until = excluded.until")) {
                    ps.setString(1, id.toString());
                    ps.setLong(2, end);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM sc_combat WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    ps.executeUpdate();
                }
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Combat-Status konnte nicht gespeichert werden", ex);
            return null;
        });
    }

    /** Beim Betreten: einen gespeicherten Tag wiederherstellen. */
    void loadOnJoin(Player player) {
        UUID id = player.getUniqueId();
        db.<Long>submit(c -> {
            long end = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT until FROM sc_combat WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        end = rs.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sc_combat WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
            return end;
        }).thenAccept(end -> {
            if (end > System.currentTimeMillis() && !player.hasPermission("schiggy.combat.bypass")) {
                SchiggyScheduler.entity(plugin, player, () -> {
                    restore(player, end);
                    plugin.messages().send(player, "combat-start");
                });
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Combat-Status konnte nicht geladen werden", ex);
            return null;
        });
    }

    // ------------------------------------------------------------------ Konfiguration

    private BossBar.Color barColor() {
        try {
            return BossBar.Color.valueOf(plugin.getConfig().getString("bossbar.color", "RED").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.RED;
        }
    }

    private BossBar.Overlay barOverlay() {
        try {
            return BossBar.Overlay.valueOf(plugin.getConfig().getString("bossbar.overlay", "PROGRESS").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Overlay.PROGRESS;
        }
    }
}
