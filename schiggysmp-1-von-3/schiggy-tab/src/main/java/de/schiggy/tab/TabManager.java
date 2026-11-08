package de.schiggy.tab;

import de.schiggy.core.util.Names;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.foliaboard.FoliaBoard;
import net.foliaboard.api.Sidebar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Aktualisiert Tab-Liste und Sidebar pro Spieler auf dessen eigenem Region-Thread (Paper und Folia).
 * Es werden nur dann Pakete gesendet, wenn sich ein Text wirklich geaendert hat.
 */
public final class TabManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private record Config(boolean tabEnabled, boolean sidebarEnabled, int interval, List<String> header,
                          List<String> footer, String nameFormat, String title, List<String> lines,
                          boolean belowEnabled, String belowTitle) {
    }

    private static final class PlayerState {
        String lastHeader;
        String lastFooter;
        String lastName;
        Sidebar sidebar;
        // Geld der letzten Sekunden, daraus wird "Geld pro Sekunde" berechnet
        final long[] moneySamples = new long[10];
        final long[] sampleTimes = new long[10];
        int sampleCount;
        int sampleHead;
        long moneyPerSecCents;

        void pushMoney(long money, long now) {
            moneySamples[sampleHead] = money;
            sampleTimes[sampleHead] = now;
            sampleHead = (sampleHead + 1) % moneySamples.length;
            if (sampleCount < moneySamples.length) {
                sampleCount++;
            }
            if (sampleCount >= 2) {
                int oldest = (sampleHead - sampleCount + moneySamples.length) % moneySamples.length;
                int newest = (sampleHead - 1 + moneySamples.length) % moneySamples.length;
                double seconds = (sampleTimes[newest] - sampleTimes[oldest]) / 1000.0;
                if (seconds > 0) {
                    moneyPerSecCents = Math.max(0, Math.round((moneySamples[newest] - moneySamples[oldest]) / seconds));
                }
            }
        }
    }

    private final SchiggyTab plugin;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private volatile Config config;
    private volatile boolean sidebarBroken;
    /** FoliaBoard: paketbasierte Scoreboards, funktionieren auf Paper und Folia. Null, falls die Bibliothek nicht startet. */
    private FoliaBoard board;

    public TabManager(SchiggyTab plugin) {
        this.plugin = plugin;
        this.config = load();
        try {
            this.board = FoliaBoard.create(plugin);
            applyBelowTitle();
        } catch (Throwable t) {
            this.sidebarBroken = true;
            plugin.getLogger().log(Level.WARNING, "FoliaBoard konnte nicht gestartet werden - Sidebar und Name-Anzeige sind aus.", t);
        }
    }

    public void reload() {
        this.config = load();
        applyBelowTitle();
    }

    /** Beim Ausschalten des Plugins: alle Boards und Aufgaben der Bibliothek schliessen. */
    public void close() {
        if (board != null) {
            board.close();
        }
    }

    private void applyBelowTitle() {
        if (board == null) {
            return;
        }
        try {
            Config cfg = this.config;
            if (cfg.belowEnabled()) {
                board.belowName().title(MM.deserialize(cfg.belowTitle()));
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Anzeige unter dem Namen konnte nicht gesetzt werden", t);
        }
    }

    private Config load() {
        FileConfiguration c = plugin.getConfig();
        return new Config(
                c.getBoolean("tab.enabled", true),
                c.getBoolean("scoreboard.enabled", true),
                Math.max(5, c.getInt("update-interval-ticks", 20)),
                c.getStringList("tab.header"),
                c.getStringList("tab.footer"),
                c.getString("tab.name-format", "{prefix}<white>{player}"),
                c.getString("scoreboard.title", "<gold><bold>SCHIGGY"),
                c.getStringList("scoreboard.lines"),
                c.getBoolean("below-name.enabled", true),
                c.getString("below-name.title", "<green>$"));
    }

    // ------------------------------------------------------------------ Start / Stop

    /** Muss auf dem Thread des Spielers laufen. */
    public void start(Player player) {
        UUID id = player.getUniqueId();
        if (tasks.containsKey(id)) {
            return;
        }
        PlayerState state = new PlayerState();
        states.put(id, state);
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin,
                scheduled -> update(player, state, scheduled),
                () -> stop(id), 5L, config.interval());
        if (task != null) {
            tasks.put(id, task);
        }
    }

    public void stop(UUID id) {
        states.remove(id);
        ScheduledTask task = tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    // ------------------------------------------------------------------ Update

    private void update(Player player, PlayerState state, ScheduledTask task) {
        if (!player.isOnline()) {
            task.cancel();
            stop(player.getUniqueId());
            return;
        }
        Config cfg = this.config;
        if (plugin.getServer().getPluginManager().isPluginEnabled("SchiggyEconomy")) {
            try {
                state.pushMoney(EconomyHook.moneyMinor(player), System.currentTimeMillis());
            } catch (Throwable ignored) {
                // Geld pro Sekunde ist nur eine Anzeige
            }
        }
        try {
            if (cfg.tabEnabled()) {
                updateTab(player, state, cfg);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Tab-Update fehlgeschlagen", t);
        }
        if (cfg.sidebarEnabled() && !sidebarBroken) {
            try {
                updateSidebar(player, state, cfg);
                updateBelowName(player, cfg);
            } catch (Throwable t) {
                sidebarBroken = true;
                plugin.getLogger().log(Level.WARNING,
                        "Sidebar-Scoreboard funktioniert auf diesem Server nicht und wird abgeschaltet.", t);
            }
        }
    }

    private void updateTab(Player player, PlayerState state, Config cfg) {
        String header = join(cfg.header(), player);
        String footer = join(cfg.footer(), player);
        if (!header.equals(state.lastHeader) || !footer.equals(state.lastFooter)) {
            player.sendPlayerListHeaderAndFooter(MM.deserialize(header), MM.deserialize(footer));
            state.lastHeader = header;
            state.lastFooter = footer;
        }
        String name = resolve(cfg.nameFormat(), player);
        if (!name.equals(state.lastName)) {
            player.playerListName(MM.deserialize(name));
            state.lastName = name;
        }
    }

    private void updateSidebar(Player player, PlayerState state, Config cfg) {
        if (board == null) {
            return;
        }
        if (!sidebarWanted(player)) {
            // Spieler hat das Scoreboard in /settings ausgeschaltet
            if (state.sidebar != null) {
                state.sidebar.visible(false);
            }
            return;
        }
        if (state.sidebar == null) {
            state.sidebar = board.sidebar(player);
        }
        Sidebar sidebar = state.sidebar;
        sidebar.visible(true);
        List<String> lines = cfg.lines();
        int wanted = Math.max(1, Math.min(15, lines.size()));
        if (sidebar.lineCount() != wanted) {
            sidebar.clearLines();
        }
        sidebar.title(MM.deserialize(resolve(cfg.title(), player)));
        for (int i = 0; i < wanted && i < lines.size(); i++) {
            sidebar.line(i, MM.deserialize(resolve(lines.get(i), player)));
        }
    }

    /** Geld in Cent als Zahl unter dem Namen jedes Spielers (siehe below-name in der config.yml). */
    private void updateBelowName(Player player, Config cfg) {
        if (board == null || !cfg.belowEnabled() || !plugin.getServer().getPluginManager().isPluginEnabled("SchiggyEconomy")) {
            return;
        }
        long units = EconomyHook.moneyMinor(player) / 100;
        board.belowName().score(player, (int) Math.min(Integer.MAX_VALUE, units));
    }

    // ------------------------------------------------------------------ Platzhalter

    private String join(List<String> lines, Player player) {
        return lines.stream().map(line -> resolve(line, player)).collect(Collectors.joining("<newline>"));
    }

    private String resolve(String template, Player player) {
        if (template.indexOf('{') < 0) {
            return template;
        }
        String s = template;
        s = s.replace("{player}", MM.escapeTags(player.getName()));
        s = s.replace("{ping}", String.valueOf(player.getPing()));
        s = s.replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()));
        s = s.replace("{max}", String.valueOf(Bukkit.getMaxPlayers()));
        s = s.replace("{world}", MM.escapeTags(player.getWorld().getName()));
        if (s.contains("{prefix}")) {
            s = s.replace("{prefix}", prefix(player));
        }
        if (s.contains("{rank}")) {
            s = s.replace("{rank}", MM.escapeTags(rank(player)));
        }
        if (s.contains("{money}") || s.contains("{money_full}") || s.contains("{shards}")) {
            boolean economy = plugin.getServer().getPluginManager().isPluginEnabled("SchiggyEconomy");
            s = s.replace("{money}", economy ? MM.escapeTags(EconomyHook.moneyCompact(player)) : "0");
            s = s.replace("{money_full}", economy ? MM.escapeTags(EconomyHook.moneyFull(player)) : "0");
            s = s.replace("{shards}", economy ? MM.escapeTags(EconomyHook.shards(player)) : "0");
        }
        if (s.contains("{money_per_sec}")) {
            PlayerState current = states.get(player.getUniqueId());
            long cents = current == null ? 0 : current.moneyPerSecCents;
            s = s.replace("{money_per_sec}", MM.escapeTags(Compact.format(cents / 100.0)));
        }
        if (s.contains("{kills}") || s.contains("{deaths}") || s.contains("{playtime}") || s.contains("{kdr}")) {
            long kills = 0;
            long deaths = 0;
            long seconds = 0;
            if (plugin.getServer().getPluginManager().isPluginEnabled("SchiggyStats")) {
                try {
                    var snapshot = StatsHook.snapshot(player);
                    kills = snapshot.kills();
                    deaths = snapshot.deaths();
                    seconds = snapshot.playSeconds();
                } catch (Throwable ignored) {
                    // Statistiken sind nur eine Anzeige
                }
            }
            s = s.replace("{kills}", String.valueOf(kills));
            s = s.replace("{deaths}", String.valueOf(deaths));
            s = s.replace("{playtime}", MM.escapeTags(Names.duration(seconds * 1000L)));
            s = s.replace("{kdr}", String.format(Locale.US, "%.2f", deaths == 0 ? (double) kills : (double) kills / deaths));
        }
        return s;
    }

    /** true, wenn der Spieler das Scoreboard sehen will (SchiggySettings, Schalter "Scoreboard"). */
    private boolean sidebarWanted(Player player) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("SchiggySettings")) {
            return true;
        }
        try {
            return SettingsHook.scoreboardEnabled(player);
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean luckPerms() {
        return plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms");
    }

    /** Prefix als MiniMessage-Text (LuckPerms liefert &-Farbcodes). */
    private String prefix(Player player) {
        if (!luckPerms()) {
            return "";
        }
        try {
            String legacy = LuckPermsHook.prefix(player);
            return legacy.isEmpty() ? "" : MM.serialize(LEGACY.deserialize(legacy));
        } catch (Throwable t) {
            return "";
        }
    }

    private String rank(Player player) {
        if (!luckPerms()) {
            return "Spieler";
        }
        try {
            String group = LuckPermsHook.group(player);
            if (group == null || group.isEmpty()) {
                return "Spieler";
            }
            return group.substring(0, 1).toUpperCase(Locale.ROOT) + group.substring(1);
        } catch (Throwable t) {
            return "Spieler";
        }
    }
}
