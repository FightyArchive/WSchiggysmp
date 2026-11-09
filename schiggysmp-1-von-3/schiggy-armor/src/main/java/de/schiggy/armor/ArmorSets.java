package de.schiggy.armor;

import de.schiggy.core.message.MessageBundle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Die aus der config.yml gelesenen Ruestungs-Sets. */
final class ArmorSets {

    static final List<String> PIECES = List.of("helmet", "chestplate", "leggings", "boots");

    record EffectSpec(PotionEffectType type, int amplifier, String label) {
    }

    /** Bonus ab einer bestimmten Anzahl getragener Teile. */
    record Bonus(int pieces, List<EffectSpec> effects) {
    }

    /** Bonus, der nur von einem bestimmten Teil kommt (z. B. Helm). scale 0 = keine Groessenaenderung. */
    record PieceBonus(double scale, List<EffectSpec> effects) {
    }

    record ArmorSet(String id, String name, boolean unbreakable, Color color, Map<Enchantment, Integer> enchants,
                    Map<String, Material> pieces, List<Bonus> bonuses, Map<String, PieceBonus> pieceBonuses) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SchiggyArmor plugin;
    private final NamespacedKey setKey;
    private final NamespacedKey pieceKey;
    private volatile Map<String, ArmorSet> sets = Map.of();

    ArmorSets(SchiggyArmor plugin) {
        this.plugin = plugin;
        this.setKey = new NamespacedKey(plugin, "set");
        this.pieceKey = new NamespacedKey(plugin, "piece");
        reload();
    }

    Map<String, ArmorSet> all() {
        return sets;
    }

    ArmorSet get(String id) {
        return sets.get(id.toLowerCase(Locale.ROOT));
    }

    void reload() {
        Map<String, ArmorSet> loaded = new LinkedHashMap<>();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("sets");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                ArmorSet set = parseSet(id, section);
                if (!set.pieces().isEmpty()) {
                    loaded.put(id.toLowerCase(Locale.ROOT), set);
                }
            }
        }
        this.sets = loaded;
    }

    private List<EffectSpec> parseEffects(String setId, List<?> raw) {
        List<EffectSpec> effects = new ArrayList<>();
        for (Object entry : raw) {
            String[] parts = String.valueOf(entry).split(":");
            PotionEffectType type = Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(parts[0].toLowerCase(Locale.ROOT)));
            if (type == null) {
                plugin.getLogger().warning("Set " + setId + ": unbekannter Effekt '" + entry + "'");
                continue;
            }
            int amplifier = 0;
            if (parts.length > 1) {
                try {
                    amplifier = Math.max(0, Integer.parseInt(parts[1]));
                } catch (NumberFormatException ignored) {
                    // Standard: Stufe 0
                }
            }
            effects.add(new EffectSpec(type, amplifier, parts[0].replace('_', ' ') + " " + (amplifier + 1)));
        }
        return effects;
    }

    private ArmorSet parseSet(String id, ConfigurationSection section) {
        Map<String, Material> pieces = new LinkedHashMap<>();
        ConfigurationSection pieceSection = section.getConfigurationSection("pieces");
        if (pieceSection != null) {
            for (String piece : PIECES) {
                Material material = Material.matchMaterial(pieceSection.getString(piece + ".material", ""));
                if (material != null && material.isItem()) {
                    pieces.put(piece, material);
                }
            }
        }
        Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
        for (String entry : section.getStringList("enchants")) {
            String[] parts = entry.split(":");
            Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0].toLowerCase(Locale.ROOT)));
            if (enchantment == null) {
                plugin.getLogger().warning("Set " + id + ": unbekannte Verzauberung '" + entry + "'");
                continue;
            }
            int level = 1;
            if (parts.length > 1) {
                try {
                    level = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    // Standard: Stufe 1
                }
            }
            enchants.put(enchantment, level);
        }
        List<Bonus> bonuses = new ArrayList<>();
        for (Map<?, ?> map : section.getMapList("bonuses")) {
            if (!(map.get("pieces") instanceof Number count) || !(map.get("effects") instanceof List<?> raw)) {
                continue;
            }
            bonuses.add(new Bonus(count.intValue(), parseEffects(id, raw)));
        }
        bonuses.sort((a, b) -> Integer.compare(a.pieces(), b.pieces()));

        Map<String, PieceBonus> pieceBonuses = new LinkedHashMap<>();
        ConfigurationSection bonusSection = section.getConfigurationSection("piece-bonuses");
        if (bonusSection != null) {
            for (String piece : PIECES) {
                ConfigurationSection entry = bonusSection.getConfigurationSection(piece);
                if (entry != null) {
                    double scale = entry.getDouble("scale", 0);
                    pieceBonuses.put(piece, new PieceBonus(scale > 0 && scale < 1 ? scale : 0,
                            parseEffects(id, entry.getStringList("effects"))));
                }
            }
        }

        Color color = null;
        String colorText = section.getString("color");
        if (colorText != null) {
            try {
                color = Color.fromRGB(Integer.parseInt(colorText.replace("#", ""), 16));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Set " + id + ": ungueltige Farbe '" + colorText + "'");
            }
        }
        return new ArmorSet(id.toLowerCase(Locale.ROOT), section.getString("name", id),
                section.getBoolean("unbreakable", false), color, enchants, pieces, bonuses, pieceBonuses);
    }

    // ------------------------------------------------------------------ Items

    private static Component plain(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String effectLabels(List<EffectSpec> effects) {
        StringBuilder out = new StringBuilder();
        for (EffectSpec effect : effects) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(effect.label());
        }
        return out.toString();
    }

    /** Baut ein Teil eines Sets. */
    ItemStack create(ArmorSet set, String piece, MessageBundle msg) {
        ItemStack item = new ItemStack(set.pieces().get(piece));
        String pieceName = piece.substring(0, 1).toUpperCase(Locale.ROOT) + piece.substring(1);
        item.editMeta(meta -> {
            meta.displayName(plain(MM.deserialize(set.name() + " <white>" + pieceName)));
            List<Component> lore = new ArrayList<>();
            lore.add(plain(MM.deserialize(msg.raw("item-lore-header", "set", "") + set.name())));
            PieceBonus own = set.pieceBonuses().get(piece);
            if (own != null) {
                if (own.scale() > 0) {
                    lore.add(plain(MM.deserialize(msg.raw("item-lore-scale",
                            "percent", String.valueOf(Math.round(own.scale() * 100))))));
                }
                if (!own.effects().isEmpty()) {
                    lore.add(plain(MM.deserialize(msg.raw("item-lore-piece", "effects", effectLabels(own.effects())))));
                }
            }
            for (Bonus bonus : set.bonuses()) {
                lore.add(plain(MM.deserialize(msg.raw("item-lore-bonus", "pieces", String.valueOf(bonus.pieces()),
                        "effects", effectLabels(bonus.effects())))));
            }
            meta.lore(lore);
            for (Map.Entry<Enchantment, Integer> enchant : set.enchants().entrySet()) {
                meta.addEnchant(enchant.getKey(), enchant.getValue(), true);
            }
            meta.setUnbreakable(set.unbreakable());
            if (set.color() != null && meta instanceof LeatherArmorMeta leather) {
                leather.setColor(set.color());
            }
            meta.getPersistentDataContainer().set(setKey, PersistentDataType.STRING, set.id());
            meta.getPersistentDataContainer().set(pieceKey, PersistentDataType.STRING, piece);
        });
        return item;
    }

    /** Set-Id eines getragenen Teils oder null. */
    String setOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(setKey, PersistentDataType.STRING);
    }

    /** Teil-Name (helmet, chestplate, ...) eines Set-Items oder null. */
    String pieceOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(pieceKey, PersistentDataType.STRING);
    }
}
