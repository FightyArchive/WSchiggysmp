package de.schiggy.teleport;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.WarmupTeleport;
import de.schiggy.settings.SchiggySettings;
import de.schiggy.settings.Setting;
import de.schiggy.teleport.SchiggyTeleport.Request;
import de.schiggy.teleport.RtpService.Target;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TeleportCommand implements TabExecutor {

    private final SchiggyTeleport plugin;
    /** Spieler, fuer die gerade ein /rtp-Ort gesucht wird. */
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    public TeleportCommand(SchiggyTeleport plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        String permission = name.equals("rtp") ? "schiggy.teleport.rtp" : "schiggy.teleport.tpa";
        if (!player.hasPermission(permission)) {
            msg().send(player, "no-permission");
            return true;
        }
        switch (name) {
            case "tpa" -> request(player, args, false, "tpa");
            case "tpahere" -> request(player, args, true, "tpahere");
            case "tpaccept" -> accept(player, args);
            case "tpdeny" -> deny(player, args);
            case "rtp" -> rtp(player, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ /tpa und /tpahere

    private void request(Player player, String[] args, boolean here, String commandName) {
        if (args.length < 1) {
            msg().send(player, "tpa-usage", "command", commandName);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            msg().send(player, "player-offline");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            msg().send(player, "tpa-self");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!player.hasPermission("schiggy.teleport.bypass-cooldown")) {
            long seconds = plugin.requestCooldowns().remainingSeconds(uuid);
            if (seconds > 0) {
                msg().send(player, "cooldown", "seconds", String.valueOf(seconds));
                return;
            }
        }
        // Persoenliche Einstellung des Empfaengers (SchiggySettings)
        SchiggySettings settings = SchiggySettings.getInstance();
        if (settings != null && !settings.service().isEnabled(target.getUniqueId(), Setting.TPA)) {
            msg().send(player, "tpa-disabled", "player", target.getName());
            return;
        }

        FileConfiguration cfg = plugin.getConfig();
        long timeout = cfg.getLong("request-timeout-seconds", 60);
        plugin.requests().put(target.getUniqueId(),
                new Request(uuid, player.getName(), here, System.currentTimeMillis() + timeout * 1000L));
        plugin.requestCooldowns().set(uuid, cfg.getLong("request-cooldown-seconds", 10));

        msg().send(player, here ? "tpahere-sent" : "tpa-sent",
                "player", target.getName(), "seconds", String.valueOf(timeout));
        String senderName = player.getName();
        SchiggyScheduler.entity(plugin, target, () -> msg().send(target, here ? "tpahere-received" : "tpa-received",
                "player", senderName, "seconds", String.valueOf(timeout)));
    }

    // ------------------------------------------------------------------ /tpaccept und /tpdeny

    /** Gibt die gueltige Anfrage zurueck oder null (Nachricht wurde dann schon gesendet). */
    private Request findRequest(Player player, String[] args) {
        Request request = plugin.requests().get(player.getUniqueId());
        if (request == null || request.expiresAt() < System.currentTimeMillis()) {
            plugin.requests().remove(player.getUniqueId());
            msg().send(player, "no-request");
            return null;
        }
        if (args.length > 0 && !request.fromName().equalsIgnoreCase(args[0])) {
            msg().send(player, "no-request-from", "player", args[0]);
            return null;
        }
        return request;
    }

    private void accept(Player player, String[] args) {
        Request request = findRequest(player, args);
        if (request == null) {
            return;
        }
        if (args.length == 0 && plugin.getConfig().getBoolean("accept-gui", true)) {
            openAcceptGui(player, request);
            return;
        }
        acceptRequest(player, request);
    }

    /** Zeigt Kopf, Name, Welt und Flug-Status des Spielers und fragt nach Annehmen oder Ablehnen. */
    private void openAcceptGui(Player player, Request request) {
        Player requester = Bukkit.getPlayer(request.from());
        if (requester == null) {
            msg().send(player, "player-offline");
            return;
        }
        // Welt und Flug-Status muessen auf dem Thread des Anfragenden gelesen werden
        SchiggyScheduler.entity(plugin, requester, () -> {
            String world = plugin.getConfig().getString("world-names." + requester.getWorld().getName(),
                    requester.getWorld().getName());
            boolean flying = requester.isFlying() || requester.isGliding();
            SchiggyScheduler.entity(plugin, player, () -> showAcceptGui(player, request, world, flying));
        });
    }

    private void showAcceptGui(Player player, Request request, String world, boolean flying) {
        SchiggyGui gui = new SchiggyGui(msg().component("gui-accept-title"), 3);
        String[] placeholders = {"player", request.fromName(), "world", world,
                "flying", msg().raw(flying ? "gui-yes" : "gui-no")};
        gui.set(13, ItemBuilder.head(Bukkit.getOfflinePlayer(request.from()))
                .name(msg().raw("gui-accept-head-name", placeholders))
                .lore(msg().rawList(request.here() ? "gui-accept-head-lore-here" : "gui-accept-head-lore", placeholders))
                .build());
        gui.set(11, ItemBuilder.of(Material.LIME_CONCRETE).name(msg().raw("gui-accept-confirm-name"))
                .lore(msg().rawList("gui-accept-confirm-lore", placeholders)).build(), event -> {
                    player.closeInventory();
                    if (plugin.requests().get(player.getUniqueId()) == request) {
                        acceptRequest(player, request);
                    } else {
                        msg().send(player, "no-request");
                    }
                });
        gui.set(15, ItemBuilder.of(Material.RED_CONCRETE).name(msg().raw("gui-accept-deny-name"))
                .lore(msg().rawList("gui-accept-deny-lore", placeholders)).build(), event -> {
                    player.closeInventory();
                    deny(player, new String[]{request.fromName()});
                });
        gui.open(player);
    }

    /** Nimmt die Anfrage an. Muss auf dem Thread des Annehmenden laufen. */
    private void acceptRequest(Player player, Request request) {
        plugin.requests().remove(player.getUniqueId(), request);

        Player requester = Bukkit.getPlayer(request.from());
        if (requester == null) {
            msg().send(player, "player-offline");
            return;
        }
        msg().send(player, "tpa-accepted-self", "player", request.fromName());
        String accepterName = player.getName();

        if (!request.here()) {
            // Der Absender teleportiert sich zum Annehmenden
            Location dest = player.getLocation();
            SchiggyScheduler.entity(plugin, requester, () -> {
                msg().send(requester, "tpa-accepted", "player", accepterName);
                teleport(requester, dest, plugin.getConfig().getInt("tpa.warmup-seconds", 3));
            });
        } else {
            // Der Annehmende teleportiert sich zum Absender
            SchiggyScheduler.entity(plugin, requester, () -> {
                Location dest = requester.getLocation();
                msg().send(requester, "tpa-accepted", "player", accepterName);
                SchiggyScheduler.entity(plugin, player,
                        () -> teleport(player, dest, plugin.getConfig().getInt("tpa.warmup-seconds", 3)));
            });
        }
    }

    private void deny(Player player, String[] args) {
        Request request = findRequest(player, args);
        if (request == null) {
            return;
        }
        plugin.requests().remove(player.getUniqueId(), request);
        msg().send(player, "tpa-denied-self", "player", request.fromName());
        Player requester = Bukkit.getPlayer(request.from());
        if (requester != null) {
            String name = player.getName();
            SchiggyScheduler.entity(plugin, requester, () -> msg().send(requester, "tpa-denied", "player", name));
        }
    }

    // ------------------------------------------------------------------ /rtp

    /** /rtp oeffnet das Menue, /rtp overworld|nether|end teleportiert direkt. */
    private void rtp(Player player, String[] args) {
        if (args.length > 0) {
            Optional<Target> target = plugin.rtp().target(args[0].toLowerCase(Locale.ROOT));
            if (target.isEmpty()) {
                msg().send(player, "rtp-world-blocked");
                return;
            }
            startRtp(player, target.get());
            return;
        }
        new RtpGui(plugin, this).open(player);
    }

    /** Sucht einen sicheren Ort im Ziel und teleportiert dann. Muss auf dem Thread des Spielers laufen. */
    void startRtp(Player player, Target target) {
        UUID uuid = player.getUniqueId();
        if (!player.hasPermission("schiggy.teleport.bypass-cooldown")) {
            long seconds = plugin.rtpCooldowns().remainingSeconds(uuid);
            if (seconds > 0) {
                msg().send(player, "cooldown", "seconds", String.valueOf(seconds));
                return;
            }
        }
        if (!searching.add(uuid)) {
            msg().send(player, "rtp-searching");
            return;
        }
        msg().send(player, "rtp-searching");

        plugin.rtp().find(target).whenComplete((found, error) -> {
            searching.remove(uuid);
            SchiggyScheduler.entity(plugin, player, () -> {
                if (error != null || found == null || found.isEmpty()) {
                    msg().send(player, "rtp-failed");
                    return;
                }
                FileConfiguration cfg = plugin.getConfig();
                teleport(player, found.get(), cfg.getInt("rtp.warmup-seconds", 3), () ->
                        plugin.rtpCooldowns().set(uuid, cfg.getLong("rtp.cooldown-seconds", 60)));
            });
        });
    }

    // ------------------------------------------------------------------ gemeinsamer Teleport

    private void teleport(Player who, Location dest, int warmupSeconds) {
        teleport(who, dest, warmupSeconds, () -> {
        });
    }

    /** Muss auf dem Thread des Spielers laufen. onSuccess wird nach einem erfolgreichen Teleport aufgerufen. */
    private void teleport(Player who, Location dest, int warmupSeconds, Runnable onSuccess) {
        int warmup = who.hasPermission("schiggy.teleport.bypass-warmup") ? 0 : warmupSeconds;
        if (warmup > 0) {
            msg().send(who, "teleport-warmup", "seconds", String.valueOf(warmup));
        }
        WarmupTeleport.start(plugin, who, dest, warmup, false, false,
                remaining -> who.sendActionBar(msg().component("teleport-countdown",
                        "seconds", String.valueOf(remaining))),
                result -> {
                    switch (result) {
                        case SUCCESS -> {
                            onSuccess.run();
                            msg().send(who, "teleport-success");
                        }
                        case MOVED -> msg().send(who, "teleport-cancelled");
                        case UNSAFE -> msg().send(who, "teleport-unsafe");
                        case ALREADY_PENDING -> msg().send(who, "teleport-pending");
                        default -> msg().send(who, "teleport-failed");
                    }
                });
    }

    // ------------------------------------------------------------------ Tab-Completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("tpa") || name.equals("tpahere")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.getUniqueId().equals(player.getUniqueId())
                        && online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    out.add(online.getName());
                }
            }
        } else if (name.equals("rtp")) {
            for (String id : RtpService.IDS) {
                if (id.startsWith(typed)) {
                    out.add(id);
                }
            }
        } else if (name.equals("tpaccept") || name.equals("tpdeny")) {
            Request request = plugin.requests().get(player.getUniqueId());
            if (request != null && request.fromName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                out.add(request.fromName());
            }
        }
        return out;
    }
}
