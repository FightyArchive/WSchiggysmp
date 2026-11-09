package de.schiggy.settings;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Einstellungsmenue wie auf DonutSMP: oben das Symbol, darunter ein gruener (AN) oder grauer (AUS) Farbstoff.
 * Alle Texte kommen aus der messages.yml.
 */
final class SettingsGui {

    private final SchiggySettings plugin;

    SettingsGui(SchiggySettings plugin) {
        this.plugin = plugin;
    }

    void open(Player player) {
        SchiggyGui gui = new SchiggyGui(plugin.messages().component("gui-title"), 5);
        render(gui, player);
        gui.open(player);
    }

    private void render(SchiggyGui gui, Player player) {
        SettingsService service = plugin.service();
        MessageBundle msg = plugin.messages();
        UUID uuid = player.getUniqueId();
        Setting[] settings = Setting.values();

        for (int i = 0; i < settings.length && i < 18; i++) {
            int iconSlot = (i < 9 ? 9 : 27) + (i % 9);
            final Setting setting = settings[i];
            boolean on = service.isEnabled(uuid, setting);

            List<String> lore = new ArrayList<>();
            lore.add(msg.raw("setting-" + setting.key() + "-desc"));
            lore.add("");
            lore.add(msg.raw(on ? "gui-status-on" : "gui-status-off"));
            lore.add(msg.raw("gui-hint"));

            ItemStack icon = ItemBuilder.of(setting.icon())
                    .name(msg.raw("setting-" + setting.key() + "-name"))
                    .lore(lore)
                    .build();
            ItemStack indicator = ItemBuilder.of(on ? Material.LIME_DYE : Material.GRAY_DYE)
                    .name(msg.raw(on ? "gui-on" : "gui-off"))
                    .build();

            Consumer<InventoryClickEvent> toggle = event -> {
                service.toggle(uuid, setting);
                if (service.isEnabled(uuid, Setting.SOUNDS)) {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                }
                render(gui, player);
            };
            gui.set(iconSlot, icon, toggle);
            gui.set(iconSlot + 9, indicator, toggle);
        }
    }
}
