package de.schiggy.sign;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

/** Nur aufrufen, wenn LuckPerms installiert ist (sonst NoClassDefFoundError). */
final class LuckPermsHook {

    private LuckPermsHook() {
    }

    /** Prefix im Legacy-Format mit &-Farbcodes, leer wenn keiner gesetzt ist. */
    static String prefix(Player player) {
        User user = LuckPermsProvider.get().getPlayerAdapter(Player.class).getUser(player);
        String prefix = user.getCachedData().getMetaData().getPrefix();
        return prefix == null ? "" : prefix;
    }
}
