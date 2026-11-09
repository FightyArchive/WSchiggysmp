package de.schiggy.tab;

import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.entity.Player;

/** Nur aufrufen, wenn SchiggyEconomy aktiv ist. Liest nur aus dem Cache, ohne Datenbankzugriff. */
final class EconomyHook {

    private EconomyHook() {
    }

    private static long cached(Player player, Currency currency) {
        EconomyService service = SchiggyEconomy.getInstance().economy();
        return service.getCachedBalance(player.getUniqueId(), currency).orElse(0L);
    }

    /** Geld in Cent aus dem Cache. */
    static long moneyMinor(Player player) {
        return cached(player, Currency.MONEY);
    }

    static String moneyCompact(Player player) {
        return Compact.format(cached(player, Currency.MONEY) / (double) Currency.MONEY.scale());
    }

    static String moneyFull(Player player) {
        return SchiggyEconomy.getInstance().economy().format(Currency.MONEY, cached(player, Currency.MONEY));
    }

    static String shards(Player player) {
        return Compact.format(cached(player, Currency.SHARDS));
    }
}
