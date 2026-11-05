package ru.wavedonate.plugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class Tasks {
    interface Handle {
        void cancel();
    }

    private final Plugin plugin;
    private final boolean folia;

    private Object global;
    private Object async;
    private Method globalExecute;
    private Method globalDelayed;
    private Method asyncNow;
    private Method asyncRate;
    private Method entityScheduler;
    private Method entityRun;
    private Method taskCancel;

    Tasks(Plugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
        if (folia) {
            try {
                Class<?> gClass = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                Class<?> aClass = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
                Class<?> eClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                Class<?> tClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
                global = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                async = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
                globalExecute = gClass.getMethod("execute", Plugin.class, Runnable.class);
                globalDelayed = gClass.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                asyncNow = aClass.getMethod("runNow", Plugin.class, Consumer.class);
                asyncRate = aClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                entityScheduler = org.bukkit.entity.Entity.class.getMethod("getScheduler");
                entityRun = eClass.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                taskCancel = tClass.getMethod("cancel");
            } catch (Exception e) {
                throw new IllegalStateException("Folia API не найден: " + e, e);
            }
        }
    }

    boolean isFolia() {
        return folia;
    }

    void sync(Runnable task) {
        if (!folia) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        invoke(globalExecute, global, plugin, task);
    }

    void later(final Runnable task, long ticks) {
        if (!folia) {
            Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
            return;
        }
        invoke(globalDelayed, global, plugin, consumer(task), Math.max(1L, ticks));
    }

    void async(final Runnable task) {
        if (!folia) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            return;
        }
        invoke(asyncNow, async, plugin, consumer(task));
    }

    Handle asyncTimer(final Runnable task, long delayTicks, long periodTicks) {
        if (!folia) {
            final BukkitTask t = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
            return new Handle() {
                @Override
                public void cancel() {
                    t.cancel();
                }
            };
        }
        final Object t = invoke(asyncRate, async, plugin, consumer(task), Math.max(1L, delayTicks) * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS);
        return new Handle() {
            @Override
            public void cancel() {
                invoke(taskCancel, t);
            }
        };
    }

    void forPlayer(Player player, Runnable task) {
        if (!folia) {
            task.run();
            return;
        }
        Object scheduler = invoke(entityScheduler, player);
        invoke(entityRun, scheduler, plugin, consumer(task), null);
    }

    <T> T callSync(final Callable<T> task) throws Exception {
        if (!plugin.isEnabled()) return null;
        if (!folia) return Bukkit.getScheduler().callSyncMethod(plugin, task).get(10, TimeUnit.SECONDS);
        final CompletableFuture<T> future = new CompletableFuture<T>();
        sync(new Runnable() {
            @Override
            public void run() {
                try {
                    future.complete(task.call());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Consumer<Object> consumer(final Runnable task) {
        return new Consumer<Object>() {
            @Override
            public void accept(Object scheduledTask) {
                task.run();
            }
        };
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка планировщика: " + e, e);
        }
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
