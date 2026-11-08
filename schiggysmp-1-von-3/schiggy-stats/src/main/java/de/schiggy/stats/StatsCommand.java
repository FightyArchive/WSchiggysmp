package de.schiggy.stats;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Names;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import de.schiggy.stats.StatsService.Category;
import de.schiggy.stats.StatsService.Entry;
import de.schiggy.stats.StatsService.Snapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /stats und /leaderboard (Bestenliste als Menue mit Koepfen der Top 10). */
public final class StatsCommand implements TabExecutor {

    private static final int[] HEAD_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
    private static final int[] BUTTON_SLOTS = {37, 39, 41, 43};

    private final SchiggyStats plugin;

    public StatsCommand(SchiggyStats plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("schiggy.stats.use")) {
            msg().send(sender, "no-permission");
            return true;
        }
        if (command.getName().equalsIgnoreCase("leaderboard")) {
            if (!(sender instanceof Player player)) {
                msg().send(sender, "players-only");
                return true;
            }
            Category category = Category.MONEY;
            if (args.length > 0) {
                try {
                    category = Category.valueOf(args[0].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // unbekannt: Geld
                }
            }
            openLeaderboard(player, category);
            return true;
        }
        stats(sender, args);
        return true;
    }

    // ------------------------------------------------------------------ /stats

    private void stats(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                msg().send(sender, "players-only");
                return;
            }
            show(sender, player.getUniqueId(), player.getName());
            return;
        }
        economy().findPlayer(args[0]).thenAccept(found -> SchiggyScheduler.forSender(plugin, sender, () -> {
            if (found.isEmpty()) {
                msg().send(sender, "player-unknown", "player", args[0]);
            } else {
                show(sender, found.get().uuid(), found.get().name());
            }
        }));
    }

    private void show(CommandSender sender, UUID uuid, String name) {
        plugin.service().statsOf(uuid).thenCombine(economy().getBalance(uuid, Currency.MONEY),
                (snapshot, money) -> new Object[]{snapshot, money})
                .thenCombine(economy().getBalance(uuid, Currency.SHARDS), (pair, shards) ->
                        new Object[]{pair[0], pair[1], shards})
                .thenAccept(data -> SchiggyScheduler.forSender(plugin, sender, () -> {
                    Snapshot snapshot = (Snapshot) data[0];
                    msg().send(sender, "stats-header", "player", name);
                    msg().send(sender, "stats-kills", "kills", String.valueOf(snapshot.kills()));
                    msg().send(sender, "stats-deaths", "deaths", String.valueOf(snapshot.deaths()));
                    msg().send(sender, "stats-kdr", "kdr", String.format(Locale.US, "%.2f", snapshot.kdr()));
                    msg().send(sender, "stats-playtime", "playtime", Names.duration(snapshot.playSeconds() * 1000L));
                    msg().send(sender, "stats-money", "money", economy().format(Currency.MONEY, (Long) data[1]));
                    msg().send(sender, "stats-shards", "shards", economy().format(Currency.SHARDS, (Long) data[2]));
                }));
    }

    // ------------------------------------------------------------------ /leaderboard

    private void openLeaderboard(Player player, Category category) {
        if (category == Category.MONEY) {
            List<Entry> entries = new ArrayList<>();
            for (var entry : economy().baltop()) {
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(entry.name());
                entries.add(new Entry(cached == null ? null : cached.getUniqueId(), entry.name(), entry.amount()));
            }
            renderLeaderboard(player, category, entries);
            return;
        }
        plugin.service().top(category, 10).thenAccept(entries ->
                SchiggyScheduler.entity(plugin, player, () -> renderLeaderboard(player, category, entries)));
    }

    private String format(Category category, long value) {
        return switch (category) {
            case MONEY -> economy().format(Currency.MONEY, value);
            case PLAYTIME -> Names.duration(value * 1000L);
            default -> String.valueOf(value);
        };
    }

    private void renderLeaderboard(Player player, Category category, List<Entry> entries) {
        String categoryName = msg().raw("category-" + category.name());
        SchiggyGui gui = new SchiggyGui(msg().component("gui-lb-title",
                "category", net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(categoryName)), 5);

        for (int i = 0; i < entries.size() && i < HEAD_SLOTS.length; i++) {
            Entry entry = entries.get(i);
            ItemBuilder head = entry.uuid() == null ? ItemBuilder.of(Material.PLAYER_HEAD)
                    : ItemBuilder.head(Bukkit.getOfflinePlayer(entry.uuid()));
            gui.set(HEAD_SLOTS[i], head
                    .name(msg().raw("gui-lb-head-name", "rank", String.valueOf(i + 1), "player", entry.name()))
                    .lore(msg().rawList("gui-lb-head-lore", "category",
                            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(categoryName),
                            "value", format(category, entry.value())))
                    .build());
        }
        if (entries.isEmpty()) {
            gui.set(22, ItemBuilder.of(Material.BARRIER).name(msg().raw("gui-lb-empty")).build());
        }

        Category[] all = Category.values();
        Material[] icons = {Material.GOLD_INGOT, Material.DIAMOND_SWORD, Material.SKELETON_SKULL, Material.CLOCK};
        for (int i = 0; i < all.length; i++) {
            Category button = all[i];
            List<String> lore = new ArrayList<>(msg().rawList("gui-lb-button-lore"));
            if (button == category) {
                lore.add(0, msg().raw("gui-lb-selected"));
            }
            gui.set(BUTTON_SLOTS[i], ItemBuilder.of(icons[i]).name(msg().raw("category-" + button.name()))
                    .lore(lore).build(), event -> openLeaderboard(player, button));
        }
        gui.open(player);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            if (command.getName().equalsIgnoreCase("leaderboard")) {
                out.addAll(List.of("money", "kills", "deaths", "playtime"));
            } else {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    out.add(online.getName());
                }
            }
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
