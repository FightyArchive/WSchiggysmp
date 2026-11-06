package de.schiggy.crates;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Inventories;
import de.schiggy.crates.CrateService.Crate;
import de.schiggy.crates.CrateService.Reward;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Auswahl-Crate wie auf DonutSMP: Belohnungen ansehen, eine waehlen, bestaetigen. Erst bei der Bestaetigung wird
 * ein Schluessel verbraucht. Mit mehreren Schluesseln kann die gewaehlte Belohnung mehrfach eingeloest werden.
 */
final class CrateGui {

    private static final int ITEMS_PER_ROW = 7;
    private static final int MAX_ITEM_ROWS = 4;

    private final SchiggyCrates plugin;

    CrateGui(SchiggyCrates plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private CrateService service() {
        return plugin.service();
    }

    // ------------------------------------------------------------------ Oeffnen und Vorschau

    /** Rechtsklick auf die Crate. Muss auf dem Thread des Spielers laufen. */
    void open(Player player, Crate crate) {
        service().keys(player.getUniqueId(), crate.id()).thenAccept(keys ->
                SchiggyScheduler.entity(plugin, player, () -> {
                    if (keys <= 0) {
                        msg().send(player, "no-key", "crate", crate.name());
                        if (plugin.getConfig().getBoolean("settings.open-without-key-preview", true)) {
                            showRewards(player, crate, crate.rewards(), keys, true);
                        }
                        return;
                    }
                    showRewards(player, crate, service().offer(crate), keys, false);
                }));
    }

    /** Linksklick auf die Crate oder /crate preview. */
    void preview(Player player, Crate crate) {
        service().keys(player.getUniqueId(), crate.id()).thenAccept(keys ->
                SchiggyScheduler.entity(plugin, player, () -> showRewards(player, crate, crate.rewards(), keys, true)));
    }

    private void showRewards(Player player, Crate crate, List<Reward> offered, int keys, boolean previewOnly) {
        int count = Math.min(offered.size(), ITEMS_PER_ROW * MAX_ITEM_ROWS);
        int itemRows = (count + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
        int rows = Math.min(6, Math.max(3, itemRows + 2));

        SchiggyGui gui = new SchiggyGui(msg().component(previewOnly ? "gui-preview-title" : "gui-choose-title",
                "crate", crate.name()), rows);
        for (int i = 0; i < count; i++) {
            Reward reward = offered.get(i);
            int slot = (1 + i / ITEMS_PER_ROW) * 9 + 1 + (i % ITEMS_PER_ROW);
            ItemStack item = ItemBuilder.appendLore(reward.display(),
                    msg().rawList(previewOnly ? "gui-preview-lore" : "gui-choose-lore"));
            if (previewOnly) {
                gui.set(slot, item);
            } else {
                gui.set(slot, item, event -> confirm(player, crate, reward, offered));
            }
        }
        gui.set(rows * 9 - 5, ItemBuilder.of(Material.TRIPWIRE_HOOK)
                .name(msg().raw("gui-keys-name"))
                .lore(msg().rawList("gui-keys-lore", "keys", String.valueOf(keys)))
                .build());
        gui.open(player);
    }

    // ------------------------------------------------------------------ Bestaetigen

    private void confirm(Player player, Crate crate, Reward reward, List<Reward> offered) {
        service().keys(player.getUniqueId(), crate.id()).thenAccept(keys ->
                SchiggyScheduler.entity(plugin, player, () -> {
                    if (keys <= 0) {
                        player.closeInventory();
                        msg().send(player, "no-key", "crate", crate.name());
                        return;
                    }
                    SchiggyGui gui = new SchiggyGui(msg().component("gui-confirm-title"), 3);
                    gui.set(13, ItemBuilder.appendLore(reward.display(), List.of()));
                    gui.set(11, ItemBuilder.of(Material.LIME_CONCRETE)
                            .name(msg().raw("gui-confirm-name"))
                            .lore(msg().rawList("gui-confirm-lore", "reward", reward.plainName()))
                            .build(), event -> claim(player, crate, reward, 1));
                    gui.set(15, ItemBuilder.of(Material.RED_CONCRETE)
                            .name(msg().raw("gui-cancel-name"))
                            .lore(msg().rawList("gui-cancel-lore"))
                            .build(), event -> showRewards(player, crate, offered, keys, false));
                    int all = Math.min(keys, Math.max(1, plugin.getConfig().getInt("settings.max-claim-all", 36)));
                    if (all > 1) {
                        gui.set(22, ItemBuilder.of(Material.GOLD_BLOCK)
                                .name(msg().raw("gui-all-name"))
                                .lore(msg().rawList("gui-all-lore", "count", String.valueOf(all)))
                                .build(), event -> claim(player, crate, reward, all));
                    }
                    gui.open(player);
                }));
    }

    // ------------------------------------------------------------------ Einloesen

    /** Loest die Belohnung count-mal ein, solange Schluessel und Platz da sind. */
    private void claim(Player player, Crate crate, Reward reward, int count) {
        UUID uuid = player.getUniqueId();
        if (!service().processing().add(uuid)) {
            return;
        }
        player.closeInventory();
        claimNext(player, crate, reward, count, 0);
    }

    private void claimNext(Player player, Crate crate, Reward reward, int count, int done) {
        if (done >= count) {
            finish(player, crate, reward, done, false);
            return;
        }
        if (reward.give() != null && !Inventories.fits(player, reward.give())) {
            finish(player, crate, reward, done, true);
            return;
        }
        service().consumeKey(player.getUniqueId(), crate.id()).thenAccept(ok ->
                SchiggyScheduler.entity(plugin, player, () -> {
                    if (!ok) {
                        finish(player, crate, reward, done, false);
                        return;
                    }
                    deliver(player, crate, reward);
                    claimNext(player, crate, reward, count, done + 1);
                }));
    }

    private void finish(Player player, Crate crate, Reward reward, int done, boolean inventoryFull) {
        service().processing().remove(player.getUniqueId());
        if (done == 0) {
            msg().send(player, inventoryFull ? "inventory-full" : "no-key", "crate", crate.name());
            return;
        }
        if (inventoryFull) {
            msg().send(player, "inventory-full", "crate", crate.name());
        }
        msg().send(player, "claimed", "amount", String.valueOf(done), "reward", reward.plainName(), "crate", crate.name());
        if (reward.broadcast()) {
            var message = msg().component("broadcast", "player", player.getName(),
                    "crate", crate.name(), "reward", done > 1 ? done + "x " + reward.plainName() : reward.plainName());
            // an jeden Spieler ueber dessen eigenen Thread senden (Folia)
            for (Player online : Bukkit.getOnlinePlayers()) {
                SchiggyScheduler.entity(plugin, online, () -> online.sendMessage(message));
            }
        }
    }

    /** Gibt eine Belohnung. Muss auf dem Thread des Spielers laufen. */
    private void deliver(Player player, Crate crate, Reward reward) {
        if (reward.give() != null) {
            Inventories.give(player, reward.give());
        }
        for (String template : reward.commands()) {
            String command = template.replace("{player}", player.getName());
            SchiggyScheduler.global(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
        service().log(player.getUniqueId(), player.getName(), crate.id(), reward.plainName());
    }
}
