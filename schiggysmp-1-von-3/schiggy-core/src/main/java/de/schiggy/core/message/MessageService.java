package de.schiggy.core.message;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Erzeugt pro Plugin ein MessageBundle (messages.yml im Plugin-Ordner) und merkt sich alle zum Neuladen. */
public final class MessageService {

    private final List<MessageBundle> bundles = new CopyOnWriteArrayList<>();

    public MessageBundle bundle(JavaPlugin plugin) {
        MessageBundle bundle = new MessageBundle(plugin);
        bundles.add(bundle);
        return bundle;
    }

    /** Laedt die messages.yml aller Schiggy-Plugins neu und gibt zurueck, wie viele es waren. */
    public int reloadAll() {
        for (MessageBundle bundle : bundles) {
            bundle.reload();
        }
        return bundles.size();
    }
}
