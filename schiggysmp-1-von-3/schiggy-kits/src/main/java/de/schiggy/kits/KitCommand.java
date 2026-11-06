package de.schiggy.kits;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Names;
import de.schiggy.kits.KitService.EffectSpec;
import de.schiggy.kits.KitService.Kit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KitCommand implements TabExecutor {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,16}");
    private static final Pattern TIME = Pattern.compile("(\\d+)([smhd]?)");
    private static final List<String> ADMIN_SUBS = List.of("create", "update", "delete", "cooldown", "icon",
            "display", "effect", "give", "preview", "reload");

    private final SchiggyKits plugin;

    public KitCommand(SchiggyKits plugin) {
        this.plugin = plugin;
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private KitService service() {
        return plugin.service();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                msg().send(sender, "players-only");
                return true;
            }
            if (!player.hasPermission("schiggy.kit.use")) {
                msg().send(sender, "no-permission");
                return true;
            }
            plugin.gui().open(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (ADMIN_SUBS.contains(sub)) {
            admin(sender, sub, args);
            return true;
        }
        // /kit <name> holt das Kit direkt ab
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("schiggy.kit.use")) {
            msg().send(sender, "no-permission");
            return true;
        }
        Optional<Kit> kit = service().get(args[0]);
        if (kit.isEmpty()) {
            msg().send(sender, "kit-unknown", "kit", args[0]);
            return true;
        }
        plugin.claim(player, kit.get(), false);
        return true;
    }

    // ------------------------------------------------------------------ Admin

    private void admin(CommandSender sender, String sub, String[] args) {
        if (!sender.hasPermission("schiggy.kit.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (sub.equals("reload")) {
            plugin.reloadConfig();
            msg().reload();
            service().reload().thenAccept(count -> plugin.reply(sender, () ->
                    msg().send(sender, "reloaded", "count", String.valueOf(count))));
            return;
        }
        if (sub.equals("give")) {
            give(sender, args);
            return;
        }
        if (args.length < 2) {
            for (String line : msg().rawList("admin-usage")) {
                sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(line));
            }
            return;
        }
        String name = args[1];
        Optional<Kit> existing = service().get(name);

        switch (sub) {
            case "create" -> create(sender, name, args, existing);
            case "update" -> update(sender, name, existing);
            case "delete" -> {
                if (existing.isEmpty()) {
                    msg().send(sender, "kit-unknown", "kit", name);
                    return;
                }
                String kitName = existing.get().name();
                service().delete(kitName).thenRun(() -> plugin.reply(sender, () ->
                        msg().send(sender, "kit-deleted", "kit", kitName)));
            }
            case "cooldown" -> cooldown(sender, name, args, existing);
            case "icon" -> icon(sender, name, existing);
            case "display" -> display(sender, name, args, existing);
            case "effect" -> effect(sender, name, args, existing);
            case "preview" -> {
                if (!(sender instanceof Player player)) {
                    msg().send(sender, "players-only");
                } else if (existing.isEmpty()) {
                    msg().send(sender, "kit-unknown", "kit", name);
                } else {
                    plugin.gui().preview(player, existing.get());
                }
            }
            default -> msg().send(sender, "error");
        }
    }

    /** Inhalt des Admin-Inventars (Items, Ruestung, Zweithand) ohne leere Slots. */
    private List<ItemStack> snapshot(Player player) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                items.add(stack.clone());
            }
        }
        return items;
    }

    private void create(CommandSender sender, String name, String[] args, Optional<Kit> existing) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        if (!NAME.matcher(name).matches()) {
            msg().send(sender, "invalid-name");
            return;
        }
        if (existing.isPresent()) {
            msg().send(sender, "kit-exists", "kit", existing.get().name());
            return;
        }
        long cooldown = 0;
        if (args.length > 2) {
            cooldown = parseSeconds(args[2]);
            if (cooldown < 0) {
                msg().send(sender, "invalid-time");
                return;
            }
        }
        List<ItemStack> items = snapshot(player);
        if (items.isEmpty()) {
            msg().send(sender, "empty-inventory");
            return;
        }
        Kit kit = new Kit(name, "<white><bold>" + name, Material.CHEST, cooldown, items, List.of());
        service().save(kit).thenRun(() -> plugin.reply(sender, () ->
                msg().send(sender, "kit-created", "kit", name, "count", String.valueOf(items.size()))));
    }

    private void update(CommandSender sender, String name, Optional<Kit> existing) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        if (existing.isEmpty()) {
            msg().send(sender, "kit-unknown", "kit", name);
            return;
        }
        List<ItemStack> items = snapshot(player);
        if (items.isEmpty()) {
            msg().send(sender, "empty-inventory");
            return;
        }
        Kit old = existing.get();
        Kit kit = new Kit(old.name(), old.display(), old.icon(), old.cooldownSeconds(), items, old.effects());
        service().save(kit).thenRun(() -> plugin.reply(sender, () ->
                msg().send(sender, "kit-updated", "kit", old.name(), "count", String.valueOf(items.size()))));
    }

    private void cooldown(CommandSender sender, String name, String[] args, Optional<Kit> existing) {
        if (existing.isEmpty()) {
            msg().send(sender, "kit-unknown", "kit", name);
            return;
        }
        long seconds = args.length > 2 ? parseSeconds(args[2]) : -1;
        if (seconds < 0) {
            msg().send(sender, "invalid-time");
            return;
        }
        Kit old = existing.get();
        Kit kit = new Kit(old.name(), old.display(), old.icon(), seconds, old.items(), old.effects());
        service().save(kit).thenRun(() -> plugin.reply(sender, () -> msg().send(sender, "kit-cooldown-set",
                "kit", old.name(), "time", seconds == 0 ? "-" : Names.duration(seconds * 1000L))));
    }

    private void icon(CommandSender sender, String name, Optional<Kit> existing) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        if (existing.isEmpty()) {
            msg().send(sender, "kit-unknown", "kit", name);
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            msg().send(sender, "kit-icon-none");
            return;
        }
        Kit old = existing.get();
        Kit kit = new Kit(old.name(), old.display(), hand.getType(), old.cooldownSeconds(), old.items(), old.effects());
        service().save(kit).thenRun(() -> plugin.reply(sender, () ->
                msg().send(sender, "kit-icon-set", "kit", old.name())));
    }

    private void display(CommandSender sender, String name, String[] args, Optional<Kit> existing) {
        if (existing.isEmpty()) {
            msg().send(sender, "kit-unknown", "kit", name);
            return;
        }
        if (args.length < 3) {
            msg().send(sender, "error");
            return;
        }
        String text = String.join(" ", List.of(args).subList(2, args.length));
        Kit old = existing.get();
        Kit kit = new Kit(old.name(), text, old.icon(), old.cooldownSeconds(), old.items(), old.effects());
        service().save(kit).thenRun(() -> plugin.reply(sender, () ->
                msg().send(sender, "kit-display-set", "kit", old.name())));
    }

    private void effect(CommandSender sender, String name, String[] args, Optional<Kit> existing) {
        if (existing.isEmpty()) {
            msg().send(sender, "kit-unknown", "kit", name);
            return;
        }
        Kit old = existing.get();
        if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
            Kit kit = new Kit(old.name(), old.display(), old.icon(), old.cooldownSeconds(), old.items(), List.of());
            service().save(kit).thenRun(() -> plugin.reply(sender, () ->
                    msg().send(sender, "kit-effects-cleared", "kit", old.name())));
            return;
        }
        if (args.length < 6 || !args[2].equalsIgnoreCase("add")) {
            msg().send(sender, "error");
            return;
        }
        String typeName = args[3].toLowerCase(Locale.ROOT);
        if (Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(typeName)) == null) {
            msg().send(sender, "invalid-effect", "effect", args[3]);
            return;
        }
        int seconds;
        int amplifier;
        try {
            seconds = Integer.parseInt(args[4]);
            amplifier = Integer.parseInt(args[5]);
        } catch (NumberFormatException e) {
            msg().send(sender, "invalid-number");
            return;
        }
        List<EffectSpec> effects = new ArrayList<>(old.effects());
        effects.removeIf(effect -> effect.type().equalsIgnoreCase(typeName));
        effects.add(new EffectSpec(typeName, Math.max(1, seconds), Math.max(0, amplifier)));
        Kit kit = new Kit(old.name(), old.display(), old.icon(), old.cooldownSeconds(), old.items(), effects);
        service().save(kit).thenRun(() -> plugin.reply(sender, () ->
                msg().send(sender, "kit-effect-added", "kit", old.name(), "effect", typeName)));
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg().send(sender, "error");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg().send(sender, "player-offline");
            return;
        }
        Optional<Kit> kit = service().get(args[2]);
        if (kit.isEmpty()) {
            msg().send(sender, "kit-unknown", "kit", args[2]);
            return;
        }
        plugin.claim(target, kit.get(), true);
        msg().send(sender, "kit-given", "kit", kit.get().name(), "player", target.getName());
    }

    /** 30m, 12h, 1d, 90s oder nur Sekunden. -1 bei ungueltiger Eingabe. */
    static long parseSeconds(String text) {
        Matcher matcher = TIME.matcher(text.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return -1;
        }
        long value;
        try {
            value = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
        return switch (matcher.group(2)) {
            case "m" -> value * 60;
            case "h" -> value * 3600;
            case "d" -> value * 86400;
            default -> value;
        };
    }

    // ------------------------------------------------------------------ Tab-Completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("schiggy.kit.admin");
        if (args.length == 1) {
            for (Kit kit : service().all()) {
                out.add(kit.name());
            }
            if (admin) {
                out.addAll(ADMIN_SUBS);
            }
        } else if (admin && args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("give")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    out.add(player.getName());
                }
            } else if (!sub.equals("create") && !sub.equals("reload") && ADMIN_SUBS.contains(sub)) {
                for (Kit kit : service().all()) {
                    out.add(kit.name());
                }
            }
        } else if (admin && args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("effect")) {
                out.addAll(List.of("add", "clear"));
            } else if (sub.equals("give")) {
                for (Kit kit : service().all()) {
                    out.add(kit.name());
                }
            } else if (sub.equals("cooldown")) {
                out.addAll(List.of("0", "30m", "12h", "1d"));
            }
        } else if (admin && args.length == 4 && args[0].equalsIgnoreCase("effect")) {
            out.addAll(List.of("speed", "strength", "regeneration", "resistance", "fire_resistance",
                    "night_vision", "haste", "jump_boost", "absorption"));
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
