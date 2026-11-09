
package de.schiggy.armor;

import de.schiggy.armor.ArmorSets.ArmorSet;
import de.schiggy.armor.ArmorSets.Bonus;
import de.schiggy.armor.ArmorSets.EffectSpec;
import de.schiggy.armor.ArmorSets.PieceBonus;
import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SchiggyArmor extends JavaPlugin implements Listener {

    private static final int EFFECT_TICKS = 60;

    private NamespacedKey scaleKey;
    private MessageBundle messages;
    private ArmorSets sets;

    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<PotionEffectType>> granted = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();

        if (core == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggyArmor wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();

        messages = core.messages().bundle(this);
        sets = new ArmorSets(this);
        scaleKey = new NamespacedKey(this, "scale");

        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("armor");

        if (command != null) {
            ArmorCommand executor = new ArmorCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(this, task -> start(player), null);
        }

        getLogger().info("SchiggyArmor aktiviert (" + sets.all().size() + " Sets).");
    }

    MessageBundle messages() {
        return messages;
    }

    ArmorSets sets() {
        return sets;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        start(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();

        ScheduledTask task = tasks.remove(id);

        if (task != null) {
            task.cancel();
        }

        granted.remove(id);
    }

    private void start(Player player) {
        UUID id = player.getUniqueId();

        if (tasks.containsKey(id)) {
            return;
        }

        ScheduledTask task = player.getScheduler().runAtFixedRate(
                this,
                scheduled -> {
                    if (!player.isOnline()) {
                        scheduled.cancel();
                        tasks.remove(id);
                        return;
                    }

                    refresh(player);
                },
                () -> tasks.remove(id),
                1L,
                20L
        );

        if (task != null) {
            tasks.put(id, task);
        }
    }

    private void applyScale(Player player, double scale) {
        AttributeInstance attribute = player.getAttribute(Attribute.SCALE);

        if (attribute == null) {
            return;
        }

        AttributeModifier current = attribute.getModifier(scaleKey);
        double wantedAmount = scale - 1.0;

        if (scale >= 1.0) {
            if (current != null) {
                attribute.removeModifier(scaleKey);
            }
            return;
        }

        if (current != null && Math.abs(current.getAmount() - wantedAmount) < 0.0001) {
            return;
        }

        if (current != null) {
            attribute.removeModifier(scaleKey);
        }

        attribute.addTransientModifier(
                new AttributeModifier(
                        scaleKey,
                        wantedAmount,
                        AttributeModifier.Operation.ADD_SCALAR
                )
        );
    }

    void refresh(Player player) {
        Map<String, Integer> worn = new HashMap<>();
        Map<PotionEffectType, Integer> wanted = new HashMap<>();

        double wantedScale = 1.0;

        for (ItemStack piece : player.getInventory().getArmorContents()) {
            String setId = sets.setOf(piece);

            if (setId == null) {
                continue;
            }

            worn.merge(setId, 1, Integer::sum);

            ArmorSet set = sets.get(setId);

            PieceBonus own = set == null
                    ? null
                    : set.pieceBonuses().get(sets.pieceOf(piece));

            if (own != null) {
                if (own.scale() > 0) {
                    wantedScale = Math.min(wantedScale, own.scale());
                }

                for (EffectSpec effect : own.effects()) {
                    wanted.merge(effect.type(), effect.amplifier(), Math::max);
                }
            }
        }

        applyScale(player, wantedScale);

        for (Map.Entry<String, Integer> entry : worn.entrySet()) {
            ArmorSet set = sets.get(entry.getKey());

            if (set == null) {
                continue;
            }

            for (Bonus bonus : set.bonuses()) {
                if (entry.getValue() >= bonus.pieces()) {
                    for (EffectSpec effect : bonus.effects()) {
                        wanted.merge(effect.type(), effect.amplifier(), Math::max);
                    }
                }
            }
        }

        UUID id = player.getUniqueId();

        Set<PotionEffectType> previous =
                granted.getOrDefault(id, Set.of());

        for (PotionEffectType type : previous) {
            if (!wanted.containsKey(type)) {
                player.removePotionEffect(type);
            }
        }

        for (Map.Entry<PotionEffectType, Integer> entry : wanted.entrySet()) {
            player.addPotionEffect(
                    new PotionEffect(
                            entry.getKey(),
                            EFFECT_TICKS,
                            entry.getValue(),
                            true,
                            false,
                            true
                    )
            );
        }

        if (wanted.isEmpty()) {
            granted.remove(id);
        } else {
            granted.put(id, new HashSet<>(wanted.keySet()));
        }
    }
}
