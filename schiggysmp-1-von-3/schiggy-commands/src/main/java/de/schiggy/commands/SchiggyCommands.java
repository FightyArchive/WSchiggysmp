package de.schiggy.commands;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Befehls-Whitelist: Spieler duerfen nur Befehle benutzen, die eine ihrer Gruppen erlaubt.
 * Gruppen sind LuckPerms-Gruppen (Recht group.<name>). Gesperrte Befehle werden blockiert und aus der
 * Tab-Vervollstaendigung entfernt.
 */
public final class SchiggyCommands extends JavaPlugin implements CommandExecutor {

    private MessageBundle messages;
    private volatile Map<String, Set<String>> groups = Map.of();

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggyCommands wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        messages = core.messages().bundle(this);
        loadGroups();

        getServer().getPluginManager().registerEvents(new CommandListener(this), this);
        PluginCommand command = getCommand("schiggycommands");
        if (command != null) {
            command.setExecutor(this);
        }
        getLogger().info("SchiggyCommands aktiviert (" + groups.size() + " Gruppen).");
    }

    MessageBundle messages() {
        return messages;
    }

    private void loadGroups() {
        Map<String, Set<String>> loaded = new HashMap<>();
        ConfigurationSection section = getConfig().getConfigurationSection("groups");
        if (section != null) {
            for (String group : section.getKeys(false)) {
                Set<String> commands = new HashSet<>();
                for (String entry : section.getStringList(group)) {
                    commands.add(normalize(entry));
                }
                loaded.put(group.toLowerCase(Locale.ROOT), commands);
            }
        }
        this.groups = loaded;
    }

    /** Kleinschreibung, ohne Schraegstrich und ohne Plugin-Praefix (minecraft:msg wird zu msg). */
    static String normalize(String raw) {
        String label = raw.trim().toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        return label;
    }

    /** Darf dieser Spieler den Befehl benutzen? */
    boolean allowed(Player player, String rawLabel) {
        if (!getConfig().getBoolean("enabled", true)
                || player.hasPermission(getConfig().getString("bypass-permission", "schiggy.commands.bypass"))) {
            return true;
        }
        Set<String> allowed = new HashSet<>(groups.getOrDefault("default", Set.of()));
        for (Map.Entry<String, Set<String>> entry : groups.entrySet()) {
            if (!entry.getKey().equals("default") && player.hasPermission("group." + entry.getKey())) {
                allowed.addAll(entry.getValue());
            }
        }
        if (allowed.contains("*")) {
            return true;
        }
        String label = normalize(rawLabel);
        if (allowed.contains(label)) {
            return true;
        }
        // Aliase: Haupt-Name und alle Aliase des Befehls pruefen
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (command != null) {
            if (allowed.contains(command.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
            for (String alias : command.getAliases()) {
                if (allowed.contains(alias.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("schiggy.commands.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            messages.reload();
            loadGroups();
            messages.send(sender, "reloaded", "count", String.valueOf(groups.size()));
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("check")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                messages.send(sender, "player-offline");
                return true;
            }
            boolean ok = allowed(target, args[2]);
            messages.send(sender, "check-result", "player", target.getName(), "command", normalize(args[2]),
                    "result", messages.raw(ok ? "result-yes" : "result-no"));
            return true;
        }
        messages.send(sender, "usage");
        return true;
    }
}
