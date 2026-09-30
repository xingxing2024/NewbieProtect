package com.newbie.protect.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * 调度器兼容层：Folia 用区域化调度器，其他服务端走 BukkitScheduler。
 *
 * <p>与之前几个插件保持一致的实现方式，避免在 Folia / Leaf / Paper 上出现
 * {@code UnsupportedOperationException}。</p>
 */
public final class SchedulerUtils {

    private static final long TICK_MILLIS = 50L;
    private static final boolean FOLIA = detectFolia();

    private SchedulerUtils() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 可取消句柄。 */
    public interface Cancellable {
        void cancel();
    }

    /** 定时重复执行（全局线程）。 */
    public static Cancellable runTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (task == null || plugin == null || !plugin.isEnabled()) {
            return () -> {
            };
        }
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        try {
            if (FOLIA) {
                var scheduled = Bukkit.getGlobalRegionScheduler()
                        .runAtFixedRate(plugin, s -> task.run(), delay, period);
                return scheduled::cancel;
            }
            var bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            return bukkitTask::cancel;
        } catch (Throwable ignored) {
            return () -> {
            };
        }
    }

    /** 异步延迟执行。 */
    public static void runAsyncLater(Plugin plugin, Runnable task, long delayTicks) {
        if (task == null || plugin == null || !plugin.isEnabled()) {
            return;
        }
        try {
            if (FOLIA) {
                Bukkit.getAsyncScheduler().runDelayed(plugin, s -> task.run(),
                        Math.max(0L, delayTicks) * TICK_MILLIS, TimeUnit.MILLISECONDS);
            } else {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, Math.max(1L, delayTicks));
            }
        } catch (Throwable ignored) {
            // 关服期间忽略
        }
    }

    /** 在实体所属区域线程执行（Folia 读/写玩家状态必须如此）。 */
    public static void runEntity(Plugin plugin, Entity entity, Runnable task) {
        if (task == null || plugin == null || entity == null || !plugin.isEnabled()) {
            return;
        }
        try {
            if (FOLIA) {
                entity.getScheduler().execute(plugin, task, null, 0L);
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } catch (Throwable ignored) {
            // 实体已离线
        }
    }

    /** 在实体所属区域线程「延迟」执行（Folia 推荐用 execute 后自行延迟，这里统一封装）。 */
    public static void runEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (task == null || plugin == null || entity == null || !plugin.isEnabled()) {
            return;
        }
        long delay = Math.max(1L, delayTicks);
        try {
            if (FOLIA) {
                // Folia 的 EntityScheduler 支持 delay 参数
                entity.getScheduler().execute(plugin, task, null, delay);
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        } catch (Throwable ignored) {
            // 实体已离线
        }
    }
}
