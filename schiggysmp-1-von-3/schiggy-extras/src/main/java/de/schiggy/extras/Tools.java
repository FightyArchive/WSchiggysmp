package de.schiggy.extras;

import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.Inventories;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.economy.Currency;
import de.schiggy.economy.EconomyService;
import de.schiggy.economy.SchiggyEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Amethyst-Werkzeuge mit eigenen IDs und Uses. Die Uses stehen im Item selbst (PersistentDataContainer),
 * bleiben also ueber Neustarts erhalten und lassen sich nicht ueber Amboss, Schleifstein oder Crafting reparieren.
 * Die Werkzeuge sind unzerbrechlich, nur die Uses zaehlen.
 */
final class Tools implements Listener, TabExecutor {

    enum ToolType {
        TREE_CHOPPER("tree_chopper", Material.NETHERITE_AXE, 3000),
        DRILL_3X3("drill_3x3", Material.NETHERITE_PICKAXE, 3000),
        DRILL_3X3X3("drill_3x3x3", Material.NETHERITE_PICKAXE, 3000),
        SELL_AXE("sell_axe", Material.NETHERITE_AXE, 500),
        SHOVEL_3X3("shovel_3x3", Material.NETHERITE_SHOVEL, 3000),
        SELL_WAND("sell_wand", Material.BLAZE_ROD, 500);

        final String id;
        final Material material;
        final int defaultUses;

        ToolType(String id, Material material, int defaultUses) {
            this.id = id;
            this.material = material;
            this.defaultUses = defaultUses;
        }

        static ToolType byId(String id) {
            for (ToolType type : values()) {
                if (type.id.equalsIgnoreCase(id)) {
                    return type;
                }
            }
            return null;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SchiggyExtras plugin;
    private final Spawners spawners;
    private final NamespacedKey idKey;
    private final NamespacedKey usesKey;
    private final NamespacedKey maxKey;

    Tools(SchiggyExtras plugin, Spawners spawners) {
        this.plugin = plugin;
        this.spawners = spawners;
        this.idKey = new NamespacedKey(plugin, "tool_id");
        this.usesKey = new NamespacedKey(plugin, "tool_uses");
        this.maxKey = new NamespacedKey(plugin, "tool_max_uses");
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    private EconomyService economy() {
        return SchiggyEconomy.getInstance().economy();
    }

    // ------------------------------------------------------------------ Items und Uses

    ToolType typeOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? null : ToolType.byId(id);
    }

    boolean isTool(ItemStack item) {
        return typeOf(item) != null;
    }

    private int usesOf(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(usesKey, PersistentDataType.INTEGER, 0);
    }

    private static String number(int value) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(value);
    }

    private int configuredUses(ToolType type) {
        return Math.max(1, plugin.getConfig().getInt("tools." + type.id + ".uses", type.defaultUses));
    }

    /** Schreibt Name und Lore (inklusive der Uses) in das Item. */
    private void render(ItemMeta meta, ToolType type, int uses, int max) {
        meta.displayName(MM.deserialize(msg().raw("tool-name-" + type.id))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize(msg().raw("tool-desc-" + type.id)).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        lore.add(Component.empty());
        lore.add(MM.deserialize(msg().raw("tool-lore-uses", "uses", number(uses), "max", number(max)))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        if (uses <= 0) {
            lore.add(MM.deserialize(msg().raw("tool-lore-empty")).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(lore);
        meta.setUnbreakable(true);
    }

    ItemStack create(ToolType type, int amount) {
        ItemStack item = new ItemStack(type.material, Math.max(1, Math.min(64, amount)));
        int max = configuredUses(type);
        item.editMeta(meta -> {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(idKey, PersistentDataType.STRING, type.id);
            data.set(usesKey, PersistentDataType.INTEGER, max);
            data.set(maxKey, PersistentDataType.INTEGER, max);
            render(meta, type, max, max);
        });
        return item;
    }

    /** Zieht eine Use ab und gibt die uebrigen Uses zurueck. Muss auf dem Thread des Spielers laufen. */
    private int consumeUse(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        ToolType type = typeOf(hand);
        if (type == null) {
            return 0;
        }
        ItemMeta meta = hand.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        int uses = Math.max(0, data.getOrDefault(usesKey, PersistentDataType.INTEGER, 0) - 1);
        int max = data.getOrDefault(maxKey, PersistentDataType.INTEGER, type.defaultUses);
        data.set(usesKey, PersistentDataType.INTEGER, uses);
        render(meta, type, uses, max);
        hand.setItemMeta(meta);
        player.getInventory().setItemInMainHand(hand);
        return uses;
    }

    // ------------------------------------------------------------------ Abbauen

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (SchiggyExtras.INTERNAL.get()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        ToolType type = typeOf(hand);
        if (type == null || type == ToolType.SELL_WAND) {
            return;
        }
        if (!player.hasPermission("schiggy.tools.use")) {
            event.setCancelled(true);
            msg().send(player, "tool-no-permission");
            return;
        }
        if (usesOf(hand) <= 0) {
            event.setCancelled(true);
            msg().send(player, "tool-empty");
            return;
        }
        Block origin = event.getBlock();
        List<Block> extra;
        switch (type) {
            case TREE_CHOPPER -> {
                if (!Tag.LOGS.isTagged(origin.getType())) {
                    return;
                }
                List<Block> tree = collectTree(origin);
                if (tree.size() <= 1) {
                    return;
                }
                tree.remove(origin);
                extra = tree;
            }
            case DRILL_3X3, SHOVEL_3X3 -> extra = area(player, origin, hand, false);
            case DRILL_3X3X3 -> extra = area(player, origin, hand, true);
            case SELL_AXE -> {
                sellAxe(event, player, hand, origin);
                return;
            }
            default -> {
                return;
            }
        }
        if (extra.isEmpty()) {
            return;
        }
        breakBlocks(player, hand, extra);
        consumeUse(player);
    }

    /** Alle zusammenhaengenden Staemme derselben Holzart. Ohne Blaetter am Baum wird nur der eine Block gefaellt. */
    private List<Block> collectTree(Block origin) {
        int max = Math.max(1, plugin.getConfig().getInt("tools.tree_chopper.max-blocks", 400));
        boolean requireLeaves = plugin.getConfig().getBoolean("tools.tree_chopper.require-leaves", true);
        String species = species(origin.getType());
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        boolean leaves = false;
        while (!queue.isEmpty() && seen.size() < max) {
            Block current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Block neighbor = current.getRelative(dx, dy, dz);
                        if (seen.contains(neighbor)) {
                            continue;
                        }
                        Material type = neighbor.getType();
                        if (Tag.LOGS.isTagged(type) && species(type).equals(species)) {
                            seen.add(neighbor);
                            queue.add(neighbor);
                        } else if (Tag.LEAVES.isTagged(type) || Tag.WART_BLOCKS.isTagged(type) || type == Material.SHROOMLIGHT) {
                            leaves = true;
                        }
                    }
                }
            }
        }
        if (requireLeaves && !leaves) {
            return new ArrayList<>(List.of(origin));
        }
        return new ArrayList<>(seen);
    }

    /** OAK_LOG, STRIPPED_OAK_LOG und OAK_WOOD sind dieselbe Holzart "OAK". */
    private static String species(Material material) {
        return material.name().replace("STRIPPED_", "").replace("_LOG", "").replace("_WOOD", "")
                .replace("_STEM", "").replace("_HYPHAE", "");
    }

    private boolean mineable(Block block, ItemStack tool) {
        Material type = block.getType();
        return !type.isAir() && !block.isLiquid() && type.getHardness() >= 0 && type != Material.SPAWNER
                && !spawners.isRegistered(block) && block.isPreferredTool(tool);
    }

    private BlockFace hitFace(Player player, Block origin) {
        RayTraceResult hit = player.rayTraceBlocks(8.0);
        if (hit != null && hit.getHitBlockFace() != null && origin.equals(hit.getHitBlock())) {
            return hit.getHitBlockFace();
        }
        float pitch = player.getLocation().getPitch();
        if (pitch > 60) {
            return BlockFace.UP;
        }
        if (pitch < -60) {
            return BlockFace.DOWN;
        }
        return player.getFacing().getOppositeFace();
    }

    /** 3x3-Flaeche in Blickrichtung, oder 3x3x3 (drei Schichten tief in den Block hinein). */
    private List<Block> area(Player player, Block origin, ItemStack tool, boolean cube) {
        BlockFace face = hitFace(player, origin);
        BlockFace into = face.getOppositeFace();
        int depth = cube ? 3 : 1;
        List<Block> list = new ArrayList<>();
        for (int d = 0; d < depth; d++) {
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    int[] offset = switch (face) {
                        case UP, DOWN -> new int[]{a, 0, b};
                        case NORTH, SOUTH -> new int[]{a, b, 0};
                        default -> new int[]{0, b, a};
                    };
                    int dx = offset[0] + into.getModX() * d;
                    int dy = offset[1] + into.getModY() * d;
                    int dz = offset[2] + into.getModZ() * d;
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    Block block = origin.getRelative(dx, dy, dz);
                    if (mineable(block, tool)) {
                        list.add(block);
                    }
                }
            }
        }
        return list;
    }

    /**
     * Baut die Bloecke ab. Jeder Block wird auf dem Region-Thread seines Ortes bearbeitet (Folia) und vorher
     * ueber ein BlockBreakEvent von Schutz-Plugins (z. B. WorldGuard) geprueft.
     */
    private void breakBlocks(Player player, ItemStack tool, List<Block> blocks) {
        ItemStack toolCopy = tool.clone();
        for (Block block : blocks) {
            Bukkit.getRegionScheduler().execute(plugin, block.getLocation(), () -> {
                if (block.getType().isAir()) {
                    return;
                }
                boolean allowed;
                SchiggyExtras.INTERNAL.set(true);
                try {
                    BlockBreakEvent check = new BlockBreakEvent(block, player);
                    Bukkit.getPluginManager().callEvent(check);
                    allowed = !check.isCancelled();
                } finally {
                    SchiggyExtras.INTERNAL.set(false);
                }
                if (allowed) {
                    block.breakNaturally(toolCopy);
                }
            });
        }
    }

    // ------------------------------------------------------------------ Sell Axe

    private double priceOf(Material material) {
        double price = plugin.getConfig().getDouble("tools.sell_axe.prices." + material.name(), -1);
        if (price < 0 && Tag.LOGS.isTagged(material)) {
            price = plugin.getConfig().getDouble("tools.sell_axe.default-price", 3.0);
        }
        return Math.max(0, price);
    }

    private void sellAxe(BlockBreakEvent event, Player player, ItemStack hand, Block origin) {
        if (!Tag.LOGS.isTagged(origin.getType())) {
            return;
        }
        Collection<ItemStack> drops = origin.getDrops(hand, player);
        event.setDropItems(false);
        long total = 0;
        int items = 0;
        for (ItemStack drop : drops) {
            double price = priceOf(drop.getType());
            if (price > 0) {
                total += Math.round(price * 100) * drop.getAmount();
                items += drop.getAmount();
            } else {
                origin.getWorld().dropItemNaturally(origin.getLocation().add(0.5, 0.5, 0.5), drop);
            }
        }
        if (total <= 0) {
            return;
        }
        int left = consumeUse(player);
        long amount = total;
        int sold = items;
        economy().deposit(player.getUniqueId(), Currency.MONEY, amount, "SELL_AXE", "items=" + sold)
                .thenAccept(result -> SchiggyScheduler.entity(plugin, player, () -> {
                    if (result.status() == EconomyService.Status.SUCCESS) {
                        msg().send(player, "tool-sold", "items", String.valueOf(sold),
                                "amount", economy().format(Currency.MONEY, amount), "uses", number(left));
                    } else {
                        msg().send(player, "error");
                    }
                }));
    }

    // ------------------------------------------------------------------ Sell Wand

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (typeOf(hand) != ToolType.SELL_WAND) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission("schiggy.tools.use")) {
            msg().send(player, "tool-no-permission");
            return;
        }
        if (usesOf(hand) <= 0) {
            msg().send(player, "tool-empty");
            return;
        }
        sellSpawners(player, event.getClickedBlock());
    }

    /** Verkauft deine Spawner im Bereich um den angeklickten Block. Jeder Spawner wird genau einmal ausgezahlt. */
    private void sellSpawners(Player player, Block center) {
        int size = Math.max(1, plugin.getConfig().getInt("tools.sell_wand.size", 10));
        int vertical = Math.max(0, plugin.getConfig().getInt("tools.sell_wand.vertical", 10));
        int minX = center.getX() - size / 2;
        int minZ = center.getZ() - size / 2;
        World world = center.getWorld();
        boolean admin = player.hasPermission("schiggy.spawners.admin");
        List<Spawners.Spawner> candidates = new ArrayList<>();
        for (Spawners.Spawner s : spawners.inBox(world.getName(), minX, minX + size - 1,
                center.getY() - vertical, center.getY() + vertical, minZ, minZ + size - 1)) {
            if (admin || s.owner.equals(player.getUniqueId())) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            msg().send(player, "wand-none");
            return;
        }
        long total = 0;
        int count = 0;
        int stacks = 0;
        int blocked = 0;
        for (Spawners.Spawner s : candidates) {
            Block block = world.getBlockAt(s.x, s.y, s.z);
            boolean allowed;
            SchiggyExtras.INTERNAL.set(true);
            try {
                BlockBreakEvent check = new BlockBreakEvent(block, player);
                Bukkit.getPluginManager().callEvent(check);
                allowed = !check.isCancelled();
            } finally {
                SchiggyExtras.INTERNAL.set(false);
            }
            if (!allowed) {
                blocked++;
                continue;
            }
            long value = spawners.value(s);
            if (!spawners.remove(s)) {
                continue; // wurde schon verkauft oder abgebaut
            }
            total += value;
            count++;
            stacks += s.stack;
            Location location = block.getLocation();
            Bukkit.getRegionScheduler().execute(plugin, location, () -> block.setType(Material.AIR));
        }
        if (count == 0) {
            if (blocked > 0) {
                msg().send(player, "wand-protected", "count", String.valueOf(blocked));
            }
            return;
        }
        int left = consumeUse(player);
        long amount = total;
        int sold = count;
        int stacked = stacks;
        economy().deposit(player.getUniqueId(), Currency.MONEY, amount, "SELL_WAND", "spawners=" + sold)
                .thenAccept(result -> SchiggyScheduler.entity(plugin, player, () -> {
                    if (result.status() == EconomyService.Status.SUCCESS) {
                        msg().send(player, "wand-sold", "count", String.valueOf(sold), "stacks", String.valueOf(stacked),
                                "amount", economy().format(Currency.MONEY, amount), "uses", number(left));
                    } else {
                        plugin.getLogger().severe("Auszahlung der Sell Wand an " + player.getName() + " fehlgeschlagen ("
                                + result.status() + "), Betrag " + amount);
                        msg().send(player, "error");
                    }
                }));
        if (blocked > 0) {
            msg().send(player, "wand-protected", "count", String.valueOf(blocked));
        }
    }

    // ------------------------------------------------------------------ Keine Reparatur

    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        if (isTool(event.getInventory().getFirstItem()) || isTool(event.getInventory().getSecondItem())) {
            event.setResult(null);
        }
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (isTool(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onGrindstone(PrepareGrindstoneEvent event) {
        if (isTool(event.getInventory().getUpperItem()) || isTool(event.getInventory().getLowerItem())) {
            event.setResult(null);
        }
    }

    // ------------------------------------------------------------------ Befehle

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("schiggy.tools.admin")) {
            msg().send(sender, "no-permission");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "list" -> {
                msg().send(sender, "tools-list-header");
                for (ToolType type : ToolType.values()) {
                    msg().send(sender, "tools-list-line", "id", type.id, "uses", number(configuredUses(type)));
                }
            }
            case "reload" -> {
                plugin.reload();
                msg().send(sender, "reloaded");
            }
            case "give" -> give(sender, args);
            default -> msg().send(sender, "tools-usage");
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg().send(sender, "tools-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg().send(sender, "player-offline");
            return;
        }
        ToolType type = ToolType.byId(args[2]);
        if (type == null) {
            StringBuilder ids = new StringBuilder();
            for (ToolType known : ToolType.values()) {
                if (ids.length() > 0) {
                    ids.append(", ");
                }
                ids.append(known.id);
            }
            msg().send(sender, "tools-unknown", "tool", args[2], "tools", ids.toString());
            return;
        }
        int amount = 1;
        if (args.length > 3) {
            try {
                amount = Math.max(1, Math.min(36, Integer.parseInt(args[3])));
            } catch (NumberFormatException e) {
                msg().send(sender, "tools-usage");
                return;
            }
        }
        int count = amount;
        SchiggyScheduler.entity(plugin, target, () -> {
            for (int i = 0; i < count; i++) {
                Inventories.give(target, create(type, 1));
            }
            msg().send(target, "tools-received", "amount", String.valueOf(count), "tool", type.id);
        });
        msg().send(sender, "tools-given", "player", target.getName(), "amount", String.valueOf(count), "tool", type.id);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("schiggy.tools.admin")) {
            return out;
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            out.addAll(List.of("give", "list", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                out.add(online.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (ToolType type : ToolType.values()) {
                out.add(type.id);
            }
        }
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
