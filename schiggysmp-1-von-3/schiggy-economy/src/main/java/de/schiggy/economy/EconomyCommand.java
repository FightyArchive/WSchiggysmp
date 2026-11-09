package de.schiggy.economy;

import de.schiggy.core.gui.SchiggyGui;
import de.schiggy.core.gui.ItemBuilder;
import de.schiggy.core.message.MessageBundle;
import de.schiggy.core.util.SchiggyScheduler;
import de.schiggy.economy.EconomyService.BaltopEntry;
import de.schiggy.economy.EconomyService.PlayerRef;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

public final class EconomyCommand implements TabExecutor {

    private final SchiggyEconomy plugin;

    public EconomyCommand(SchiggyEconomy plugin) {
        this.plugin = plugin;
    }

    private EconomyService economy() {
        return plugin.economy();
    }

    private MessageBundle msg() {
        return plugin.messages();
    }

    /** Antworten kommen vom Datenbank-Thread; Nachrichten gehen sicher ueber den Thread des Absenders. */
    private void reply(CommandSender sender, Runnable task) {
        SchiggyScheduler.forSender(plugin, sender, task);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> balance(sender, args, Currency.MONEY);
            case "shards" -> balance(sender, args, Currency.SHARDS);
            case "pay" -> pay(sender, args);
            case "baltop" -> baltop(sender, args);
            case "eco" -> eco(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ /bal und /shards

    private void balance(CommandSender sender, String[] args, Currency currency) {
        if (!sender.hasPermission("schiggy.economy.balance")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                msg().send(sender, "players-only");
                return;
            }
            economy().getBalance(player.getUniqueId(), currency).thenAccept(balance -> reply(sender, () ->
                    msg().send(sender, "balance-self-" + currency.id(),
                            "amount", economy().format(currency, balance))));
            return;
        }
        if (!sender.hasPermission("schiggy.economy.balance.others")) {
            msg().send(sender, "no-permission");
            return;
        }
        economy().findPlayer(args[0]).thenAccept(found -> {
            if (found.isEmpty()) {
                reply(sender, () -> msg().send(sender, "player-not-found", "player", args[0]));
                return;
            }
            PlayerRef target = found.get();
            economy().getBalance(target.uuid(), currency).thenAccept(balance -> reply(sender, () ->
                    msg().send(sender, "balance-other-" + currency.id(),
                            "player", target.name(), "amount", economy().format(currency, balance))));
        });
    }

    // ------------------------------------------------------------------ /pay

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        if (!sender.hasPermission("schiggy.economy.pay")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            msg().send(sender, "pay-usage");
            return;
        }
        OptionalLong parsed = AmountParser.parse(args[1], Currency.MONEY, false);
        if (parsed.isEmpty()) {
            msg().send(sender, "invalid-amount");
            return;
        }
        long amount = parsed.getAsLong();

        FileConfiguration cfg = plugin.getConfig();
        long min = Currency.MONEY.toMinor(cfg.getDouble("limits.pay.min", 1));
        long max = Currency.MONEY.toMinor(cfg.getDouble("limits.pay.max", 0));
        if (amount < min) {
            msg().send(sender, "pay-too-low", "amount", economy().format(Currency.MONEY, min));
            return;
        }
        if (max > 0 && amount > max) {
            msg().send(sender, "pay-too-high", "amount", economy().format(Currency.MONEY, max));
            return;
        }

        economy().findPlayer(args[0]).thenAccept(found -> {
            if (found.isEmpty()) {
                reply(sender, () -> msg().send(sender, "player-not-found", "player", args[0]));
                return;
            }
            PlayerRef target = found.get();
            if (target.uuid().equals(player.getUniqueId())) {
                reply(sender, () -> msg().send(sender, "pay-self"));
                return;
            }
            reply(sender, () -> {
                if (confirmEnabled(player)) {
                    openPayConfirm(player, target, amount);
                } else {
                    doPay(player, target, amount);
                }
            });
        });
    }

    private boolean confirmEnabled(Player player) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("SchiggySettings")) {
            return false;
        }
        try {
            return SettingsHook.payConfirm(player.getUniqueId());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Bestaetigungs-Menue: Kopf des Empfaengers in der Mitte, links Bestaetigen, rechts Abbrechen. */
    private void openPayConfirm(Player player, PlayerRef target, long amount) {
        String formatted = economy().format(Currency.MONEY, amount);
        SchiggyGui gui = new SchiggyGui(msg().component("pay-confirm-title"), 3);
        gui.set(13, ItemBuilder.head(Bukkit.getOfflinePlayer(target.uuid()))
                .name(msg().raw("gui-pay-head-name", "player", target.name()))
                .lore(msg().rawList("gui-pay-head-lore", "player", target.name(), "amount", formatted))
                .build());
        gui.set(11, ItemBuilder.of(Material.LIME_CONCRETE)
                .name(msg().raw("gui-pay-confirm-name"))
                .lore(msg().rawList("gui-pay-confirm-lore", "player", target.name(), "amount", formatted))
                .build(), event -> {
                    player.closeInventory();
                    doPay(player, target, amount);
                });
        gui.set(15, ItemBuilder.of(Material.RED_CONCRETE)
                .name(msg().raw("gui-pay-cancel-name"))
                .lore(msg().rawList("gui-pay-cancel-lore"))
                .build(), event -> {
                    player.closeInventory();
                    msg().send(player, "pay-cancelled");
                });
        gui.open(player);
    }

    /** Fuehrt die Zahlung aus. Muss auf dem Thread des Spielers laufen. */
    private void doPay(Player player, PlayerRef target, long amount) {
        economy().transfer(player.getUniqueId(), target.uuid(), Currency.MONEY, amount, "pay")
                .thenAccept(result -> reply(player, () -> {
                    switch (result.status()) {
                        case SUCCESS -> {
                            String formatted = economy().format(Currency.MONEY, amount);
                            msg().send(player, "pay-sent", "player", target.name(), "amount", formatted);
                            Player online = Bukkit.getPlayer(target.uuid());
                            if (online != null) {
                                SchiggyScheduler.entity(plugin, online, () -> msg().send(online, "pay-received",
                                        "player", player.getName(), "amount", formatted));
                            }
                        }
                        case INSUFFICIENT_FUNDS -> msg().send(player, "insufficient-funds");
                        case LIMIT_EXCEEDED -> msg().send(player, "receiver-limit");
                        case INVALID_AMOUNT -> msg().send(player, "invalid-amount");
                        default -> msg().send(player, "error");
                    }
                }));
    }

    // ------------------------------------------------------------------ /baltop

    private void baltop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("schiggy.economy.baltop")) {
            msg().send(sender, "no-permission");
            return;
        }
        List<BaltopEntry> list = economy().baltop();
        if (list.isEmpty()) {
            msg().send(sender, "baltop-empty");
            return;
        }
        int perPage = 10;
        int pages = (list.size() + perPage - 1) / perPage;
        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
                // ungueltige Seite: Seite 1 anzeigen
            }
        }
        page = Math.max(1, Math.min(page, pages));

        msg().send(sender, "baltop-header", "page", String.valueOf(page), "pages", String.valueOf(pages));
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, list.size());
        for (int i = start; i < end; i++) {
            BaltopEntry entry = list.get(i);
            msg().send(sender, "baltop-line", "rank", String.valueOf(i + 1), "player", entry.name(),
                    "amount", economy().format(Currency.MONEY, entry.amount()));
        }
        if (page < pages) {
            msg().send(sender, "baltop-footer", "next", String.valueOf(page + 1));
        }
    }

    // ------------------------------------------------------------------ /eco

    private void eco(CommandSender sender, String[] args) {
        if (!sender.hasPermission("schiggy.economy.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            msg().reload();
            msg().send(sender, "reloaded");
            return;
        }
        if (args.length < 4) {
            msg().send(sender, "eco-usage");
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!action.equals("give") && !action.equals("take") && !action.equals("set")) {
            msg().send(sender, "eco-usage");
            return;
        }
        Optional<Currency> currencyOpt = Currency.byId(args[2]);
        if (currencyOpt.isEmpty()) {
            msg().send(sender, "invalid-currency");
            return;
        }
        Currency currency = currencyOpt.get();
        OptionalLong parsed = AmountParser.parse(args[3], currency, action.equals("set"));
        if (parsed.isEmpty()) {
            msg().send(sender, "invalid-amount");
            return;
        }
        long amount = parsed.getAsLong();

        economy().findPlayer(args[1]).thenAccept(found -> {
            if (found.isEmpty()) {
                reply(sender, () -> msg().send(sender, "player-not-found", "player", args[1]));
                return;
            }
            PlayerRef target = found.get();
            String note = sender.getName();
            CompletableFuture<EconomyService.TxResult> future = switch (action) {
                case "give" -> economy().deposit(target.uuid(), currency, amount, "ADMIN_GIVE", note);
                case "take" -> economy().withdraw(target.uuid(), currency, amount, "ADMIN_TAKE", note);
                default -> economy().set(target.uuid(), currency, amount, "ADMIN_SET", note);
            };
            future.thenAccept(result -> reply(sender, () -> {
                switch (result.status()) {
                    case SUCCESS -> msg().send(sender, "eco-" + action,
                            "player", target.name(),
                            "amount", economy().format(currency, amount),
                            "balance", economy().format(currency, result.balance()));
                    case INSUFFICIENT_FUNDS -> msg().send(sender, "eco-insufficient", "player", target.name());
                    case LIMIT_EXCEEDED -> msg().send(sender, "limit-exceeded");
                    case INVALID_AMOUNT -> msg().send(sender, "invalid-amount");
                    default -> msg().send(sender, "error");
                }
            }));
        });
    }

    // ------------------------------------------------------------------ Tab-Completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "balance", "shards" -> {
                if (args.length == 1 && sender.hasPermission("schiggy.economy.balance.others")) {
                    out.addAll(onlineNames());
                }
            }
            case "pay" -> {
                if (args.length == 1) {
                    out.addAll(onlineNames());
                }
            }
            case "eco" -> {
                if (!sender.hasPermission("schiggy.economy.admin")) {
                    return List.of();
                }
                if (args.length == 1) {
                    out.addAll(List.of("give", "take", "set", "reload"));
                } else if (args.length == 2) {
                    out.addAll(onlineNames());
                } else if (args.length == 3) {
                    out.addAll(List.of("money", "shards"));
                }
            }
            default -> {
            }
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }

    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }
}
