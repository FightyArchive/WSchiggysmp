package de.schiggy.bounty;

import de.schiggy.core.SchiggyCore;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.economy.SchiggyEconomy;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SchiggyBounty extends JavaPlugin implements Listener {

    private MessageBundle messages;
    private BountyService service;

    @Override
    public void onEnable() {
        SchiggyCore core = SchiggyCore.getInstance();
        if (core == null || core.database() == null || SchiggyEconomy.getInstance() == null) {
            getLogger().severe("SchiggyCore oder SchiggyEconomy ist nicht aktiv - SchiggyBounty wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        messages = core.messages().bundle(this);
        service = new BountyService(this, core.database());
        try {
            service.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank-Setup fehlgeschlagen - SchiggyBounty wird deaktiviert.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("bounty");
        if (command != null) {
            BountyCommand executor = new BountyCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("SchiggyBounty aktiviert.");
    }

    /** Stirbt ein Spieler durch einen anderen Spieler, bekommt der Moerder das Kopfgeld. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            service.claim(victim, killer);
        }
    }

    public BountyService service() {
        return service;
    }

    public MessageBundle messages() {
        return messages;
    }
}
