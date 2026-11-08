package de.schiggy.orders;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.ChatPrompt;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Inventories;
import de.schiggy.core.util.Names;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.orders.OrdersService.Order;
import de.schiggy.orders.OrdersService.Page;
import de.schiggy.orders.OrdersService.Sort;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orders-Menue nach dem Vorbild: oben 45 Orders, unten die Leiste mit Kiste (Deine Orders), Kessel (Sortierung),
 * roter/gruener Scheibe (Seiten), Fernrohr (Suche), Trichter (Order erstellen) und Schild (Hilfe).
 * Order erstellen: Item in einer Liste aller Items waehlen, dann Menge und Preis einstellen.
 */
final class OrdersGui {

    static final class ViewState {
        volatile Sort sort = Sort.NEWEST;
        volatile String search;
        volatile String pickerSearch;
    }

    /** Zustand des Erstellen-Menues. */
    static final class CreateState {
        Material material;
        int amount;
        long unit;
    }

    /** Items, die man nicht bestellen kann (nur Kreativmodus oder ohne Eigenschaften nicht lieferbar). */
    private static final Set<String> EXCLUDED = Set.of("BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_BLOCK", "STRUCTURE_VOID",
            "JIGSAW", "DEBUG_STICK", "KNOWLEDGE_BOOK", "SPAWNER", "TRIAL_SPAWNER", "VAULT", "END_PORTAL_FRAME",
            "REINFORCED_DEEPSLATE", "BUDDING_AMETHYST", "PETRIFIED_OAK_SLAB", "FARMLAND", "CHORUS_PLANT", "WRITTEN_BOOK",
            "FILLED_MAP", "PLAYER_HEAD", "POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW", "ENCHANTED_BOOK",
            "SUSPICIOUS_STEW", "OMINOUS_BOTTLE", "GOAT_HORN", "FIREWORK_STAR", "TEST_BLOCK", "TEST_INSTANCE_BLOCK");

    private final SchiggyOrders plugin;
    private final Map<UUID, ViewState> states = new ConcurrentHashMap<>();
    private volatile List<Material> selectable;

    OrdersGui(SchiggyOrders plugin) {
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

    /** Baut die Item-Liste nach einem Config-Reload neu. */
    void reloadItems() {
        selectable = null;
    }

    /** Fragt im Chat nach einem Suchbegriff und gibt ihn an den Callback (null = abgebrochen). */
    private void promptSearch(Player player, java.util.function.Consumer<String> callback) {
        player.closeInventory();
        msg().send(player, "search-prompt");
        ChatPrompt.ask(plugin, player, callback);
    }

    // ------------------------------------------------------------------ Uebersicht

    void open(Player player, int page) {
        ViewState state = state(player);
        plugin.service().browse(state.search, state.sort, page).thenAccept(result ->
                SchiggyScheduler.entity(plugin, player, () -> render(player, state, page, result)));
    }

    private void render(Player player, ViewState state, int requestedPage, Page result) {
        int pages = Math.max(1, (result.total() + OrdersService.PAGE_SIZE - 1) / OrdersService.PAGE_SIZE);
        if (requestedPage > pages - 1) {
            open(player, pages - 1);
            return;
        }
        final int page = Math.max(0, requestedPage);

        SchiggyGui gui = new SchiggyGui(msg().component("gui-orders-title",
                "page", String.valueOf(page + 1), "pages", String.valueOf(pages)), 6);

        List<Order> orders = result.orders();
        for (int i = 0; i < orders.size() && i < OrdersService.PAGE_SIZE; i++) {
            Order order = orders.get(i);
            long value = order.unitPrice() * order.remaining();
            String[] placeholders = {
                    "item", Names.pretty(order.material()),
                    "remaining", String.valueOf(order.remaining()),
                    "total", String.valueOf(order.total()),
                    "unit", economy().format(Currency.MONEY, order.unitPrice()),
                    "value", economy().format(Currency.MONEY, value),
                    "buyer", order.buyerName(),
                    "time", Names.duration(order.expires() - System.currentTimeMillis()),
                    "have", String.valueOf(Inventories.countPlain(player, order.material()))
            };
            gui.set(i, ItemBuilder.of(order.material()).amount(Math.min(64, order.remaining()))
                    .name(msg().raw("gui-order-name", placeholders))
                    .lore(msg().rawList("gui-order-lore", placeholders))
                    .build(), event -> {
                        player.closeInventory();
                        plugin.service().fulfill(player, order);
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
                        promptSearch(player, text -> {
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
        gui.set(52, ItemBuilder.of(Material.HOPPER).name(msg().raw("gui-create-name"))
                .lore(msg().rawList("gui-create-lore")).build(), event -> openPicker(player, 0, null));
        gui.set(53, ItemBuilder.of(Material.OAK_SIGN).name(msg().raw("gui-help-name"))
                .lore(msg().rawList("gui-help-lore",
                        "duration", plugin.getConfig().getLong("duration-hours", 72) + "h",
                        "fee", String.valueOf(plugin.getConfig().getDouble("fee-percent", 5)))).build());
        gui.open(player);
    }

    // ------------------------------------------------------------------ Deine Orders

    void openMine(Player player) {
        plugin.service().mine(player.getUniqueId()).thenAccept(list ->
                SchiggyScheduler.entity(plugin, player, () -> renderMine(player, list)));
    }

    private void renderMine(Player player, List<Order> orders) {
        SchiggyGui gui = new SchiggyGui(msg().component("gui-orders-mine-title"), 6);
        for (int i = 0; i < orders.size() && i < OrdersService.PAGE_SIZE; i++) {
            Order order = orders.get(i);
            boolean active = order.status().equals("ACTIVE");
            long refund = order.unitPrice() * order.remaining();
            List<String> lore = new ArrayList<>(msg().rawList("gui-mine-order-lore",
                    "filled", String.valueOf(order.filled()),
                    "total", String.valueOf(order.total()),
                    "unit", economy().format(Currency.MONEY, order.unitPrice()),
                    "status", msg().raw("status-" + order.status()),
                    "claimable", String.valueOf(order.claimable())));
            lore.add("");
            if (order.claimable() > 0) {
                lore.add(msg().raw("gui-mine-claim-line"));
            }
            if (active) {
                lore.add(msg().raw("gui-mine-cancel-line", "refund", economy().format(Currency.MONEY, refund)));
            }
            gui.set(i, ItemBuilder.of(order.material()).amount(Math.max(1, Math.min(64, order.total())))
                    .name(msg().raw("gui-order-name", "item", Names.pretty(order.material())))
                    .lore(lore).build(), event -> {
                        if (event.getClick().isRightClick()) {
                            if (active) {
                                plugin.service().cancel(player, order, () -> openMine(player));
                            }
                        } else if (order.claimable() > 0) {
                            plugin.service().claim(player, order, () -> openMine(player));
                        }
                    });
        }
        gui.set(45, ItemBuilder.of(Material.ARROW).name(msg().raw("gui-back-name")).build(), event -> open(player, 0));
        gui.open(player);
    }

    // ------------------------------------------------------------------ Item auswaehlen

    private boolean isSelectable(Material material, Set<String> blacklist) {
        String name = material.name();
        if (name.startsWith("LEGACY_") || !material.isItem() || material.isAir()) {
            return false;
        }
        if (blacklist.contains(name) || EXCLUDED.contains(name)) {
            return false;
        }
        return !(name.endsWith("_SPAWN_EGG") || name.startsWith("INFESTED_") || name.contains("COMMAND_BLOCK"));
    }

    /** Alle bestellbaren Items, alphabetisch nach Anzeigename. */
    private List<Material> selectableItems() {
        List<Material> list = selectable;
        if (list == null) {
            Set<String> blacklist = new HashSet<>(plugin.getConfig().getStringList("blacklist"));
            List<Material> built = new ArrayList<>();
            for (Material material : Material.values()) {
                if (isSelectable(material, blacklist)) {
                    built.add(material);
                }
            }
            built.sort(Comparator.comparing(Names::pretty));
            selectable = built;
            list = built;
        }
        return list;
    }

    private CreateState defaultState(Material material) {
        CreateState state = new CreateState();
        state.material = material;
        state.amount = Math.min(64, Math.max(1, plugin.getConfig().getInt("max-amount", 2304)));
        state.unit = Currency.MONEY.toMinor(Math.max(1.0, plugin.getConfig().getDouble("min-unit-price", 0.5)));
        return state;
    }

    /**
     * Liste aller Items zum Auswaehlen (mit Suche ueber den Chat). carry != null: ein bestehendes Erstellen-Menue,
     * in dem nur das Item getauscht wird.
     */
    void openPicker(Player player, int requestedPage, CreateState carry) {
        ViewState state = state(player);
        String search = state.pickerSearch == null ? "" : state.pickerSearch.trim().toLowerCase(Locale.ROOT);
        List<Material> items = new ArrayList<>();
        for (Material material : selectableItems()) {
            if (search.isEmpty() || Names.pretty(material).toLowerCase(Locale.ROOT).contains(search)
                    || material.name().toLowerCase(Locale.ROOT).contains(search.replace(' ', '_'))) {
                items.add(material);
            }
        }
        int pages = Math.max(1, (items.size() + 44) / 45);
        final int page = Math.max(0, Math.min(requestedPage, pages - 1));

        SchiggyGui gui = new SchiggyGui(msg().component("gui-pick-title",
                "page", String.valueOf(page + 1), "pages", String.valueOf(pages)), 6);
        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= items.size()) {
                break;
            }
            Material material = items.get(index);
            gui.set(i, ItemBuilder.of(material)
                    .name(msg().raw("gui-pick-name", "item", Names.pretty(material)))
                    .lore(msg().rawList("gui-pick-lore"))
                    .build(), event -> pick(player, material, carry));
        }

        gui.set(45, ItemBuilder.of(Material.ARROW).name(msg().raw("gui-back-name")).build(), event -> {
            if (carry != null) {
                openCreate(player, carry);
            } else {
                open(player, 0);
            }
        });
        Material inHand = player.getInventory().getItemInMainHand().getType();
        if (!inHand.isAir() && isSelectable(inHand, new HashSet<>(plugin.getConfig().getStringList("blacklist")))) {
            gui.set(46, ItemBuilder.of(inHand).name(msg().raw("gui-pick-hand-name"))
                    .lore(msg().rawList("gui-pick-hand-lore")).build(), event -> pick(player, inHand, carry));
        } else {
            gui.set(46, ItemBuilder.of(Material.BARRIER).name(msg().raw("gui-pick-hand-empty")).build());
        }
        gui.set(48, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE).name(msg().raw("gui-prev-name")).build(),
                event -> {
                    if (page > 0) {
                        openPicker(player, page - 1, carry);
                    }
                });
        String shown = state.pickerSearch == null || state.pickerSearch.isBlank()
                ? msg().raw("gui-search-none") : state.pickerSearch;
        gui.set(49, ItemBuilder.of(Material.SPYGLASS).name(msg().raw("gui-search-name"))
                .lore(msg().rawList("gui-search-lore", "search", shown)).build(), event -> {
                    if (event.getClick().isRightClick()) {
                        state.pickerSearch = null;
                        openPicker(player, 0, carry);
                    } else {
                        promptSearch(player, text -> {
                            if (text != null) {
                                state.pickerSearch = text;
                            }
                            openPicker(player, 0, carry);
                        });
                    }
                });
        gui.set(50, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE).name(msg().raw("gui-next-name")).build(),
                event -> {
                    if (page < pages - 1) {
                        openPicker(player, page + 1, carry);
                    }
                });
        gui.set(53, ItemBuilder.of(Material.OAK_SIGN).name(msg().raw("gui-pick-help-name"))
                .lore(msg().rawList("gui-pick-help-lore")).build());
        gui.open(player);
    }

    private void pick(Player player, Material material, CreateState carry) {
        if (carry != null) {
            carry.material = material;
            openCreate(player, carry);
        } else {
            openCreate(player, defaultState(material));
        }
    }

    // ------------------------------------------------------------------ Order erstellen (Menue)

    /** Oeffnet das Erstellen-Menue. Ohne Zustand wird das Item aus der Hand genommen. */
    void openCreate(Player player, CreateState existing) {
        CreateState state = existing;
        if (state == null) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                msg().send(player, "create-hold-item");
                return;
            }
            if (plugin.getConfig().getStringList("blacklist").contains(hand.getType().name())) {
                msg().send(player, "create-blacklisted");
                return;
            }
            state = defaultState(hand.getType());
        }
        SchiggyGui gui = new SchiggyGui(msg().component("gui-create-title"), 5);
        renderCreate(gui, player, state);
        gui.open(player);
    }

    private void renderCreate(SchiggyGui gui, Player player, CreateState state) {
        int maxAmount = Math.max(1, plugin.getConfig().getInt("max-amount", 2304));
        long minUnit = Currency.MONEY.toMinor(plugin.getConfig().getDouble("min-unit-price", 0.5));
        long maxUnit = Currency.MONEY.toMinor(plugin.getConfig().getDouble("max-unit-price", 1_000_000));
        state.amount = Math.max(1, Math.min(maxAmount, state.amount));
        state.unit = Math.max(minUnit, Math.min(maxUnit, state.unit));
        long total;
        try {
            total = Math.multiplyExact(state.unit, (long) state.amount);
        } catch (ArithmeticException e) {
            total = Long.MAX_VALUE;
        }
        String[] placeholders = {
                "item", Names.pretty(state.material),
                "amount", String.valueOf(state.amount),
                "unit", economy().format(Currency.MONEY, state.unit),
                "total", economy().format(Currency.MONEY, total)
        };
        Runnable rerender = () -> renderCreate(gui, player, state);

        gui.set(13, ItemBuilder.of(state.material).amount(Math.min(64, state.amount))
                .name(msg().raw("gui-create-item-name", placeholders))
                .lore(msg().rawList("gui-create-item-lore", placeholders))
                .build(), event -> openPicker(player, 0, state));

        // Menge
        adjust(gui, 19, Material.RED_STAINED_GLASS_PANE, "gui-create-remove", "64", () -> state.amount -= 64, rerender);
        adjust(gui, 20, Material.RED_STAINED_GLASS_PANE, "gui-create-remove", "10", () -> state.amount -= 10, rerender);
        adjust(gui, 21, Material.RED_STAINED_GLASS_PANE, "gui-create-remove", "1", () -> state.amount -= 1, rerender);
        gui.set(22, ItemBuilder.of(Material.PAPER).name(msg().raw("gui-create-amount-name", placeholders))
                .lore(msg().rawList("gui-create-amount-lore", placeholders)).build());
        adjust(gui, 23, Material.LIME_STAINED_GLASS_PANE, "gui-create-add", "1", () -> state.amount += 1, rerender);
        adjust(gui, 24, Material.LIME_STAINED_GLASS_PANE, "gui-create-add", "10", () -> state.amount += 10, rerender);
        adjust(gui, 25, Material.LIME_STAINED_GLASS_PANE, "gui-create-add", "64", () -> state.amount += 64, rerender);

        // Preis pro Stueck (in ganzen Einheiten)
        adjust(gui, 28, Material.RED_STAINED_GLASS_PANE, "gui-create-remove", "100", () -> state.unit -= 10_000, rerender);
        adjust(gui, 29, Material.RED_STAINED_GLASS_PANE, "gui-create-remove", "10", () -> state.unit -= 1_000, rerender);
        adjust(gui, 30, Material.RED_STAINED_GLASS_PANE, "gui-create-remove", "1", () -> state.unit -= 100, rerender);
        gui.set(31, ItemBuilder.of(Material.GOLD_NUGGET).name(msg().raw("gui-create-price-name", placeholders))
                .lore(msg().rawList("gui-create-price-lore", placeholders)).build());
        adjust(gui, 32, Material.LIME_STAINED_GLASS_PANE, "gui-create-add", "1", () -> state.unit += 100, rerender);
        adjust(gui, 33, Material.LIME_STAINED_GLASS_PANE, "gui-create-add", "10", () -> state.unit += 1_000, rerender);
        adjust(gui, 34, Material.LIME_STAINED_GLASS_PANE, "gui-create-add", "100", () -> state.unit += 10_000, rerender);

        gui.set(38, ItemBuilder.of(Material.RED_CONCRETE).name(msg().raw("gui-create-cancel-name"))
                .lore(msg().rawList("gui-create-cancel-lore")).build(), event -> open(player, 0));
        gui.set(42, ItemBuilder.of(Material.LIME_CONCRETE).name(msg().raw("gui-create-confirm-name"))
                .lore(msg().rawList("gui-create-confirm-lore", placeholders)).build(), event -> {
                    player.closeInventory();
                    plugin.service().createOrder(player, state.material, state.amount, state.unit);
                });
    }

    private void adjust(SchiggyGui gui, int slot, Material material, String nameKey, String number,
                        Runnable change, Runnable rerender) {
        gui.set(slot, ItemBuilder.of(material).name(msg().raw(nameKey, "n", number)).build(), event -> {
            change.run();
            rerender.run();
        });
    }
}
