package de.schiggy.worth;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Names;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** Preisliste: alle Items mit Preis, seitenweise. Alle Texte stehen in der messages.yml. */
final class WorthGui {

    private static final int PER_PAGE = 45;

    /** Sortierung der Liste. */
    enum Sort {
        NAME, WORTH_DESC, WORTH_ASC;

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final java.util.Map<java.util.UUID, Sort> sorts = new java.util.concurrent.ConcurrentHashMap<>();

    private final SchiggyWorth plugin;

    WorthGui(SchiggyWorth plugin) {
        this.plugin = plugin;
    }

    /** Muss auf dem Thread des Spielers laufen. */
    void open(Player player, int requestedPage) {
        MessageBundle msg = plugin.messages();
        EconomyService economy = SchiggyEconomy.getInstance().economy();
        List<Map.Entry<Material, Long>> entries = new java.util.ArrayList<>(plugin.service().sorted());
        Sort sort = sorts.getOrDefault(player.getUniqueId(), Sort.NAME);
        switch (sort) {
            case WORTH_DESC -> entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            case WORTH_ASC -> entries.sort((a, b) -> Long.compare(a.getValue(), b.getValue()));
            default -> { }
        }
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        final int page = Math.max(0, Math.min(requestedPage, pages - 1));

        SchiggyGui gui = new SchiggyGui(msg.component("gui-worth-title",
                "page", String.valueOf(page + 1), "pages", String.valueOf(pages)), 6);

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= entries.size()) {
                break;
            }
            Map.Entry<Material, Long> entry = entries.get(index);
            Material material = entry.getKey();
            long price = entry.getValue();
            String[] placeholders = {
                    "item", Names.pretty(material),
                    "price", economy.format(Currency.MONEY, price),
                    "stack", economy.format(Currency.MONEY, price * material.getMaxStackSize())
            };
            gui.set(i, ItemBuilder.of(material)
                    .name(msg.raw("gui-worth-item-name", placeholders))
                    .lore(msg.rawList("gui-worth-item-lore", placeholders))
                    .build());
        }

        gui.set(48, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE).name(msg.raw("gui-worth-prev-name")).build(),
                event -> {
                    if (page > 0) {
                        open(player, page - 1);
                    }
                });
        gui.set(50, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE).name(msg.raw("gui-worth-next-name")).build(),
                event -> {
                    if (page < pages - 1) {
                        open(player, page + 1);
                    }
                });
        gui.set(53, ItemBuilder.of(Material.OAK_SIGN)
                .name(msg.raw("gui-worth-info-name"))
                .lore(msg.rawList("gui-worth-info-lore", "page", String.valueOf(page + 1),
                        "pages", String.valueOf(pages), "count", String.valueOf(entries.size())))
                .build());
        gui.set(46, ItemBuilder.of(Material.CAULDRON).name(msg.raw("gui-worth-sort-name"))
                .lore(msg.rawList("gui-worth-sort-lore", "sort", msg.raw("worth-sort-" + sort.name()))).build(),
                event -> {
                    sorts.put(player.getUniqueId(), sort.next());
                    open(player, 0);
                });
        gui.open(player);
    }
}
