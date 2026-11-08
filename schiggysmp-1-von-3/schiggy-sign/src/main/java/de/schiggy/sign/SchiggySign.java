package de.schiggy.sign;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * /sign: Haengt direkt unter den Item-Namen eine Zeile "Signiert von [Prefix] Name".
 * Wer ein Item signiert hat, kann es aktualisieren oder die Signatur entfernen. Fremde Signaturen bleiben geschuetzt.
 */
public final class SchiggySign extends JavaPlugin implements CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private MessageBundle messages;
    private NamespacedKey signedKey;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggySign wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        messages = core.messages().bundle(this);
        signedKey = new NamespacedKey(this, "signed_by");
        PluginCommand command = getCommand("sign");
        if (command != null) {
            command.setExecutor(this);
        }
        getLogger().info("SchiggySign aktiviert.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.sign.use")) {
            messages.send(sender, "no-permission");
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            messages.send(player, "hold-item");
            return true;
        }
        ItemMeta meta = hand.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String signer = data.get(signedKey, PersistentDataType.STRING);
        String own = player.getUniqueId().toString();
        boolean admin = player.hasPermission("schiggy.sign.admin");

        if (args.length > 0 && args[0].equalsIgnoreCase("remove")) {
            if (signer == null) {
                messages.send(player, "not-signed");
            } else if (!signer.equals(own) && !admin) {
                messages.send(player, "not-yours");
            } else {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                if (!lore.isEmpty()) {
                    lore.remove(0);
                }
                meta.lore(lore.isEmpty() ? null : lore);
                data.remove(signedKey);
                hand.setItemMeta(meta);
                player.getInventory().setItemInMainHand(hand);
                messages.send(player, "removed");
            }
            return true;
        }

        if (signer != null && !signer.equals(own) && !admin) {
            messages.send(player, "already-signed");
            return true;
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        Component line = MM.deserialize(getConfig().getString("format", "<gray>Signiert von {prefix}<white>{player}")
                        .replace("{prefix}", prefixOf(player))
                        .replace("{player}", MM.escapeTags(player.getName())))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        boolean resign = signer != null && !lore.isEmpty();
        if (resign) {
            lore.set(0, line);
        } else {
            lore.add(0, line);
        }
        meta.lore(lore);
        data.set(signedKey, PersistentDataType.STRING, own);
        hand.setItemMeta(meta);
        player.getInventory().setItemInMainHand(hand);
        messages.send(player, resign ? "resigned" : "signed");
        return true;
    }

    /** LuckPerms-Prefix als MiniMessage mit Leerzeichen am Ende (leer ohne LuckPerms oder ohne Prefix). */
    private String prefixOf(Player player) {
        if (!getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            return "";
        }
        try {
            String legacy = LuckPermsHook.prefix(player);
            if (legacy.isBlank()) {
                return "";
            }
            String prefix = MM.serialize(LEGACY.deserialize(legacy));
            return prefix.endsWith(" ") ? prefix : prefix + " ";
        } catch (Throwable t) {
            return "";
        }
    }
}
