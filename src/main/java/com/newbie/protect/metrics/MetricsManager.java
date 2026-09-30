/**
 * NewbieProtect - 新人保护插件
 */
package com.newbie.protect.metrics;

import com.newbie.protect.NewbieProtect;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * bStats 匿名统计。
 *
 * <p>bStats 会收集**匿名**的服务器信息（在线人数、MC 版本、Java 版本、
 * 插件配置项分布等），用于作者了解插件使用情况。不会收集任何玩家信息。</p>
 *
 * <p>可在 config.yml 关闭：{@code metrics.enabled: false}。</p>
 *
 * <p><b>重要</b>：{@link #PLUGIN_ID} 需要在
 * <a href="https://bstats.org">bstats.org</a> 注册插件后获得，
 * 注册时选 Bukkit 平台。</p>
 */
public final class MetricsManager {

    /**
     * bStats 插件 ID。
     *
     * <p>获取方式：</p>
     * <ol>
     *     <li>打开 <a href="https://bstats.org">https://bstats.org</a> 并登录</li>
     *     <li>点击右上角头像 → <b>Add Plugin</b></li>
     *     <li>填插件名 NewbieProtect，平台选 <b>Bukkit</b></li>
     *     <li>提交后会得到一个数字 ID（例如 23456），填到下面</li>
     * </ol>
     */
    public static final int PLUGIN_ID = 34419;   // NewbieProtectA @ bstats.org

    private MetricsManager() {
    }

    /**
     * 初始化统计。
     *
     * @return true 表示初始化成功
     */
    public static boolean init(NewbieProtect plugin) {
        // 未配置 pluginId 就不启用（避免报错）
        if (PLUGIN_ID <= 0) {
            plugin.getLogger().info("bStats 未配置 pluginId，统计功能跳过"
                    + "（去 bstats.org 注册后填入 MetricsManager.PLUGIN_ID）。");
            return false;
        }
        // 配置里关闭
        if (!plugin.getConfig().getBoolean("metrics.enabled", true)) {
            plugin.getLogger().info("bStats 统计已在配置中关闭。");
            return false;
        }
        try {
            Metrics metrics = new Metrics(plugin, PLUGIN_ID);
            registerCharts(plugin, metrics);
            plugin.getLogger().info("bStats 统计已启用（匿名，可在 config.yml 关闭）。");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("bStats 初始化失败（不影响插件功能）: " + t.getMessage());
            return false;
        }
    }

    private static void registerCharts(NewbieProtect plugin, Metrics metrics) {
        // ---------- 简单饼图：配置项分布 ----------

        metrics.addCustomChart(new SimplePie("protect_enabled",
                () -> plugin.isProtectEnabled() ? "启用" : "关闭"));

        metrics.addCustomChart(new SimplePie("self_toggle",
                () -> plugin.isSelfToggleEnabled() ? "允许玩家自助开关" : "禁止"));

        metrics.addCustomChart(new SimplePie("boss_bar_enabled",
                () -> plugin.getConfig().getBoolean("boss-bar.enabled", true) ? "启用" : "关闭"));

        metrics.addCustomChart(new SimplePie("boss_bar_player_toggle",
                () -> plugin.isBossBarToggleEnabled() ? "允许" : "禁止"));

        metrics.addCustomChart(new SimplePie("block_mob_damage",
                () -> plugin.getConfig().getBoolean("protection.block-mob-damage", true)
                        ? "防怪物" : "不防"));

        metrics.addCustomChart(new SimplePie("block_player_damage",
                () -> plugin.getConfig().getBoolean("protection.block-player-damage", true)
                        ? "防PVP" : "不防"));

        metrics.addCustomChart(new SimplePie("mob_ignore_player",
                () -> plugin.getConfig().getBoolean("protection.mob-ignore-protected-player", true)
                        ? "怪物无视新人" : "怪物仍会攻击"));

        metrics.addCustomChart(new SimplePie("placeholders",
                () -> plugin.isPlaceholderApiAvailable() ? "装了PAPI" : "未装PAPI"));

        metrics.addCustomChart(new SimplePie("residence_hook",
                () -> plugin.getManager() != null
                        && plugin.getManager().getResidenceHook().isAvailable()
                        ? "装了Residence" : "未装Residence"));

        metrics.addCustomChart(new SimplePie("scoreboard_folia",
                () -> com.newbie.protect.util.SchedulerUtils.isFolia() ? "Folia" : "非Folia"));

        metrics.addCustomChart(new SimplePie("admin_limit",
                () -> plugin.hasAdminLimit() ? "有上限" : "无限制"));

        metrics.addCustomChart(new SimplePie("boss_battle",
                () -> plugin.getConfig().getBoolean("boss-battle.enabled", true)
                        ? "打Boss无效化已开" : "关闭"));

        // 保护时长区间（不暴露具体值，用区间更有统计意义）
        metrics.addCustomChart(new SimplePie("duration_range", () -> {
            long minutes = plugin.getProtectDurationSeconds() / 60L;
            if (minutes <= 30) {
                return "30分钟以内";
            }
            if (minutes <= 60) {
                return "30分钟~1小时";
            }
            if (minutes <= 180) {
                return "1~3小时";
            }
            if (minutes <= 360) {
                return "3~6小时";
            }
            if (minutes <= 720) {
                return "6~12小时";
            }
            if (minutes <= 1440) {
                return "12~24小时";
            }
            return "超过1天";
        }));

        // ---------- 单值折线图：记录了多少玩家 ----------
        metrics.addCustomChart(new SingleLineChart("tracked_players",
                () -> plugin.getDataStore() == null ? 0 : plugin.getDataStore().size()));

        // ---------- 高级饼图：暂停计时来源 ----------
        metrics.addCustomChart(new AdvancedPie("pause_sources", () -> {
            Map<String, Integer> map = new HashMap<>();
            try {
                if (plugin.isWorldListEnabled()) {
                    map.put("世界列表", 1);
                }
                if (plugin.isResidenceEnabled()
                        && plugin.getManager() != null
                        && plugin.getManager().getResidenceHook().isAvailable()) {
                    map.put("Residence领地", 1);
                }
                if (plugin.getConfig().getBoolean("boss-battle.enabled", true)) {
                    map.put("打Boss战斗", 1);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            return map;
        }));

        // ---------- 钻取饼图：世界列表模式 ----------
        metrics.addCustomChart(new DrilldownPie("world_list_mode", () -> {
            Map<String, Map<String, Integer>> map = new HashMap<>();
            try {
                if (!plugin.isWorldListEnabled()) {
                    Map<String, Integer> inner = new HashMap<>();
                    inner.put("未启用", 1);
                    map.put("世界列表", inner);
                } else {
                    String mode = plugin.getWorldListMode();
                    Map<String, Integer> inner = new HashMap<>();
                    inner.put(mode.equals("deny") ? "deny(列表外暂停)" : "allow(仅列表内暂停)", 1);
                    map.put("世界列表", inner);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            return map;
        }));
    }

    /** 供外部调用的安全包装（避免编译期强依赖调用方处理异常）。 */
    public static void safeAdd(Metrics metrics, String name, Callable<String> supplier) {
        try {
            metrics.addCustomChart(new SimplePie(name, supplier));
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
