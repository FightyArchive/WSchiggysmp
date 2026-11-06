package de.schiggy.economy;

import java.util.Locale;
import java.util.Optional;

/**
 * Waehrungen. Betraege werden intern immer als ganze Zahl in der kleinsten Einheit gespeichert
 * (Money: Cent, Shards: ganze Stueck), damit es keine Rundungsfehler gibt.
 */
public enum Currency {
    MONEY("money", 2),
    SHARDS("shards", 0);

    private final String id;
    private final int decimals;
    private final long scale;

    Currency(String id, int decimals) {
        this.id = id;
        this.decimals = decimals;
        long s = 1;
        for (int i = 0; i < decimals; i++) {
            s *= 10;
        }
        this.scale = s;
    }

    public String id() {
        return id;
    }

    public int decimals() {
        return decimals;
    }

    public long scale() {
        return scale;
    }

    /** Normale Einheiten (z. B. 12.5) in die kleinste Einheit umrechnen. */
    public long toMinor(double units) {
        return Math.round(units * scale);
    }

    public static Optional<Currency> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (Currency currency : values()) {
            if (currency.id.equals(lower)) {
                return Optional.of(currency);
            }
        }
        return Optional.empty();
    }
}
