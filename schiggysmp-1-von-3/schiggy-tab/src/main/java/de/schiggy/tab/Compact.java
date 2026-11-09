package de.schiggy.tab;

import java.util.Locale;

/** Kurzschreibweise fuer grosse Zahlen: 1500 -> 1.5K, 2000000 -> 2M. */
final class Compact {

    private static final String[] SUFFIX = {"", "K", "M", "B", "T"};

    private Compact() {
    }

    static String format(double value) {
        double x = value;
        int index = 0;
        while (Math.abs(x) >= 1000 && index < SUFFIX.length - 1) {
            x /= 1000;
            index++;
        }
        if (index == 0) {
            return x == Math.floor(x) ? String.valueOf((long) x) : String.format(Locale.US, "%.2f", x);
        }
        return String.format(Locale.US, "%.1f", x).replaceAll("\\.0$", "") + SUFFIX[index];
    }
}
