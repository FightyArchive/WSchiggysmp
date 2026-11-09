package de.schiggy.kits;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Names;
import de.schiggy.kits.KitService.Kit;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kit-Menue nach dem Vorbild: die Kits stehen in Reihe 2 (je 5 pro Reihe, mittig), unten rechts liegt das Buch mit
 * der Erklaerung. Linksklick holt das Kit ab, Rechtsklick zeigt den Inhalt.
 */
final class KitGui {

    private final SchiggyKits plugin;

    KitGui(SchiggyKits plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    void open(Player player) {
        List<Kit> kits = plugin.service().all();
        int rows = Math.max(3, Math.min(6, 2 + (kits.size() + 4) / 5));
        int capacity = (rows - 2) * 5;
        SchiggyGui gui = new SchiggyGui(msg().component("gui-kits-title"), rows);
        UUID uuid = player.getUniqueId();

        for (int i = 0; i < kits.size() && i < capacity; i++) {
            Kit kit = kits.get(i);
            int slot = (1 + i / 5) * 9 + 2 + (i % 5);
            boolean access = plugin.hasAccess(player, kit);
            long remaining = plugin.service().remainingMillis(uuid, kit);

            List<String> lore = new ArrayList<>(msg().rawList("gui-kit-lore",
                    "items", String.valueOf(kit.items().size()),
                    "cooldown", plugin.cooldownFor(player, kit) <= 0 ? "-" : Names.duration(plugin.cooldownFor(player, kit) * 1000L)));
            if (!access) {
                lore.add(msg().raw("gui-kit-line-locked"));
            } else if (remaining > 0) {
                lore.add(msg().raw("gui-kit-line-cooldown", "time", Names.duration(remaining)));
            } else {
                lore.add(msg().raw("gui-kit-line-ready"));
            }
            lore.addAll(msg().rawList("gui-kit-hint"));

            gui.set(slot, ItemBuilder.of(kit.icon()).name(kit.display()).lore(lore).build(), event -> {
                if (event.getClick().isRightClick()) {
                    preview(player, kit);
                } else {
                    plugin.claim(player, kit, false);
                    // Menue nach dem Abholen aktualisieren
                    SchiggyScheduler.entity(plugin, player, () -> {
                        if (player.getOpenInventory().getTopInventory().getHolder() instanceof SchiggyGui) {
                            open(player);
                        }
                    });
                }
            });
        }
        gui.set(rows * 9 - 1, ItemBuilder.of(Material.BOOK).name(msg().raw("gui-kits-info-name"))
                .lore(msg().rawList("gui-kits-info-lore")).build());
        gui.open(player);
    }

    /** Zeigt den Inhalt eines Kits. */
    void preview(Player player, Kit kit) {
        List<ItemStack> items = kit.items();
        int shown = Math.min(45, items.size());
        int rows = Math.max(2, (shown + 8) / 9 + 1);
        SchiggyGui gui = new SchiggyGui(msg().component("gui-kit-preview-title",
                "kit", MiniMessage.miniMessage().stripTags(kit.display())), rows);
        for (int i = 0; i < shown; i++) {
            gui.set(i, items.get(i).clone());
        }
        gui.set(rows * 9 - 1, ItemBuilder.of(Material.ARROW).name(msg().raw("gui-back-name")).build(),
                event -> open(player));
        gui.open(player);
    }
}
