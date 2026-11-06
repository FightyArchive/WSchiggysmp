package de.schiggy.crates;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.core.util.Names;
import de.schiggy.crates.CrateService.Crate;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class CrateCommand implements TabExecutor {

    private static final List<String> ADMIN_SUBS = List.of("give", "giveall", "set", "remove", "list", "reload");

    private final SchiggyCrates plugin;

    public CrateCommand(SchiggyCrates plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private CrateService service() {
        return plugin.service();
    }

    private void reply(CommandSender sender, Runnable task) {
        SchiggyScheduler.forSender(plugin, sender, task);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "keys";
        if (ADMIN_SUBS.contains(sub) && !sender.hasPermission("schiggy.crates.admin")) {
            msg().send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "keys" -> keys(sender);
            case "history" -> history(sender, args);
            case "preview" -> preview(sender, args);
            case "give" -> give(sender, args);
            case "giveall" -> giveAll(sender, args);
            case "set" -> set(sender, args);
            case "remove" -> remove(sender);
            case "list" -> {
                msg().send(sender, "list-header");
                for (Crate crate : service().crates().values()) {
                    msg().send(sender, "list-line", "id", crate.id(), "count", String.valueOf(crate.rewards().size()));
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                msg().reload();
                service().reloadCatalog();
                msg().send(sender, "reloaded", "count", String.valueOf(service().crates().size()));
            }
            default -> msg().send(sender, "usage");
        }
        return true;
    }

    private void keys(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        service().allKeys(player.getUniqueId()).thenAccept(map -> reply(sender, () -> {
            if (map.isEmpty()) {
                msg().send(sender, "keys-none");
                return;
            }
            msg().send(sender, "keys-header");
            for (var entry : map.entrySet()) {
                String name = service().crate(entry.getKey()).map(Crate::name).orElse(entry.getKey());
                msg().send(sender, "keys-line", "crate", name, "amount", String.valueOf(entry.getValue()));
            }
        }));
    }

    private void history(CommandSender sender, String[] args) {
        UUID uuid;
        String name;
        if (args.length > 1 && sender.hasPermission("schiggy.crates.admin")) {
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (target == null) {
                msg().send(sender, "player-unknown", "player", args[1]);
                return;
            }
            uuid = target.getUniqueId();
            name = args[1];
        } else if (sender instanceof Player player) {
            uuid = player.getUniqueId();
            name = player.getName();
        } else {
            msg().send(sender, "players-only");
            return;
        }
        service().history(uuid, 10).thenAccept(list -> reply(sender, () -> {
            if (list.isEmpty()) {
                msg().send(sender, "history-empty");
                return;
            }
            msg().send(sender, "history-header", "player", name);
            for (var entry : list) {
                msg().send(sender, "history-line", "reward", entry.reward(), "crate", entry.crate(),
                        "time", Names.duration(System.currentTimeMillis() - entry.time()));
            }
        }));
    }

    private Optional<Crate> findCrate(CommandSender sender, String id) {
        Optional<Crate> crate = service().crate(id);
        if (crate.isEmpty()) {
            msg().send(sender, "unknown-crate", "crate", id, "crates", String.join(", ", service().crates().keySet()));
        }
        return crate;
    }

    private void preview(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        if (args.length < 2) {
            msg().send(sender, "usage");
            return;
        }
        findCrate(sender, args[1]).ifPresent(crate -> plugin.gui().preview(player, crate));
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 4) {
            msg().send(sender, "usage");
            return;
        }
        Optional<Crate> crate = findCrate(sender, args[2]);
        if (crate.isEmpty()) {
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            amount = -1;
        }
        if (amount < 1 || amount > 100_000) {
            msg().send(sender, "invalid-amount");
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            msg().send(sender, "player-unknown", "player", args[1]);
            return;
        }
        int given = amount;
        Crate chosen = crate.get();
        service().addKeys(target.getUniqueId(), chosen.id(), given).thenRun(() -> {
            reply(sender, () -> msg().send(sender, "keys-given", "player", args[1],
                    "amount", String.valueOf(given), "crate", chosen.name()));
            Player receiver = Bukkit.getPlayer(target.getUniqueId());
            if (receiver != null) {
                SchiggyScheduler.entity(plugin, receiver, () -> msg().send(receiver, "keys-received",
                        "amount", String.valueOf(given), "crate", chosen.name()));
            }
        });
    }

    /** /crates giveall [Crate] [Anzahl]: gibt allen Spielern, die gerade online sind, Schluessel. */
    private void giveAll(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg().send(sender, "usage");
            return;
        }
        Optional<Crate> crate = findCrate(sender, args[1]);
        if (crate.isEmpty()) {
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            amount = -1;
        }
        if (amount < 1 || amount > 100_000) {
            msg().send(sender, "invalid-amount");
            return;
        }
        int given = amount;
        Crate chosen = crate.get();
        int count = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            service().addKeys(online.getUniqueId(), chosen.id(), given);
            SchiggyScheduler.entity(plugin, online, () -> msg().send(online, "keys-received",
                    "amount", String.valueOf(given), "crate", chosen.name()));
            count++;
        }
        msg().send(sender, "keys-given-all", "count", String.valueOf(count), "amount", String.valueOf(given),
                "crate", chosen.name());
    }

    private void set(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        if (args.length < 2) {
            msg().send(sender, "usage");
            return;
        }
        Optional<Crate> crate = findCrate(sender, args[1]);
        if (crate.isEmpty()) {
            return;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null || block.getType().isAir()) {
            msg().send(sender, "block-no-target");
            return;
        }
        service().setBlock(block.getLocation(), crate.get().id());
        msg().send(sender, "block-set", "crate", crate.get().id());
    }

    private void remove(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null || block.getType().isAir()) {
            msg().send(sender, "block-no-target");
            return;
        }
        msg().send(sender, service().removeBlock(block.getLocation()) ? "block-removed" : "block-none");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("schiggy.crates.admin");
        if (args.length == 1) {
            out.addAll(List.of("keys", "history", "preview"));
            if (admin) {
                out.addAll(ADMIN_SUBS);
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("preview") || (admin && sub.equals("set"))) {
                out.addAll(service().crates().keySet());
            } else if (admin && (sub.equals("give") || sub.equals("history"))) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    out.add(player.getName());
                }
            }
        } else if (admin && args.length == 2 && args[0].equalsIgnoreCase("giveall")) {
            out.addAll(service().crates().keySet());
        } else if (args.length == 3 && admin && args[0].equalsIgnoreCase("give")) {
            out.addAll(service().crates().keySet());
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
