package de.schiggy.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.OptionalLong;

/** Liest Betraege wie 100, 2.5k, 1m, 3b. Dezimalpunkt ist ".". Ergebnis in der kleinsten Einheit. */
public final class AmountParser {

    private AmountParser() {
    }

    public static OptionalLong parse(String input, Currency currency, boolean allowZero) {
        if (input == null) {
            return OptionalLong.empty();
        }
        String s = input.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        if (s.isEmpty() || s.length() > 32) {
            return OptionalLong.empty();
        }

        BigDecimal multiplier = BigDecimal.ONE;
        boolean hasSuffix = true;
        switch (s.charAt(s.length() - 1)) {
            case 'k' -> multiplier = BigDecimal.valueOf(1_000L);
            case 'm' -> multiplier = BigDecimal.valueOf(1_000_000L);
            case 'b' -> multiplier = BigDecimal.valueOf(1_000_000_000L);
            case 't' -> multiplier = BigDecimal.valueOf(1_000_000_000_000L);
            default -> hasSuffix = false;
        }
        if (hasSuffix) {
            s = s.substring(0, s.length() - 1);
        }

        try {
            BigDecimal value = new BigDecimal(s).multiply(multiplier).movePointRight(currency.decimals());
            int sign = value.signum();
            if (sign < 0 || (sign == 0 && !allowZero)) {
                return OptionalLong.empty();
            }
            // wirft ArithmeticException, wenn es Nachkommastellen unterhalb der kleinsten Einheit gibt
            return OptionalLong.of(value.setScale(0, RoundingMode.UNNECESSARY).longValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
