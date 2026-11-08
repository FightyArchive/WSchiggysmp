package de.schiggy.auction;

import de.schiggy.auction.AuctionService.Listing;
import de.schiggy.auction.AuctionService.Page;
import de.schiggy.auction.AuctionService.Sort;
import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Inventories;
import de.schiggy.core.util.Names;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auktionshaus-Menue nach dem Vorbild: oben 45 Auktionen, unten die Leiste mit Kiste (Deine Auktionen),
 * Kessel (Sortierung), roter/gruener Scheibe (Seiten), Fernrohr (Suche), Smaragd (Verkaufen) und Schild (Hilfe).
 */
final class AuctionGui {

    static final class ViewState {
        volatile Sort sort = Sort.NEWEST;
        volatile String search;
    }

    private final SchiggyAuction plugin;
    private final Map<UUID, ViewState> states = new ConcurrentHashMap<>();

    AuctionGui(SchiggyAuction plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    ViewState state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> new ViewState());
    }

    void forget(UUID uuid) {
        states.remove(uuid);
    }

    // ------------------------------------------------------------------ Uebersicht

    void open(Player player, int page) {
        ViewState state = state(player);
        plugin.service().browse(state.search, state.sort, page).thenAccept(result ->
                SchiggyScheduler.entity(plugin, player, () -> render(player, state, page, result)));
    }

    private void render(Player player, ViewState state, int requestedPage, Page result) {
        int pages = Math.max(1, (result.total() + AuctionService.PAGE_SIZE - 1) / AuctionService.PAGE_SIZE);
        if (requestedPage > pages - 1) {
            open(player, pages - 1);
            return;
        }
        final int page = Math.max(0, requestedPage);

        SchiggyGui gui = new SchiggyGui(msg().component("gui-auction-title",
                "page", String.valueOf(page + 1), "pages", String.valueOf(pages)), 6);

        List<Listing> listings = result.listings();
        for (int i = 0; i < listings.size() && i < AuctionService.PAGE_SIZE; i++) {
            Listing listing = listings.get(i);
            gui.set(i, display(listing, "gui-listing-lore"), event -> {
                if (listing.seller().equals(player.getUniqueId())) {
                    msg().send(player, "own-listing");
                } else {
                    openConfirm(player, listing, page);
                }
            });
        }

        gui.set(45, ItemBuilder.of(Material.CHEST).name(msg().raw("gui-mine-name"))
                .lore(msg().rawList("gui-mine-lore")).build(), event -> openMine(player));
        gui.set(46, ItemBuilder.of(Material.CAULDRON).name(msg().raw("gui-sort-name"))
                .lore(msg().rawList("gui-sort-lore", "sort", msg().raw("sort-" + state.sort.name()))).build(),
                event -> {
                    state.sort = state.sort.next();
                    open(player, 0);
                });
        gui.set(48, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE).name(msg().raw("gui-prev-name")).build(),
                event -> {
                    if (page > 0) {
                        open(player, page - 1);
                    }
                });
        String search = state.search == null || state.search.isBlank() ? msg().raw("gui-search-none") : state.search;
        gui.set(49, ItemBuilder.of(Material.SPYGLASS).name(msg().raw("gui-search-name"))
                .lore(msg().rawList("gui-search-lore", "search", search)).build(), event -> {
                    if (event.getClick().isRightClick()) {
                        state.search = null;
                        open(player, 0);
                    } else {
                        player.closeInventory();
                        msg().send(player, "search-prompt");
                        de.schiggy.core.util.ChatPrompt.ask(plugin, player, text -> {
                            if (text != null) {
                                state.search = text;
                            }
                            open(player, 0);
                        });
                    }
                });
        gui.set(50, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE).name(msg().raw("gui-next-name")).build(),
                event -> {
                    if (page < pages - 1) {
                        open(player, page + 1);
                    }
                });
        gui.set(52, ItemBuilder.of(Material.EMERALD).name(msg().raw("gui-sell-name"))
                .lore(msg().rawList("gui-sell-lore")).build());
        gui.set(53, ItemBuilder.of(Material.OAK_SIGN).name(msg().raw("gui-help-name"))
                .lore(msg().rawList("gui-help-lore",
                        "duration", plugin.getConfig().getLong("duration-hours", 48) + "h",
                        "fee", String.valueOf(plugin.getConfig().getDouble("sale-fee-percent", 5)),
                        "limit", String.valueOf(plugin.service().slotsFor(player)))).build());
        gui.open(player);
    }

    private ItemStack display(Listing listing, String loreKey) {
        long left = listing.expires() - System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.addAll(msg().rawList(loreKey,
                "seller", listing.sellerName(),
                "price", economy().format(Currency.MONEY, listing.price()),
                "time", Names.duration(left)));
        return ItemBuilder.appendLore(listing.item(), lines);
    }

    // ------------------------------------------------------------------ Kauf bestaetigen

    private void openConfirm(Player player, Listing listing, int page) {
        SchiggyGui gui = new SchiggyGui(msg().component("gui-auction-confirm-title"), 3);
        gui.set(13, display(listing, "gui-listing-lore"));
        gui.set(11, ItemBuilder.of(Material.LIME_CONCRETE).name(msg().raw("gui-confirm-name"))
                .lore(msg().rawList("gui-confirm-lore", "price", economy().format(Currency.MONEY, listing.price())))
                .build(), event -> {
                    player.closeInventory();
                    plugin.service().purchase(player, listing);
                });
        gui.set(15, ItemBuilder.of(Material.RED_CONCRETE).name(msg().raw("gui-cancel-name")).build(),
                event -> open(player, page));
        gui.open(player);
    }

    // ------------------------------------------------------------------ Deine Auktionen

    void openMine(Player player) {
        plugin.service().mine(player.getUniqueId()).thenAccept(list ->
                SchiggyScheduler.entity(plugin, player, () -> renderMine(player, list)));
    }

    private void renderMine(Player player, List<Listing> listings) {
        SchiggyGui gui = new SchiggyGui(msg().component("gui-auction-mine-title"), 6);
        UUID uuid = player.getUniqueId();
        for (int i = 0; i < listings.size() && i < AuctionService.PAGE_SIZE; i++) {
            Listing listing = listings.get(i);
            boolean active = listing.status().equals("ACTIVE");
            String key = active ? "gui-mine-active-lore"
                    : listing.status().equals("CANCELLED") ? "gui-mine-cancelled-lore" : "gui-mine-expired-lore";
            gui.set(i, display(listing, key), event -> {
                if (active) {
                    plugin.service().cancel(uuid, listing.id()).thenAccept(ok -> SchiggyScheduler.entity(plugin, player, () -> {
                        msg().send(player, ok ? "cancelled" : "cancel-failed");
                        openMine(player);
                    }));
                } else {
                    claim(player, listing);
                }
            });
        }
        gui.set(45, ItemBuilder.of(Material.ARROW).name(msg().raw("gui-back-name")).build(), event -> open(player, 0));
        gui.open(player);
    }

    private void claim(Player player, Listing listing) {
        if (!Inventories.fits(player, listing.item())) {
            msg().send(player, "inventory-full");
            return;
        }
        plugin.service().claim(player.getUniqueId(), listing.id()).thenAccept(item ->
                SchiggyScheduler.entity(plugin, player, () -> {
                    if (item.isPresent()) {
                        Inventories.give(player, item.get());
                        msg().send(player, "claimed");
                    } else {
                        msg().send(player, "claim-failed");
                    }
                    openMine(player);
                }));
    }
}
