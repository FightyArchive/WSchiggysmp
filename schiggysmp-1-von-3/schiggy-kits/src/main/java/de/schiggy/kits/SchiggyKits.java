package de.schiggy.kits;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Inventories;
import de.schiggy.core.util.Names;
import de.schiggy.kits.KitService.EffectSpec;
import de.schiggy.kits.KitService.Kit;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

public final class SchiggyKits extends JavaPlugin {

    private MessageBundle messages;
    private KitService service;
    private KitGui gui;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggyKits wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new KitService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyKits wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        gui = new KitGui(this);

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        PluginCommand command = getCommand("kit");
        if (command != null) {
            KitCommand executor = new KitCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            service.loadPlayer(player.getUniqueId());
        }
        getLogger().info("SchiggyKits aktiviert (" + service.all().size() + " Kits).");
    }

    public KitService service() {
        return service;
    }

    KitGui gui() {
        return gui;
    }

    public MessageBundle messages() {
        return messages;
    }

    void reply(CommandSender sender, Runnable task) {
        SchiggyScheduler.forSender(this, sender, task);
    }

    /** Darf der Spieler dieses Kit benutzen? (Recht schiggy.kit.<name> oder Eintrag in free-kits) */
    boolean hasAccess(Player player, Kit kit) {
        String name = kit.name().toLowerCase(Locale.ROOT);
        if (getConfig().getStringList("free-kits").stream().anyMatch(free -> free.equalsIgnoreCase(name))) {
            return true;
        }
        return player.hasPermission("schiggy.kit." + name);
    }

    /**
     * Holt ein Kit ab. force = true ueberspringt Recht und Wartezeit (Admin-Befehl).
     * Muss auf dem Thread des Spielers laufen.
     */
    void claim(Player player, Kit kit, boolean force) {
        UUID uuid = player.getUniqueId();
        String display = MiniMessage.miniMessage().stripTags(kit.display());
        if (force) {
            deliver(player, kit);
            return;
        }
        if (!hasAccess(player, kit)) {
            messages.send(player, "kit-no-access", "kit", display);
            return;
        }
        if (!player.hasPermission("schiggy.kit.bypass-cooldown")) {
            long remaining = service.remainingMillis(uuid, kit);
            if (remaining > 0) {
                messages.send(player, "kit-cooldown", "kit", display, "time", Names.duration(remaining));
                return;
            }
        }
        if (player.hasPermission("schiggy.kit.bypass-cooldown")) {
            deliver(player, kit);
            return;
        }
        service.startCooldown(uuid, kit, cooldownFor(player, kit)).thenAccept(started -> SchiggyScheduler.entity(this, player, () -> {
            if (started) {
                deliver(player, kit);
            } else {
                messages.send(player, "kit-cooldown", "kit", display,
                        "time", Names.duration(service.remainingMillis(uuid, kit)));
            }
        }));
    }

    /**
     * Wartezeit dieses Spielers fuer das Kit in Sekunden: Standard des Kits, oder die niedrigste Zeit aus den
     * LuckPerms-Rechten schiggy.kit.cooldown.<kit>.<Zeit> und schiggy.kit.cooldown.all.<Zeit> (z. B. 30m, 12h, 0).
     */
    long cooldownFor(Player player, Kit kit) {
        long best = kit.cooldownSeconds();
        String specific = "schiggy.kit.cooldown." + kit.name().toLowerCase(Locale.ROOT) + ".";
        String all = "schiggy.kit.cooldown.all.";
        for (org.bukkit.permissions.PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String permission = info.getPermission().toLowerCase(Locale.ROOT);
            String time;
            if (permission.startsWith(specific)) {
                time = permission.substring(specific.length());
            } else if (permission.startsWith(all)) {
                time = permission.substring(all.length());
            } else {
                continue;
            }
            long seconds = KitCommand.parseSeconds(time);
            if (seconds >= 0) {
                best = Math.min(best, seconds);
            }
        }
        return best;
    }

    private void deliver(Player player, Kit kit) {
        for (ItemStack item : kit.items()) {
            Inventories.give(player, item);
        }
        for (EffectSpec spec : kit.effects()) {
            PotionEffectType type = Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(spec.type().toLowerCase(Locale.ROOT)));
            if (type != null) {
                player.addPotionEffect(new PotionEffect(type, spec.seconds() * 20, spec.amplifier()));
            }
        }
        messages.send(player, "kit-received", "kit", MiniMessage.miniMessage().stripTags(kit.display()));
    }
}
