package de.schiggy.keydrop;

import de.schiggy.core.database.Database;
import de.schiggy.core.util.Names;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.crates.CrateService;
import de.schiggy.crates.SchiggyCrates;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Keydrop-Events. Ein einziger Ticker (jede Sekunde) steuert Countdown, Bossbar und automatische Events.
 * Die Auszahlung ist durch ein Flag pro Event gegen Doppelzahlung geschuetzt. Die Schluessel werden direkt
 * ueber die Crate-API von SchiggyCrates gutgeschrieben (keine zweite Schluessel-Waehrung).
 */
public final class KeydropService {

    public record Preset(String id, String name, int weight, Map<String, Integer> keys) {
    }

    public record HistoryEntry(long time, String preset, int players, String status) {
    }

    public enum StartResult { STARTED, ALREADY_RUNNING, UNKNOWN_PRESET, NO_PRESETS }

    private static final class Running {
        final Preset preset;
        final long endsAt;
        final String by;
        final BossBar bar;
        final AtomicBoolean finished = new AtomicBoolean(false);
        int lastAnnounced = -1;

        Running(Preset preset, long endsAt, String by, BossBar bar) {
            this.preset = preset;
            this.endsAt = endsAt;
            this.by = by;
            this.bar = bar;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SchiggyKeydrop plugin;
    private final Database db;
    private volatile Map<String, Preset> presets = Map.of();
    private volatile Running running;
    private volatile long nextAuto;

    public KeydropService(SchiggyKeydrop plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void init() {
        db.submit(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS kd_events ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, preset TEXT NOT NULL, "
                        + "players INTEGER NOT NULL, keys TEXT NOT NULL, started_by TEXT NOT NULL, status TEXT NOT NULL)");
            }
            return null;
        }).join();
        loadPresets();
        scheduleNextAuto();
        SchiggyScheduler.asyncRepeating(plugin, this::tick, 1, 1);
    }

    // ------------------------------------------------------------------ Presets

    public void loadPresets() {
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        if (!file.exists()) {
            plugin.saveResource("rewards.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, Preset> loaded = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("presets");
        if (root != null) {
            SchiggyCrates crates = SchiggyCrates.getInstance();
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                ConfigurationSection keySection = section == null ? null : section.getConfigurationSection("keys");
                if (keySection == null) {
                    continue;
                }
                Map<String, Integer> keys = new LinkedHashMap<>();
                for (String crate : keySection.getKeys(false)) {
                    int amount = keySection.getInt(crate, 0);
                    if (amount <= 0) {
                        continue;
                    }
                    if (crates != null && crates.service().crate(crate).isEmpty()) {
                        plugin.getLogger().warning("Preset '" + id + "': Die Crate '" + crate
                                + "' gibt es in SchiggyCrates nicht und wird uebersprungen.");
                        continue;
                    }
                    keys.put(crate.toLowerCase(Locale.ROOT), amount);
                }
                if (!keys.isEmpty()) {
                    loaded.put(id.toLowerCase(Locale.ROOT), new Preset(id.toLowerCase(Locale.ROOT),
                            section.getString("name", id), Math.max(1, section.getInt("weight", 1)), Map.copyOf(keys)));
                }
            }
        }
        this.presets = loaded;
    }

    public Map<String, Preset> presets() {
        return presets;
    }

    private Preset randomPreset(Map<String, Preset> map) {
        int total = map.values().stream().mapToInt(Preset::weight).sum();
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        int sum = 0;
        for (Preset preset : map.values()) {
            sum += preset.weight();
            if (roll < sum) {
                return preset;
            }
        }
        return map.values().iterator().next();
    }

    /** Name einer Crate fuer Anzeigen (MiniMessage). */
    String crateName(String id) {
        SchiggyCrates crates = SchiggyCrates.getInstance();
        return crates == null ? id : crates.service().crate(id).map(CrateService.Crate::name).orElse(id);
    }

    String crateNamePlain(String id) {
        return MM.stripTags(crateName(id));
    }

    // ------------------------------------------------------------------ Status

    public boolean isRunning() {
        return running != null;
    }

    public long remainingSeconds() {
        Running r = running;
        return r == null ? 0 : Math.max(0, (r.endsAt - System.currentTimeMillis() + 999) / 1000);
    }

    public String runningPresetName() {
        Running r = running;
        return r == null ? "" : MM.stripTags(r.preset.name());
    }

    public long nextAutoMillis() {
        return Math.max(0, nextAuto - System.currentTimeMillis());
    }

    public boolean autoEnabled() {
        return plugin.getConfig().getBoolean("auto.enabled", true);
    }

    void scheduleNextAuto() {
        long minutes = Math.max(1, plugin.getConfig().getLong("auto.interval-minutes", 60));
        nextAuto = System.currentTimeMillis() + minutes * 60_000L;
    }

    // ------------------------------------------------------------------ Starten und Stoppen

    private String keyLines(Preset preset) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : preset.keys().entrySet()) {
            if (out.length() > 0) {
                out.append("\n");
            }
            out.append(plugin.messages().raw("announce-key-line", "amount", String.valueOf(entry.getValue()),
                    "crate", crateNamePlain(entry.getKey())));
        }
        return out.toString();
    }

    private BossBar.Color barColor() {
        try {
            return BossBar.Color.valueOf(plugin.getConfig().getString("bossbar.color", "YELLOW").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.YELLOW;
        }
    }

    private BossBar.Overlay barOverlay() {
        try {
            return BossBar.Overlay.valueOf(plugin.getConfig().getString("bossbar.overlay", "PROGRESS").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Overlay.PROGRESS;
        }
    }

    private void play(Player player, String configKey) {
        String name = plugin.getConfig().getString("sounds." + configKey, "");
        if (name.isBlank()) {
            return;
        }
        try {
            player.playSound(Sound.sound(Key.key(name), Sound.Source.MASTER, 1f, 1f));
        } catch (Exception ignored) {
            // ungueltiger Sound-Name in der Config
        }
    }

    /** presetId = null: ein Preset nach Gewicht waehlen. */
    public synchronized StartResult start(String presetId, String by) {
        if (running != null) {
            return StartResult.ALREADY_RUNNING;
        }
        Map<String, Preset> map = presets;
        if (map.isEmpty()) {
            return StartResult.NO_PRESETS;
        }
        Preset preset = presetId == null ? randomPreset(map) : map.get(presetId.toLowerCase(Locale.ROOT));
        if (preset == null) {
            return StartResult.UNKNOWN_PRESET;
        }
        long seconds = Math.max(1, plugin.getConfig().getLong("countdown-seconds", 60));
        BossBar bar = BossBar.bossBar(Component.empty(), 1f, barColor(), barOverlay());
        Running r = new Running(preset, System.currentTimeMillis() + seconds * 1000L, by, bar);
        running = r;

        String presetName = preset.name();
        String lines = keyLines(preset);
        for (Player player : Bukkit.getOnlinePlayers()) {
            SchiggyScheduler.entity(plugin, player, () -> {
                player.showBossBar(bar);
                for (String line : plugin.messages().rawList("announce-chat", "seconds", String.valueOf(seconds))) {
                    player.sendMessage(MM.deserialize(line.replace("{keys}", lines).replace("{preset}", presetName)));
                }
                player.showTitle(Title.title(plugin.messages().component("title"),
                        plugin.messages().component("subtitle", "seconds", String.valueOf(seconds)),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
                play(player, "start");
            });
        }
        return StartResult.STARTED;
    }

    /** true, wenn ein laufender Keydrop abgebrochen wurde. */
    public boolean stop(String by) {
        Running r = running;
        if (r == null || !r.finished.compareAndSet(false, true)) {
            return false;
        }
        running = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            SchiggyScheduler.entity(plugin, player, () -> {
                player.hideBossBar(r.bar);
                plugin.messages().send(player, "stopped", "by", by);
            });
        }
        record(r, 0, "CANCELLED");
        return true;
    }

    /** Zeigt neuen Spielern waehrend eines Events die Bossbar. Auf dem Thread des Spielers. */
    void showTo(Player player) {
        Running r = running;
        if (r != null) {
            player.showBossBar(r.bar);
        }
    }

    // ------------------------------------------------------------------ Ticker

    private void tick() {
        try {
            Running r = running;
            long now = System.currentTimeMillis();
            if (r != null) {
                long remaining = Math.max(0, r.endsAt - now);
                int seconds = (int) ((remaining + 999) / 1000);
                long total = Math.max(1, plugin.getConfig().getLong("countdown-seconds", 60)) * 1000L;
                r.bar.name(MM.deserialize(plugin.messages().raw("bossbar", "seconds", String.valueOf(seconds))
                        .replace("{preset}", r.preset.name())));
                r.bar.progress((float) Math.max(0, Math.min(1, remaining / (double) total)));
                if (now >= r.endsAt) {
                    finish(r);
                } else if (seconds != r.lastAnnounced) {
                    r.lastAnnounced = seconds;
                    if (plugin.getConfig().getIntegerList("milestones").contains(seconds)) {
                        announceMilestone(seconds);
                    }
                }
                return;
            }
            if (autoEnabled() && now >= nextAuto) {
                scheduleNextAuto();
                if (Bukkit.getOnlinePlayers().size() >= Math.max(1, plugin.getConfig().getInt("auto.min-players", 1))) {
                    start(null, "AUTO");
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Fehler im Keydrop-Ticker", t);
        }
    }

    private void announceMilestone(int seconds) {
        String text = String.valueOf(seconds);
        for (Player player : Bukkit.getOnlinePlayers()) {
            SchiggyScheduler.entity(plugin, player, () -> {
                player.sendActionBar(plugin.messages().component("countdown-actionbar", "seconds", text));
                if (seconds > 5) {
                    plugin.messages().send(player, "countdown-chat", "seconds", text);
                } else {
                    player.showTitle(Title.title(plugin.messages().component("countdown-title", "seconds", text), Component.empty(),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
                    play(player, "tick");
                }
            });
        }
    }

    // ------------------------------------------------------------------ Auszahlung

    private boolean eligible(Player player) {
        if (!player.hasPermission(plugin.getConfig().getString("receive-permission", "schiggy.keydrop.receive"))) {
            return false;
        }
        return !plugin.getConfig().getStringList("blocked-worlds").contains(player.getWorld().getName());
    }

    private void finish(Running r) {
        if (!r.finished.compareAndSet(false, true)) {
            return; // schon ausgezahlt oder gestoppt: keine Doppelzahlung
        }
        running = null;
        SchiggyCrates crates = SchiggyCrates.getInstance();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        Set<UUID> paid = new HashSet<>();
        for (Player player : online) {
            if (eligible(player)) {
                paid.add(player.getUniqueId());
                if (crates != null) {
                    for (Map.Entry<String, Integer> key : r.preset.keys().entrySet()) {
                        crates.service().addKeys(player.getUniqueId(), key.getKey(), key.getValue());
                    }
                }
            }
        }
        int count = paid.size();
        for (Player player : online) {
            boolean received = paid.contains(player.getUniqueId());
            SchiggyScheduler.entity(plugin, player, () -> {
                player.hideBossBar(r.bar);
                plugin.messages().send(player, "finish-chat", "players", String.valueOf(count));
                if (!received) {
                    plugin.messages().send(player, "not-eligible");
                    return;
                }
                plugin.messages().send(player, "received-header");
                for (Map.Entry<String, Integer> key : r.preset.keys().entrySet()) {
                    plugin.messages().send(player, "received-line", "amount", String.valueOf(key.getValue()),
                            "crate", crateNamePlain(key.getKey()));
                }
                player.showTitle(Title.title(plugin.messages().component("received-title"),
                        plugin.messages().component("received-subtitle"),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
                play(player, "finish");
            });
        }
        record(r, count, "PAID");
    }

    // ------------------------------------------------------------------ Verlauf

    private void record(Running r, int players, String status) {
        StringBuilder keys = new StringBuilder();
        for (Map.Entry<String, Integer> key : r.preset.keys().entrySet()) {
            if (keys.length() > 0) {
                keys.append(',');
            }
            keys.append(key.getKey()).append(':').append(key.getValue());
        }
        String keyText = keys.toString();
        db.<Void>submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO kd_events(time, preset, players, keys, started_by, status) VALUES(?,?,?,?,?,?)")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, r.preset.id());
                ps.setInt(3, players);
                ps.setString(4, keyText);
                ps.setString(5, r.by);
                ps.setString(6, status);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Keydrop-Event konnte nicht gespeichert werden", ex);
            return null;
        });
    }

    public CompletableFuture<List<HistoryEntry>> history(int limit) {
        return db.<List<HistoryEntry>>submit(c -> {
            List<HistoryEntry> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT time, preset, players, status FROM kd_events ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new HistoryEntry(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
                    }
                }
            }
            return list;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Keydrop-Verlauf konnte nicht geladen werden", ex);
            return List.<HistoryEntry>of();
        });
    }

    String formatDuration(long millis) {
        return Names.duration(millis);
    }
}
