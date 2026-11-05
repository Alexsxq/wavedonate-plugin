package ru.wavedonate.plugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class WaveDonateCommand implements TabExecutor {
    private static final List<String> SUBS = Arrays.asList("setup", "status", "ping", "check", "queue", "test", "reload");
    private final WaveDonatePlugin plugin;

    WaveDonateCommand(WaveDonatePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("wavedonate.admin")) {
            send(sender, plugin.message("no-permission"));
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        if ("setup".equals(sub)) {
            if (sender instanceof Player) {
                send(sender, "&eВыполните в консоли сервера: &fwd setup <ключ> [server-id]&e — так ключ не попадёт в логи чата.");
                return true;
            }
            if (args.length < 2 || !args[1].matches("[0-9A-Fa-f-]{32,40}")) {
                send(sender, "&cУкажите ключ магазина: wd setup <ключ> [server-id]. Ключ — в кабинете, вкладка «Плагин».");
                return true;
            }
            int sid = -1;
            if (args.length > 2) {
                try { sid = Math.max(0, Integer.parseInt(args[2])); } catch (NumberFormatException ex) {
                    send(sender, "&cserver-id — число из вкладки «Серверы» (0 — один сервер).");
                    return true;
                }
            }
            try {
                plugin.setup(args[1], sid);
            } catch (java.io.IOException ex) {
                send(sender, "&cНе удалось сохранить config.yml: &f" + ex.getMessage());
                return true;
            }
            send(sender, "&aКлюч сохранён. Проверяю связь…");
            plugin.pingAsync(sender);
        } else if ("status".equals(sub)) {
            for (String line : plugin.statusLines()) send(sender, line);
        } else if ("ping".equals(sub)) {
            send(sender, plugin.message("ping-wait"));
            plugin.pingAsync(sender);
        } else if ("check".equals(sub)) {
            plugin.tasks().async(new Runnable() {
                @Override
                public void run() {
                    plugin.pollOrders();
                }
            });
            send(sender, plugin.message("check"));
        } else if ("queue".equals(sub)) {
            List<String> lines = plugin.queueLines();
            if (lines.isEmpty()) send(sender, plugin.message("queue-empty"));
            for (String line : lines) send(sender, line);
        } else if ("test".equals(sub)) {
            Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player ? (Player) sender : null;
            if (target == null) {
                send(sender, "&cУкажите игрока в сети: /" + label + " test <ник>");
            } else {
                plugin.testNotify(target);
                send(sender, plugin.message("test").replace("{player}", target.getName()));
            }
        } else if ("reload".equals(sub)) {
            plugin.reloadWaveDonate();
            send(sender, plugin.message("reload"));
        } else {
            send(sender, "&bWaveDonate &f" + plugin.getDescription().getVersion() + " &8— команды");
            send(sender, "&f/" + label + " setup <ключ> [server-id] &8— &7подключить магазин (из консоли)");
            send(sender, "&f/" + label + " status &8— &7подключение и статистика");
            send(sender, "&f/" + label + " ping &8— &7проверить ключ и связь с API");
            send(sender, "&f/" + label + " check &8— &7проверить новые оплаты сейчас");
            send(sender, "&f/" + label + " queue &8— &7что ждёт выдачи");
            send(sender, "&f/" + label + " test [ник] &8— &7показать уведомление о покупке");
            send(sender, "&f/" + label + " reload &8— &7перечитать config.yml");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("wavedonate.admin")) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && "test".equalsIgnoreCase(args[0])) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        }
        return out;
    }

    private static void send(CommandSender sender, String text) {
        sender.sendMessage(Colors.apply(text));
    }
}
