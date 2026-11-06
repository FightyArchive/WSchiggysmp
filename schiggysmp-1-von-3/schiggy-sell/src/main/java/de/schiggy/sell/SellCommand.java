package de.schiggy.sell;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/** Nur drei Befehle: /sell (Menue), /sellmulti (Level und Multiplikator), /sellhistory (letzte Verkaeufe). */
public final class SellCommand implements CommandExecutor {

    private final SchiggySell plugin;

    public SellCommand(SchiggySell plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.sell.use")) {
            msg().send(sender, "no-permission");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sell" -> plugin.gui().open(player);
            case "sellmulti" -> plugin.gui().openMulti(player);
            case "sellhistory" -> plugin.service().history(player.getUniqueId()).thenAccept(sales ->
                    SchiggyScheduler.entity(plugin, player, () -> plugin.gui().openHistory(player, sales)));
            default -> {
                return false;
            }
        }
        return true;
    }
}
