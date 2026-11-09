package de.schiggy.core.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Laedt messages.yml eines Plugins (MiniMessage-Format).
 * Platzhalter: {name}. {prefix} wird durch den Wert von "prefix" ersetzt.
 * Werte von Platzhaltern werden escaped, Spieler koennen also keine Formatierung einschleusen.
 */
public final class MessageBundle {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private volatile YamlConfiguration yaml;

    MessageBundle(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        InputStream defaults = plugin.getResource("messages.yml");
        if (defaults != null) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        this.yaml = loaded;
    }

    /** Platzhalter als Paare: component("key", "player", "Steve", "amount", "100"). */
    public Component component(String key, String... placeholders) {
        String raw = yaml.getString(key);
        if (raw == null) {
            return Component.text("Fehlende Nachricht: " + key);
        }
        return MM.deserialize(apply(raw, placeholders));
    }

    /** Wie component, aber als fertiger MiniMessage-Text (fuer Item-Namen in GUIs). */
    public String raw(String key, String... placeholders) {
        String raw = yaml.getString(key);
        if (raw == null) {
            return "<red>Fehlende Nachricht: " + MM.escapeTags(key);
        }
        return apply(raw, placeholders);
    }

    /** Eine Liste aus der messages.yml (fuer Item-Beschreibungen), jede Zeile mit Platzhaltern ersetzt. */
    public List<String> rawList(String key, String... placeholders) {
        List<String> lines = yaml.getStringList(key);
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(apply(line, placeholders));
        }
        return result;
    }

    public void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(component(key, placeholders));
    }

    private String apply(String raw, String... placeholders) {
        String result = raw.replace("{prefix}", yaml.getString("prefix", ""));
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            result = result.replace("{" + placeholders[i] + "}", MM.escapeTags(placeholders[i + 1]));
        }
        return result;
    }
}
