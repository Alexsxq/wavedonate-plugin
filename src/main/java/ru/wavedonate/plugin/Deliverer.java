package ru.wavedonate.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Deliverer {
    private static final Pattern ACTION = Pattern.compile("^\\[([a-zA-Z]+)(?::([^\\]]*))?]\\s*(.*)$", Pattern.DOTALL);

    private final WaveDonatePlugin plugin;
    private final Set<Long> running = new HashSet<Long>();

    Deliverer(WaveDonatePlugin plugin) {
        this.plugin = plugin;
    }

    boolean isRunning(long id) {
        return running.contains(id);
    }

    void deliver(DeliveryQueue.Entry entry, boolean force) {
        if (entry == null || running.contains(entry.order.id)) return;
        if (!force && plugin.requireOnline() && Bukkit.getPlayerExact(entry.order.player) == null) return;
        running.add(entry.order.id);
        step(entry, entry.next);
    }

    private void step(final DeliveryQueue.Entry entry, final int index) {
        final PendingOrder order = entry.order;
        List<String> commands = order.commands;
        if (index >= commands.size()) {
            finish(order);
            return;
        }
        String line = commands.get(index).trim();
        long delayTicks = 0;
        Matcher m = ACTION.matcher(line);
        if (m.matches() && "delay".equalsIgnoreCase(m.group(1))) {
            delayTicks = Math.max(0, Math.round(parseDouble(m.group(2), 0) * 20));
            line = m.group(3).trim();
        }
        final String toRun = line;
        Runnable run = new Runnable() {
            @Override
            public void run() {
                if (!plugin.isEnabled()) return;
                try {
                    if (!toRun.isEmpty()) execute(order, toRun);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Заказ #" + order.id + ": команда «" + toRun + "» упала: " + ex.getMessage());
                }
                plugin.queue().progress(order.id, index + 1);
                step(entry, index + 1);
            }
        };
        if (delayTicks > 0) {
            plugin.tasks().later(run, delayTicks);
        } else {
            run.run();
        }
    }

    private void finish(PendingOrder order) {
        running.remove(order.id);
        plugin.queue().remove(order.id);
        plugin.onDelivered(order);
        notifyPlayer(order, Bukkit.getPlayerExact(order.player));
    }

    void notifyPlayer(PendingOrder order, final Player player) {
        ConfigurationSection n = plugin.getConfig().getConfigurationSection("notify");
        if (n == null) return;
        if (player != null) {
            final String msg = Colors.apply(order.apply(n.getString("message", "")));
            final boolean title = n.getBoolean("title.enabled", true);
            final String titleText = Colors.apply(order.apply(n.getString("title.title", "")));
            final String subtitle = Colors.apply(order.apply(n.getString("title.subtitle", "")));
            final int stay = Math.max(20, n.getInt("title.stay-ticks", 50));
            final String sound = n.getString("sound", "");
            plugin.tasks().forPlayer(player, new Runnable() {
                @Override
                public void run() {
                    if (!msg.isEmpty()) player.sendMessage(msg);
                    if (title) Compat.title(player, titleText, subtitle, 10, stay, 15);
                    if (!sound.isEmpty()) playSound(player, sound);
                }
            });
        }
        if (n.getBoolean("broadcast.enabled", false)) {
            String text = n.getString("broadcast.text", "");
            if (!text.isEmpty()) Bukkit.broadcastMessage(Colors.apply(order.apply(text)));
        }
    }

    private void execute(PendingOrder order, String line) {
        String action = "console";
        String arg = null;
        String rest = line;
        Matcher m = ACTION.matcher(line);
        if (m.matches()) {
            action = m.group(1).toLowerCase();
            arg = m.group(2);
            rest = m.group(3).trim();
        }
        final String text = order.apply(rest);
        final Player player = Bukkit.getPlayerExact(order.player);
        if ("console".equals(action)) {
            dispatch(order, text);
        } else if ("broadcast".equals(action)) {
            Bukkit.broadcastMessage(Colors.apply(text));
        } else if (isPlayerAction(action)) {
            if (player == null) {
                if ("player".equals(action)) plugin.getLogger().warning("Заказ #" + order.id + ": [player] пропущена, " + order.player + " не в сети");
            } else {
                final String act = action;
                plugin.tasks().forPlayer(player, new Runnable() {
                    @Override
                    public void run() {
                        playerAction(player, act, text);
                    }
                });
            }
        } else {
            dispatch(order, order.apply(line));
        }
        if (arg != null && plugin.isDebug()) plugin.getLogger().info("Заказ #" + order.id + ": [" + action + ":" + arg + "] " + text);
    }

    private static boolean isPlayerAction(String action) {
        return "player".equals(action) || "message".equals(action) || "title".equals(action)
            || "actionbar".equals(action) || "sound".equals(action);
    }

    private static void playerAction(Player player, String action, String text) {
        if ("player".equals(action)) {
            player.performCommand(stripSlash(text));
        } else if ("message".equals(action)) {
            player.sendMessage(Colors.apply(text));
        } else if ("title".equals(action)) {
            String[] parts = text.split("\\|", 2);
            Compat.title(player, Colors.apply(parts[0]), Colors.apply(parts.length > 1 ? parts[1] : ""), 10, 50, 15);
        } else if ("actionbar".equals(action)) {
            Compat.actionBar(player, Colors.apply(text));
        } else if ("sound".equals(action)) {
            playSound(player, text);
        }
    }

    private void dispatch(PendingOrder order, String command) {
        String clean = stripSlash(command);
        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clean);
        if (!ok) plugin.getLogger().warning("Заказ #" + order.id + ": сервер не знает команду «" + clean + "» — проверьте товар в кабинете");
        else if (plugin.isDebug()) plugin.getLogger().info("Заказ #" + order.id + ": " + clean);
    }

    @SuppressWarnings("deprecation")
    private static void playSound(Player player, String spec) {
        String[] p = spec.split("\\|");
        String name = p[0].trim();
        float volume = (float) parseDouble(p.length > 1 ? p[1] : null, 1);
        float pitch = (float) parseDouble(p.length > 2 ? p[2] : null, 1);
        try {
            if (name.matches("[A-Z0-9_]+")) {
                Sound sound = enumSound(name);
                if (sound != null) {
                    player.playSound(player.getLocation(), sound, volume, pitch);
                    return;
                }
            }
            player.playSound(player.getLocation(), Compat.soundKey(name), volume, pitch);
        } catch (RuntimeException ignored) {
        }
    }

    private static Sound enumSound(String name) {
        try {
            return (Sound) Sound.class.getMethod("valueOf", String.class).invoke(null, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripSlash(String s) {
        String t = s.trim();
        return t.startsWith("/") ? t.substring(1) : t;
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
