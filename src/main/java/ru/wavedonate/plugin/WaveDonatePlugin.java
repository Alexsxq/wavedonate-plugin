package ru.wavedonate.plugin;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WaveDonatePlugin extends JavaPlugin implements Listener {
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final Set<String> waitingPlayers = Collections.synchronizedSet(new HashSet<String>());

    private WaveDonateApiClient api;
    private DeliveryQueue queue;
    private Deliverer deliverer;
    private Tasks tasks;
    private Tasks.Handle pollTask;
    private Tasks.Handle pingTask;

    private volatile boolean debug;
    private volatile boolean requireOnline;
    private volatile int serverId;
    private volatile String shopKey = "";

    private volatile long lastPollAt;
    private volatile String lastError;
    private volatile int failures;
    private volatile int delivered;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tasks = new Tasks(this);
        queue = new DeliveryQueue(getDataFolder(), getLogger());
        queue.load();
        deliverer = new Deliverer(this);
        PluginCommand command = getCommand("wavedonate");
        if (command != null) {
            WaveDonateCommand handler = new WaveDonateCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        reloadWaveDonate();
        if (queue.size() > 0) {
            getLogger().info("В очереди " + queue.size() + " невыданных заказов — продолжаю выдачу.");
            tasks.later(new Runnable() {
                @Override
                public void run() {
                    resumeQueue(null);
                }
            }, 40L);
        }
    }

    @Override
    public void onDisable() {
        if (tasks != null) cancelTasks();
    }

    void reloadWaveDonate() {
        reloadConfig();
        shopKey = getConfig().getString("settings.shop-key", "").trim();
        serverId = Math.max(0, getConfig().getInt("settings.server-id", 0));
        debug = getConfig().getBoolean("settings.debug", false);
        requireOnline = getConfig().getBoolean("delivery.require-online", false);
        int delaySeconds = Math.max(5, getConfig().getInt("settings.request-delay-seconds", 20));
        int timeout = Math.max(3, Math.min(60, getConfig().getInt("settings.timeout-seconds", 10)));
        api = new WaveDonateApiClient(getConfig().getString("settings.api-url", WaveDonateApiClient.DEFAULT_URL), shopKey, serverId,
            timeout * 1000, getDescription().getVersion());
        failures = 0;
        lastError = null;

        cancelTasks();
        if (!isShopKeyConfigured()) {
            getLogger().warning("Не указан shop-key. Вставьте ключ из кабинета в plugins/WaveDonate/config.yml и выполните /wd reload");
            return;
        }
        pollTask = tasks.asyncTimer(new Runnable() {
            @Override
            public void run() {
                pollOrders();
            }
        }, 60L, delaySeconds * 20L);
        pingTask = tasks.asyncTimer(new Runnable() {
            @Override
            public void run() {
                pingQuiet();
            }
        }, 20L, 20L * 60 * 5);
        getLogger().info("Подключение: " + api.url() + (serverId > 0 ? " · режим #" + serverId : "") + " · проверка каждые " + delaySeconds + " с"
            + (requireOnline ? " · выдача только игрокам в сети" : ""));
    }

    private void cancelTasks() {
        if (pollTask != null) pollTask.cancel();
        if (pingTask != null) pingTask.cancel();
        pollTask = null;
        pingTask = null;
    }

    void pollOrders() {
        if (!isShopKeyConfigured() || !polling.compareAndSet(false, true)) return;
        try {
            List<PendingOrder> orders = api.fetchPendingOrders();
            lastPollAt = System.currentTimeMillis();
            onSuccess();
            if (orders.isEmpty()) return;
            if (debug) getLogger().info("Оплаченных заказов к выдаче: " + orders.size());
            final List<PendingOrder> fresh = orders;

            List<PendingOrder> ready = callSync(new Callable<List<PendingOrder>>() {
                @Override
                public List<PendingOrder> call() {
                    List<PendingOrder> out = new ArrayList<PendingOrder>();
                    for (PendingOrder o : fresh) {
                        if (queue.contains(o.id)) continue;
                        if (requireOnline && Bukkit.getPlayerExact(o.player) == null) {
                            waitingPlayers.add(o.player.toLowerCase());
                            continue;
                        }
                        out.add(o);
                    }
                    return out;
                }
            });
            if (ready == null) return;
            for (final PendingOrder order : ready) {
                claimAndDeliver(order);
            }
        } catch (Exception ex) {
            onFailure(ex);
        } finally {
            polling.set(false);
        }
    }

    private void claimAndDeliver(final PendingOrder order) {
        try {
            final List<String> commands = api.claimOrder(order.id, order.player);
            if (commands == null) {
                if (debug) getLogger().info("Заказ #" + order.id + " уже выдан другим сервером.");
                return;
            }
            tasks.sync(new Runnable() {
                @Override
                public void run() {
                    PendingOrder claimed = order.withCommands(commands);
                    queue.add(claimed);
                    DeliveryQueue.Entry entry = queue.get(claimed.id);
                    if (commands.isEmpty()) getLogger().warning("Заказ #" + order.id + " (" + order.product + ") без команд — допишите команды товару в кабинете.");

                    deliverer.deliver(entry, true);
                }
            });
        } catch (IOException ex) {
            getLogger().warning("Не удалось забрать заказ #" + order.id + ": " + ex.getMessage());
        }
    }

    void resumeQueue(String onlyPlayer) {
        for (DeliveryQueue.Entry e : queue.all()) {
            if (onlyPlayer == null || e.order.player.equalsIgnoreCase(onlyPlayer)) deliverer.deliver(e, false);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final String name = event.getPlayer().getName();
        int delay = Math.max(0, getConfig().getInt("delivery.join-delay-seconds", 3));
        tasks.later(new Runnable() {
            @Override
            public void run() {
                resumeQueue(name);
                if (waitingPlayers.remove(name.toLowerCase())) {
                    tasks.async(new Runnable() {
                        @Override
                        public void run() {
                            pollOrders();
                        }
                    });
                }
            }
        }, delay * 20L);
    }

    void onDelivered(PendingOrder order) {
        delivered++;
        getLogger().info("Выдан заказ #" + order.id + ": " + order.product + " → " + order.player);
    }

    void pingAsync(final CommandSender sender) {
        tasks.async(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                try {
                    api.ping();
                    onSuccess();
                    tell(sender, message("ping-ok").replace("{ms}", String.valueOf(System.currentTimeMillis() - start)));
                } catch (IOException ex) {
                    tell(sender, message("ping-fail").replace("{error}", String.valueOf(ex.getMessage())));
                }
            }
        });
    }

    private void pingQuiet() {
        try {
            api.ping();
        } catch (IOException ignored) {
        }
    }

    private void onSuccess() {
        if (failures > 0) getLogger().info("Связь с WaveDonate восстановлена.");
        failures = 0;
        lastError = null;
    }

    private void onFailure(Exception ex) {
        failures++;
        lastError = ex.getMessage();

        if (failures == 1 || failures % 15 == 0) {
            getLogger().warning("Нет связи с WaveDonate (" + failures + " раз подряд): " + ex.getMessage());
        }
    }

    private void tell(final CommandSender sender, final String text) {
        tasks.sync(new Runnable() {
            @Override
            public void run() {
                sender.sendMessage(Colors.apply(text));
            }
        });
    }

    private <T> T callSync(Callable<T> task) throws Exception {
        return tasks.callSync(task);
    }

    List<String> statusLines() {
        List<String> lines = new ArrayList<String>();
        lines.add("&bWaveDonate &f" + getDescription().getVersion());
        if (!isShopKeyConfigured()) {
            lines.add(message("status-bad-key"));
            return lines;
        }
        lines.add("&7API: &f" + api.url() + (serverId > 0 ? " &7· режим &f#" + serverId : ""));
        String when = lastPollAt == 0 ? "ещё не было" : new SimpleDateFormat("HH:mm:ss").format(new Date(lastPollAt));
        lines.add("&7Последняя проверка: &f" + when + (failures > 0 ? " &c· ошибка: " + lastError : " &a· связь есть"));
        lines.add("&7Выдано с запуска: &f" + delivered + " &7· в очереди: &f" + queue.size()
            + (waitingPlayers.isEmpty() ? "" : " &7· ждут входа: &f" + waitingPlayers.size()));
        lines.add("&7Выдача только в сети: " + (requireOnline ? "&aда" : "&fнет"));
        return lines;
    }

    List<String> queueLines() {
        List<String> lines = new ArrayList<String>();
        for (DeliveryQueue.Entry e : queue.all()) {
            lines.add("&f#" + e.order.id + " &7" + e.order.player + " · " + e.order.product + " · шаг " + e.next + "/" + e.order.commands.size()
                + (deliverer.isRunning(e.order.id) ? " &a(выдаётся)" : Bukkit.getPlayerExact(e.order.player) == null ? " &e(ждёт входа)" : ""));
        }
        synchronized (waitingPlayers) {
            for (String p : waitingPlayers) lines.add("&e" + p + " &7— оплачено, выдадим при входе");
        }
        return lines;
    }

    void testNotify(Player target) {
        PendingOrder demo = new PendingOrder(0, target.getName(), getConfig().getString("notify.test-product", "VIP"), "199", null, null,
            Collections.<String>emptyList());
        deliverer.notifyPlayer(demo, target);
    }

    String message(String key) {
        String prefix = getConfig().getString("messages.prefix", "&bWaveDonate &8» ");
        return prefix + getConfig().getString("messages." + key, key);
    }

    Tasks tasks() {
        return tasks;
    }

    DeliveryQueue queue() {
        return queue;
    }

    boolean isDebug() {
        return debug;
    }

    boolean requireOnline() {
        return requireOnline;
    }

    void setup(String key, int newServerId) throws IOException {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) saveDefaultConfig();
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (!java.util.regex.Pattern.compile("(?m)^\\s*shop-key:").matcher(text).find()) throw new IOException("в config.yml нет строки shop-key — удалите файл и перезапустите сервер");
        String updated = text.replaceFirst("(?m)^(\\s*shop-key:).*$", "$1 \"" + key + "\"");
        if (newServerId >= 0) updated = updated.replaceFirst("(?m)^(\\s*server-id:).*$", "$1 " + newServerId);
        Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
        reloadWaveDonate();
    }

    private boolean isShopKeyConfigured() {
        return shopKey != null && !shopKey.isEmpty() && !"PASTE_SHOP_KEY_HERE".equalsIgnoreCase(shopKey) && !"ВАШ_КЛЮЧ".equals(shopKey);
    }
}
