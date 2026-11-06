package de.schiggy.core.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Kleiner Helfer fuer GUI-Items. Texte im MiniMessage-Format, ohne kursive Standard-Schrift. */
public final class ItemBuilder {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ItemStack item;

    private ItemBuilder(Material material) {
        this.item = new ItemStack(material);
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material);
    }

    /** Spielerkopf mit dem Skin des angegebenen Spielers. */
    public static ItemBuilder head(org.bukkit.OfflinePlayer owner) {
        ItemBuilder builder = new ItemBuilder(Material.PLAYER_HEAD);
        builder.item.editMeta(org.bukkit.inventory.meta.SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        return builder;
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(Math.max(1, Math.min(64, amount)));
        return this;
    }

    public ItemBuilder name(String miniMessage) {
        item.editMeta(meta -> meta.displayName(noItalic(MM.deserialize(miniMessage))));
        return this;
    }

    public ItemBuilder lore(String... lines) {
        return lore(List.of(lines));
    }

    public ItemBuilder lore(List<String> lines) {
        List<Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(noItalic(MM.deserialize(line)));
        }
        item.editMeta(meta -> meta.lore(lore));
        return this;
    }

    public ItemStack build() {
        return item.clone();
    }

    /** Haengt Zeilen an die vorhandene Beschreibung eines fertigen Items an (z. B. Auktions-Items). */
    public static ItemStack appendLore(ItemStack stack, List<String> lines) {
        ItemStack copy = stack.clone();
        copy.editMeta(meta -> {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            for (String line : lines) {
                lore.add(noItalic(MM.deserialize(line)));
            }
            meta.lore(lore);
        });
        return copy;
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
