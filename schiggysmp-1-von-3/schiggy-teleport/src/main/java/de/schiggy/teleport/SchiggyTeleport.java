package de.schiggy.teleport;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Cooldowns;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SchiggyTeleport extends JavaPlugin {

    /** Offene Anfrage. Pro Empfaenger wird nur die neueste gespeichert. */
    public record Request(UUID from, String fromName, boolean here, long expiresAt) {
    }

    private MessageBundle messages;
    private RtpService rtp;
    private final Cooldowns requestCooldowns = new Cooldowns();
    private final Cooldowns rtpCooldowns = new Cooldowns();
    /** Schluessel = Empfaenger der Anfrage. */
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null) {
            getLogger().severe("SchiggyCore ist nicht aktiv - SchiggyTeleport wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        messages = core.messages().bundle(this);
        rtp = new RtpService(this);

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        TeleportCommand executor = new TeleportCommand(this);
        for (String name : List.of("tpa", "tpahere", "tpaccept", "tpdeny", "rtp")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        }

        getLogger().info("SchiggyTeleport aktiviert.");
    }

    public MessageBundle messages() {
        return messages;
    }

    public RtpService rtp() {
        return rtp;
    }

    public Cooldowns requestCooldowns() {
        return requestCooldowns;
    }

    public Cooldowns rtpCooldowns() {
        return rtpCooldowns;
    }

    public Map<UUID, Request> requests() {
        return requests;
    }
}
