package de.schiggy.core.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Einfache Cooldown-Verwaltung pro Spieler (thread-sicher). */
public final class Cooldowns {

    private final Map<UUID, Long> until = new ConcurrentHashMap<>();

    /** Verbleibende Zeit in Millisekunden, 0 wenn kein Cooldown aktiv ist. */
    public long remainingMillis(UUID id) {
        Long end = until.get(id);
        if (end == null) {
            return 0L;
        }
        long remaining = end - System.currentTimeMillis();
        if (remaining <= 0) {
            until.remove(id);
            return 0L;
        }
        return remaining;
    }

    /** Verbleibende Zeit in ganzen Sekunden (aufgerundet). */
    public long remainingSeconds(UUID id) {
        return (remainingMillis(id) + 999) / 1000;
    }

    public void set(UUID id, long seconds) {
        if (seconds > 0) {
            until.put(id, System.currentTimeMillis() + seconds * 1000L);
        }
    }

    public void clear(UUID id) {
        until.remove(id);
    }
}
