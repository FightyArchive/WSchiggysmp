package de.schiggy.keydrop;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Names;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class KeydropCommand implements TabExecutor {

    private final SchiggyKeydrop plugin;

    public KeydropCommand(SchiggyKeydrop plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = sender.hasPermission("schiggy.keydrop.admin");
        switch (sub) {
            case "" -> {
                if (admin && sender instanceof Player player) {
                    plugin.gui().open(player);
                } else {
                    info(sender);
                }
            }
            case "info" -> info(sender);
            case "start" -> {
                if (!admin) {
                    msg().send(sender, "no-permission");
                    return true;
                }
                String preset = args.length > 1 ? args[1] : null;
                switch (plugin.service().start(preset, sender.getName())) {
                    case STARTED -> msg().send(sender, "started", "preset",
                            preset == null ? "(zufällig)" : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                    .stripTags(plugin.service().presets().get(preset.toLowerCase(Locale.ROOT)).name()));
                    case ALREADY_RUNNING -> msg().send(sender, "already-running");
                    case UNKNOWN_PRESET -> msg().send(sender, "unknown-preset", "preset", preset,
                            "presets", String.join(", ", plugin.service().presets().keySet()));
                    default -> msg().send(sender, "no-presets");
                }
            }
            case "stop" -> {
                if (!admin) {
                    msg().send(sender, "no-permission");
                    return true;
                }
                if (!plugin.service().stop(sender.getName())) {
                    msg().send(sender, "not-running");
                } else if (!(sender instanceof Player)) {
                    msg().send(sender, "stopped-admin");
                }
            }
            case "reload" -> {
                if (!admin) {
                    msg().send(sender, "no-permission");
                    return true;
                }
                plugin.reloadConfig();
                msg().reload();
                plugin.service().loadPresets();
                plugin.service().scheduleNextAuto();
                msg().send(sender, "reloaded", "count", String.valueOf(plugin.service().presets().size()));
            }
            default -> msg().send(sender, "usage");
        }
        return true;
    }

    private void info(CommandSender sender) {
        if (!sender.hasPermission("schiggy.keydrop.info")) {
            msg().send(sender, "no-permission");
            return;
        }
        KeydropService service = plugin.service();
        if (service.isRunning()) {
            msg().send(sender, "info-running", "preset", service.runningPresetName(),
                    "seconds", String.valueOf(service.remainingSeconds()));
        } else {
            msg().send(sender, "info-idle");
        }
        if (service.autoEnabled()) {
            msg().send(sender, "info-next-auto", "time", Names.duration(service.nextAutoMillis()));
        } else {
            msg().send(sender, "info-auto-off");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("schiggy.keydrop.admin");
        if (args.length == 1) {
            out.add("info");
            if (admin) {
                out.addAll(List.of("start", "stop", "reload"));
            }
        } else if (args.length == 2 && admin && args[0].equalsIgnoreCase("start")) {
            out.addAll(plugin.service().presets().keySet());
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
