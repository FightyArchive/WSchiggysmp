package de.schiggy.homes;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.WarmupTeleport;
import de.schiggy.homes.HomeService.Home;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class HomeCommand implements TabExecutor {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,16}");
    private static final String LIMIT_PREFIX = "schiggy.homes.limit.";

    private final SchiggyHomes plugin;
    private final HomeGui gui;

    public HomeCommand(SchiggyHomes plugin) {
        this.plugin = plugin;
        this.gui = new HomeGui(plugin, this);
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private HomeService service() {
        return plugin.homes();
    }

    void reply(Player player, Runnable task) {
        SchiggyScheduler.entity(plugin, player, task);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.homes.use")) {
            msg().send(sender, "no-permission");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sethome" -> setHome(player, args.length > 0 ? args[0] : "home", false);
            case "home" -> home(player, args);
            case "homes" -> openGui(player);
            case "delhome" -> delHome(player, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ Menue

    /** Laedt die Homes und oeffnet das Menue auf dem Thread des Spielers. */
    void openGui(Player player) {
        service().list(player.getUniqueId()).thenAccept(homes -> reply(player, () -> gui.open(player, homes)));
    }

    // ------------------------------------------------------------------ /sethome

    /** reopenGui = true: nach dem Setzen wird das Menue wieder geoeffnet (Klick auf einen freien Platz). */
    void setHome(Player player, String name, boolean reopenGui) {
        if (!NAME.matcher(name).matches()) {
            msg().send(player, "invalid-name", "max", "16");
            return;
        }
        Location loc = player.getLocation();
        if (plugin.getConfig().getStringList("blocked-worlds").contains(loc.getWorld().getName())) {
            msg().send(player, "world-blocked");
            return;
        }
        int limit = limitFor(player);
        Home home = new Home(name, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
        service().save(player.getUniqueId(), home, limit).thenAccept(result -> reply(player, () -> {
            switch (result.status()) {
                case CREATED -> msg().send(player, "home-set", "name", name,
                        "count", String.valueOf(result.count()), "limit", limitText(limit));
                case UPDATED -> msg().send(player, "home-updated", "name", name,
                        "count", String.valueOf(result.count()), "limit", limitText(limit));
                case LIMIT_REACHED -> msg().send(player, "home-limit", "limit", limitText(limit));
                default -> msg().send(player, "error");
            }
            if (reopenGui) {
                openGui(player);
            }
        }));
    }

    // ------------------------------------------------------------------ /home

    private void home(Player player, String[] args) {
        service().list(player.getUniqueId()).thenAccept(homes -> reply(player, () -> {
            if (homes.isEmpty()) {
                msg().send(player, "no-homes");
                return;
            }
            Home chosen = null;
            if (args.length > 0) {
                for (Home candidate : homes) {
                    if (candidate.name().equalsIgnoreCase(args[0])) {
                        chosen = candidate;
                    }
                }
                if (chosen == null) {
                    msg().send(player, "home-not-found", "name", args[0]);
                    return;
                }
            } else {
                for (Home candidate : homes) {
                    if (candidate.name().equalsIgnoreCase("home")) {
                        chosen = candidate;
                    }
                }
                if (chosen == null && homes.size() == 1) {
                    chosen = homes.get(0);
                }
                if (chosen == null) {
                    gui.open(player, homes);
                    return;
                }
            }
            teleportTo(player, chosen);
        }));
    }

    /** Muss auf dem Thread des Spielers laufen. */
    void teleportTo(Player player, Home home) {
        World world = Bukkit.getWorld(home.world());
        if (world == null) {
            msg().send(player, "world-missing");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!player.hasPermission("schiggy.homes.bypass-cooldown")) {
            long seconds = plugin.cooldowns().remainingSeconds(uuid);
            if (seconds > 0) {
                msg().send(player, "cooldown", "seconds", String.valueOf(seconds));
                return;
            }
        }
        FileConfiguration cfg = plugin.getConfig();
        Location dest = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        int warmup = player.hasPermission("schiggy.homes.bypass-warmup") ? 0 : cfg.getInt("teleport.warmup-seconds", 3);
        if (warmup > 0) {
            msg().send(player, "teleport-warmup", "seconds", String.valueOf(warmup));
        }
        WarmupTeleport.start(plugin, player, dest, warmup, true, false,
                remaining -> player.sendActionBar(msg().component("teleport-countdown",
                        "seconds", String.valueOf(remaining))),
                result -> {
                    switch (result) {
                        case SUCCESS -> {
                            plugin.cooldowns().set(uuid, cfg.getLong("teleport.cooldown-seconds", 5));
                            msg().send(player, "teleport-success");
                        }
                        case MOVED -> msg().send(player, "teleport-cancelled");
                        case UNSAFE -> msg().send(player, "teleport-unsafe");
                        case ALREADY_PENDING -> msg().send(player, "teleport-pending");
                        default -> msg().send(player, "teleport-failed");
                    }
                });
    }

    // ------------------------------------------------------------------ /delhome

    private void delHome(Player player, String[] args) {
        if (args.length < 1) {
            msg().send(player, "delhome-usage");
            return;
        }
        String name = args[0];
        boolean confirmed = args.length > 1 && args[1].equalsIgnoreCase("confirm");
        if (plugin.getConfig().getBoolean("confirm-delete", true) && !confirmed) {
            service().list(player.getUniqueId()).thenAccept(homes -> reply(player, () -> {
                for (Home home : homes) {
                    if (home.name().equalsIgnoreCase(name)) {
                        msg().send(player, "delhome-confirm", "name", home.name());
                        return;
                    }
                }
                msg().send(player, "home-not-found", "name", name);
            }));
            return;
        }
        service().delete(player.getUniqueId(), name).thenAccept(deleted -> reply(player, () -> {
            if (deleted) {
                msg().send(player, "home-deleted", "name", name);
            } else {
                msg().send(player, "home-not-found", "name", name);
            }
        }));
    }

    // ------------------------------------------------------------------ Limits

    /** -1 = unbegrenzt. Laeuft auf dem Thread des Spielers. */
    int limitFor(Player player) {
        if (player.hasPermission("schiggy.homes.limit.unlimited")) {
            return -1;
        }
        int best = plugin.getConfig().getInt("default-limit", 3);
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String permission = info.getPermission();
            if (permission.startsWith(LIMIT_PREFIX)) {
                try {
                    best = Math.max(best, Integer.parseInt(permission.substring(LIMIT_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // kein Zahlenwert (z. B. unlimited), wird oben behandelt
                }
            }
        }
        return best;
    }

    static String limitText(int limit) {
        return limit < 0 ? "∞" : String.valueOf(limit);
    }

    // ------------------------------------------------------------------ Tab-Completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!name.equals("home") && !name.equals("delhome")) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String home : service().cachedNames(player.getUniqueId())) {
            if (home.toLowerCase(Locale.ROOT).startsWith(typed)) {
                out.add(home);
            }
        }
        return out;
    }
}
