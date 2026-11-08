package de.schiggy.keydrop;

import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Names;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.keydrop.KeydropService.HistoryEntry;
import de.schiggy.keydrop.KeydropService.Preset;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admin-Menue: Presets starten, laufenden Keydrop stoppen, Status und Verlauf ansehen. */
final class KeydropGui {

    private static final int[] PRESET_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private final SchiggyKeydrop plugin;

    KeydropGui(SchiggyKeydrop plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    void open(Player player) {
        int size = plugin.getConfig().getInt("history-size", 8);
        plugin.service().history(size).thenAccept(history ->
                SchiggyScheduler.entity(plugin, player, () -> render(player, history)));
    }

    private void render(Player player, List<HistoryEntry> history) {
        KeydropService service = plugin.service();
        SchiggyGui gui = new SchiggyGui(msg().component("gui-title"), 3);

        int index = 0;
        for (Preset preset : service.presets().values()) {
            if (index >= PRESET_SLOTS.length) {
                break;
            }
            List<String> lore = new ArrayList<>(msg().rawList("gui-preset-lore"));
            for (Map.Entry<String, Integer> key : preset.keys().entrySet()) {
                lore.add(msg().raw("announce-key-line", "amount", String.valueOf(key.getValue()),
                        "crate", service.crateNamePlain(key.getKey())));
            }
            lore.addAll(msg().rawList("gui-preset-hint"));
            gui.set(PRESET_SLOTS[index++], ItemBuilder.of(Material.NETHER_STAR)
                    .name(msg().raw("gui-preset-name").replace("{name}", preset.name()))
                    .lore(lore).build(), event -> {
                        player.closeInventory();
                        switch (service.start(preset.id(), player.getName())) {
                            case STARTED -> msg().send(player, "started", "preset",
                                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(preset.name()));
                            case ALREADY_RUNNING -> msg().send(player, "already-running");
                            default -> msg().send(player, "no-presets");
                        }
                    });
        }

        gui.set(20, ItemBuilder.of(Material.RED_CONCRETE).name(msg().raw("gui-stop-name"))
                .lore(msg().rawList("gui-stop-lore")).build(), event -> {
                    player.closeInventory();
                    msg().send(player, service.stop(player.getName()) ? "stopped-admin" : "not-running");
                });

        List<String> info = new ArrayList<>();
        if (service.isRunning()) {
            info.add(msg().raw("info-running", "preset", service.runningPresetName(),
                    "seconds", String.valueOf(service.remainingSeconds())));
        } else {
            info.add(msg().raw("info-idle"));
        }
        info.add(service.autoEnabled()
                ? msg().raw("info-next-auto", "time", Names.duration(service.nextAutoMillis()))
                : msg().raw("info-auto-off"));
        gui.set(22, ItemBuilder.of(Material.CLOCK).name(msg().raw("gui-info-name")).lore(info).build());

        List<String> lines = new ArrayList<>();
        for (HistoryEntry entry : history) {
            Preset preset = service.presets().get(entry.preset());
            String name = preset == null ? entry.preset() : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(preset.name());
            lines.add(msg().raw("gui-history-line", "preset", name, "players", String.valueOf(entry.players()),
                    "time", Names.duration(System.currentTimeMillis() - entry.time()),
                    "status", msg().raw("status-" + entry.status())));
        }
        if (lines.isEmpty()) {
            lines.add(msg().raw("gui-history-empty"));
        }
        gui.set(24, ItemBuilder.of(Material.BOOK).name(msg().raw("gui-history-name")).lore(lines).build());
        gui.open(player);
    }
}
