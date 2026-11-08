package de.schiggy.shop;

import de.schiggy.economy.Currency;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Die aus der config.yml gelesenen Kategorien und Items. */
final class ShopCatalog {

    record ShopItem(String key, Material material, String name, Currency currency, long unitPrice,
                    int dailyLimit, List<String> commands) {
        boolean isCommand() {
            return !commands.isEmpty();
        }
    }

    record ShopCategory(String id, String name, Material icon, int slot, List<ShopItem> items) {
    }

    private final List<ShopCategory> categories;
    private final int menuRows;

    private ShopCatalog(List<ShopCategory> categories, int menuRows) {
        this.categories = categories;
        this.menuRows = menuRows;
    }

    List<ShopCategory> categories() {
        return categories;
    }

    int menuRows() {
        return menuRows;
    }

    Optional<ShopCategory> category(String id) {
        for (ShopCategory category : categories) {
            if (category.id().equalsIgnoreCase(id)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }

    static ShopCatalog load(SchiggyShop plugin) {
        FileConfiguration cfg = plugin.getConfig();
        int rows = Math.max(1, Math.min(6, cfg.getInt("menu.rows", 3)));
        List<ShopCategory> list = new ArrayList<>();
        ConfigurationSection root = cfg.getConfigurationSection("categories");
        int autoSlot = 10;
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                Material icon = Material.matchMaterial(section.getString("icon", "CHEST"));
                if (icon == null || !icon.isItem() || icon.isAir()) {
                    icon = Material.CHEST;
                }
                String name = section.getString("name", "<white>" + id);
                int slot = section.getInt("slot", -1);
                if (slot < 0) {
                    slot = autoSlot++;
                }
                List<ShopItem> items = new ArrayList<>();
                int index = 0;
                for (Map<?, ?> map : section.getMapList("items")) {
                    ShopItem item = parseItem(plugin, id, index++, map);
                    if (item != null) {
                        items.add(item);
                    }
                }
                list.add(new ShopCategory(id, name, icon, slot, List.copyOf(items)));
            }
        }
        return new ShopCatalog(List.copyOf(list), rows);
    }

    private static ShopItem parseItem(SchiggyShop plugin, String categoryId, int index, Map<?, ?> map) {
        Object materialName = map.get("material");
        Material material = materialName == null ? null : Material.matchMaterial(String.valueOf(materialName));
        if (material == null || !material.isItem() || material.isAir()) {
            plugin.getLogger().warning("config.yml: Unbekanntes Item '" + materialName + "' in Kategorie '"
                    + categoryId + "' wird uebersprungen.");
            return null;
        }
        Object currencyName = map.get("currency");
        Currency currency = Currency.byId(currencyName == null ? "money" : String.valueOf(currencyName))
                .orElse(Currency.MONEY);
        if (!(map.get("price") instanceof Number price)) {
            plugin.getLogger().warning("config.yml: Item '" + material + "' in Kategorie '" + categoryId
                    + "' hat keinen gueltigen Preis und wird uebersprungen.");
            return null;
        }
        long unitPrice = currency.toMinor(price.doubleValue());
        if (unitPrice <= 0) {
            plugin.getLogger().warning("config.yml: Item '" + material + "' in Kategorie '" + categoryId
                    + "' hat einen Preis von 0 und wird uebersprungen.");
            return null;
        }
        int dailyLimit = map.get("daily-limit") instanceof Number limit ? Math.max(0, limit.intValue()) : 0;
        String name = map.get("display-name") == null ? null : String.valueOf(map.get("display-name"));
        List<String> commands = new ArrayList<>();
        if (map.get("commands") instanceof List<?> raw) {
            for (Object command : raw) {
                commands.add(String.valueOf(command));
            }
        }
        String key = categoryId + "." + material.name() + (commands.isEmpty() ? "" : "#" + index);
        return new ShopItem(key, material, name, currency, unitPrice, dailyLimit, List.copyOf(commands));
    }
}
