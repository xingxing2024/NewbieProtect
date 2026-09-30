package com.newbie.protect.manager;

import com.newbie.protect.NewbieProtect;
import com.newbie.protect.data.PlayerDataStore;
import com.newbie.protect.util.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 新人保护核心管理器。
 *
 * <p>每秒为在线且仍在保护期的玩家累加 1 秒在线时长，
 * 并刷新 Boss 条倒计时、到期提醒、保护结束处理。</p>
 */
public class ProtectionManager {

    /** 在线玩家 → Boss 条实例。 */
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    /** 已发出的提醒（玩家 → 已提醒过的秒数集合），防止重复刷屏。 */
    private final Map<UUID, java.util.Set<Long>> reminded = new ConcurrentHashMap<>();
    /** 上一秒该玩家是否处于保护中，用于检测「刚结束」。 */
    private final Map<UUID, Boolean> wasProtected = new ConcurrentHashMap<>();

    private final NewbieProtect plugin;
    /** Residence 领地接入（没装 Residence 时内部会自动降级为不可用）。 */
    private final com.newbie.protect.hook.ResidenceHook residenceHook =
            new com.newbie.protect.hook.ResidenceHook();
    /** 打 Boss 无效化管理器。 */
    private com.newbie.protect.manager.BossBattleManager bossBattle;
    private SchedulerUtils.Cancellable tickTask;
    /** 是否已彻底关闭（关闭后 tick 不再执行任何逻辑）。 */
    private volatile boolean shutDown = false;

    public ProtectionManager(NewbieProtect plugin) {
        this.plugin = plugin;
    }

    public com.newbie.protect.hook.ResidenceHook getResidenceHook() {
        return residenceHook;
    }

    public com.newbie.protect.manager.BossBattleManager getBossBattle() {
        if (bossBattle == null) {
            bossBattle = new com.newbie.protect.manager.BossBattleManager(plugin);
        }
        return bossBattle;
    }

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    public void start() {
        stop();
        shutDown = false;
        if (!plugin.isProtectEnabled()) {
            plugin.getLogger().info("新人保护总开关已关闭，不启动计时任务。");
            return;
        }
        // 每秒跑一次。
        // 关键：外层再包一层 try/catch —— 如果 tick 抛异常，
        // Bukkit 会取消这个重复任务，导致计数器停住、所有人都「永久无敌」。
        // 这里保证异常永远传不到调度器，任务不会被取消。
        this.tickTask = SchedulerUtils.runTimer(plugin, () -> {
            try {
                tick();
            } catch (Throwable t) {
                plugin.getLogger().warning("新人保护计时循环异常（已忽略，任务继续）: " + t);
            }
        }, 20L, 20L);
        plugin.getLogger().info("新人保护计时任务已启动（总时长 "
                + (plugin.getProtectDurationSeconds() / 60) + " 分钟）。");
    }

    /** 停止计时任务并移除全部 Boss 条（用于 reload 后重启）。 */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        clearAllBossBars();
    }

    /**
     * 彻底关闭：停止任务、移除 Boss 条、并标记关闭状态。
     *
     * <p>关服 / 插件卸载时调用。关闭后 {@link #tick()} 直接返回，
     * 不会再给玩家添加任何 Boss 条。</p>
     */
    public void shutdown() {
        shutDown = true;
        stop();
    }

    /** 是否已关闭。 */
    public boolean isShutDown() {
        return shutDown;
    }

    /* ------------------------------------------------------------------ */
    /* 每秒主循环                                                          */
    /* ------------------------------------------------------------------ */

    private void tick() {
        if (shutDown || plugin.isShuttingDown() || !plugin.isProtectEnabled()) {
            return;
        }
        long total = plugin.getProtectDurationSeconds();
        if (total <= 0) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Folia 下操作玩家（Boss 条）需在实体线程；逐个包异常，
            // 保证一个玩家出错不影响其他玩家
            SchedulerUtils.runEntity(plugin, player, () -> {
                try {
                    tickOne(player, total);
                } catch (Throwable t) {
                    plugin.getLogger().warning("处理玩家 " + player.getName()
                            + " 的保护计时时出错（已跳过）: " + t);
                }
            });
        }
    }

    private void tickOne(Player player, long total) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        PlayerDataStore store = plugin.getDataStore();
        PlayerDataStore.Entry entry = store.getOrCreate(uuid, player.getName());

        // 兜底 1：绝对到期（防止计时器卡住导致「永久无敌」）
        // 即使 used-seconds 一直没涨，超过 max-absolute-days 也强制失效
        long maxDays = plugin.getConfig().getLong("protection.max-absolute-days", 30L);
        if (maxDays > 0L && entry.firstJoin() > 0L) {
            long elapsed = System.currentTimeMillis() - entry.firstJoin();
            if (elapsed > maxDays * 86_400_000L) {
                if (!entry.finished()) {
                    entry.usedSeconds(total);
                    entry.finished(true);
                    store.markDirty();
                    plugin.getLogger().info("玩家 " + player.getName()
                            + " 的新人保护已超过绝对上限 " + maxDays + " 天，强制失效。");
                }
                removeBossBar(uuid);
                wasProtected.put(uuid, false);
                return;
            }
        }

        // 已经结束过保护：确保 Boss 条被移除
        // 注意：用「剩余时间 <= 0」判断，而不是 usedSeconds >= total，
        // 这样管理员用 add 加回来的时间能正确生效（usedSeconds 可能为负）。
        if (entry.finished() || (total - entry.usedSeconds()) <= 0L) {
            entry.finished(true);
            boolean was = Boolean.TRUE.equals(wasProtected.put(uuid, false));
            if (was) {
                // 刚刚结束
                removeBossBar(uuid);
                plugin.sendConfigMessage(player, "expired", null);
                store.markDirty();
            } else {
                removeBossBar(uuid);
            }
            return;
        }

        // 暂停判定：世界列表 / 自己的领地 / 管理员冻结 / Boss 战斗
        boolean bossBattle = getBossBattle().isInBattle(player);
        boolean bossPause = bossBattle && getBossBattle().isPauseTimer();
        boolean paused = isPaused(player) || entry.adminPaused() || bossPause;
        // 暂停区域内按配置决定是否照常计时（管理员冻结 / Boss 战斗暂停永远不计时）
        boolean countThisTick = !entry.adminPaused() && !bossPause
                && (!paused || isCountInPaused());

        // 累加在线时长
        // 玩家自己关闭保护时：按配置决定是否继续计时。
        // 默认继续计时（self-toggle.count-while-disabled: true），
        // 也就是「关掉保护并不能省下时间」，避免玩家钻空子。
        boolean selfOff = entry.selfDisabled();
        boolean countWhileOff = plugin.isSelfToggleEnabled() && plugin.isCountWhileDisabled();
        if (countThisTick && (!selfOff || countWhileOff)) {
            entry.addUsedSeconds(1L);
            store.markDirty();
        }

        long left = total - entry.usedSeconds();
        // 暂停区且不保留保护时，等同于「此刻不免伤」
        boolean effective = !(paused && !isProtectInPaused());
        boolean nowProtected = left > 0 && !selfOff && effective;
        // 只有从「非保护」变成「保护」时才提示（正常进服由 onJoin 负责，
        // 这里覆盖 reload / grant 之后的情形）
        Boolean prev = wasProtected.put(uuid, nowProtected);
        if (nowProtected && !Boolean.TRUE.equals(prev)) {
            plugin.sendConfigMessage(player, "join-protected", "%time%", format(left));
        }

        if (left <= 0) {
            entry.finished(true);
            removeBossBar(uuid);
            plugin.sendConfigMessage(player, "expired", null);
            store.markDirty();
            return;
        }

        // 到期提醒（仅在保护实际生效时提醒；玩家自己关掉后不再打扰）
        if (nowProtected) {
            checkRemind(player, uuid, left);
        }

        // Boss 条：即使玩家自己关闭了保护 / 处于暂停区也继续显示，
        // 好让玩家看到「时间还在不在走」以及怎么重新开启
        updateBossBar(player, uuid, left, total, selfOff, paused, bossBattle);
    }

    /** 到点提醒（每个档位只提醒一次）。 */
    private void checkRemind(Player player, UUID uuid, long secondsLeft) {
        List<Long> list = plugin.getRemindSeconds();
        if (list.isEmpty()) {
            return;
        }
        java.util.Set<Long> sent = reminded.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        for (Long point : list) {
            if (secondsLeft <= point && sent.add(point)) {
                plugin.sendConfigMessage(player, "ending-soon", "%time%", format(point));
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Boss 条                                                             */
    /* ------------------------------------------------------------------ */

    private void updateBossBar(Player player, UUID uuid, long left, long total,
                               boolean selfOff, boolean paused, boolean bossBattle) {
        if (!plugin.getConfig().getBoolean("boss-bar.enabled", true)) {
            removeBossBar(uuid);
            return;
        }
        // 玩家自己关掉了 Boss 条（防骚扰）
        if (plugin.isBossBarHidden(uuid)) {
            removeBossBar(uuid);
            return;
        }
        BossBar bar = bossBars.get(uuid);
        if (bar == null) {
            bar = createBossBar();
            if (bar == null) {
                return;
            }
            bossBars.put(uuid, bar);
        }
        try {
            boolean countInPaused = isCountInPaused();
            // 四种状态：自己关闭 > Boss 战斗 > 暂停区 > 正常保护中
            String key;
            String fallback;
            String colorKey;
            if (selfOff) {
                key = "boss-bar.title-disabled";
                fallback = "&c保护已关闭 &8| &e剩余 %time% &8| &7(%timing%) &8| &7输入 &f/newbie on &7可开启";
                colorKey = "boss-bar.color-disabled";
            } else if (bossBattle) {
                key = "boss-bar.title-boss-battle";
                fallback = "&4Boss 战斗中 &7保护失效 &8| &e剩余 %time% &8| &7(%timing%) &8| &7脱战 %battle% 秒后恢复";
                colorKey = "boss-bar.color-boss-battle";
            } else if (paused) {
                key = "boss-bar.title-paused";
                fallback = "&7(%reason%) &8| &e剩余 %time% &8| &7(%timing%)";
                colorKey = "boss-bar.color-paused";
            } else {
                key = "boss-bar.title";
                fallback = "&b新人保护中 &7| &e剩余 %time% &8| &7输入 &f/newbie off &7可关闭";
                colorKey = "boss-bar.color";
            }
            String raw = plugin.getConfig().getString(key, fallback);
            if (raw == null || raw.isEmpty()) {
                raw = fallback;
            }

            // %timing%：暂停区用 pause-note-*，其他用 timing-note-*
            String timing = paused
                    ? (countInPaused
                            ? plugin.getConfig().getString("boss-bar.pause-note-counting", "此区域不计时（剩余时间保留）")
                            : plugin.getConfig().getString("boss-bar.pause-note-paused", "此处保护计时已暂停"))
                    : (plugin.isCountWhileDisabled()
                            ? plugin.getConfig().getString("boss-bar.timing-note-counting", "时间照常计算")
                            : plugin.getConfig().getString("boss-bar.timing-note-paused", "计时已暂停"));

            // Boss 条标题：支持插件占位符 + PAPI 变量
            String title = plugin.parsePlaceholders(raw, player,
                    "%time%", format(left),
                    "%seconds%", String.valueOf(left),
                    "%player%", player.getName(),
                    "%state%", selfOff
                            ? plugin.getConfig().getString("boss-bar.state-disabled", "已关闭")
                            : plugin.getConfig().getString("boss-bar.state-on", "保护中"),
                    "%reason%", bossBattle ? "Boss 战斗中" : pauseReason(player),
                    "%battle%", String.valueOf(getBossBattle().battleSecondsLeft(player)),
                    "%timing%", timing,
                    "%toggle-cmd%", selfOff ? "on" : "off");
            bar.setTitle(title);

            String mode = plugin.getConfig().getString("boss-bar.progress-mode", "total");
            double progress = 1.0;
            if ("total".equalsIgnoreCase(mode) && total > 0) {
                progress = (double) left / (double) total;
            }
            progress = Math.max(0.0, Math.min(1.0, progress));
            bar.setProgress(progress);

            // 每种状态用自己的颜色，方便玩家一眼分辨
            try {
                bar.setColor(parseColor(plugin.getConfig().getString(colorKey,
                        selfOff ? "RED" : paused ? "YELLOW" : "BLUE")));
            } catch (Throwable ignored) {
                // 忽略
            }

            if (!bar.getPlayers().contains(player)) {
                bar.addPlayer(player);
            }
        } catch (Throwable ignored) {
            // 玩家已离线等
        }
    }

    private BossBar createBossBar() {
        try {
            BarColor color = parseColor(plugin.getConfig().getString("boss-bar.color", "BLUE"));
            BarStyle style = parseStyle(plugin.getConfig().getString("boss-bar.style", "SEGMENTED_10"));
            return Bukkit.createBossBar("NewbieProtect", color, style);
        } catch (Throwable t) {
            plugin.getLogger().warning("创建 Boss 条失败: " + t.getMessage());
            return null;
        }
    }

    private static BarColor parseColor(String name) {
        if (name == null) {
            return BarColor.BLUE;
        }
        try {
            return BarColor.valueOf(name.trim().toUpperCase());
        } catch (Throwable ignored) {
            return BarColor.BLUE;
        }
    }

    private static BarStyle parseStyle(String name) {
        if (name == null) {
            return BarStyle.SEGMENTED_10;
        }
        try {
            return BarStyle.valueOf(name.trim().toUpperCase());
        } catch (Throwable ignored) {
            return BarStyle.SEGMENTED_10;
        }
    }

    /** 移除并丢弃该玩家的 Boss 条。 */
    public void removeBossBar(UUID uuid) {
        BossBar bar = bossBars.remove(uuid);
        if (bar != null) {
            try {
                bar.removeAll();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 对外状态查询                                                        */
    /* ------------------------------------------------------------------ */

    /** 剩余保护秒数；已结束或未开启返回 0。 */
    public long remainingSeconds(Player player) {
        long total = plugin.getProtectDurationSeconds();
        if (total <= 0) {
            return 0L;
        }
        PlayerDataStore.Entry entry = plugin.getDataStore().get(player.getUniqueId());
        if (entry == null) {
            // 还没记录（首次进服前）视为满时长
            return total;
        }
        if (entry.finished()) {
            return 0L;
        }
        // 绝对到期兜底：与 tickOne 的判断保持一致，
        // 避免「计时器卡住时事件仍认为在保护中」
        long maxDays = plugin.getConfig().getLong("protection.max-absolute-days", 30L);
        if (maxDays > 0L && entry.firstJoin() > 0L
                && System.currentTimeMillis() - entry.firstJoin() > maxDays * 86_400_000L) {
            return 0L;
        }
        return Math.max(0L, total - entry.usedSeconds());
    }

    /** 该玩家此刻是否处于新人保护中（已自助关闭 / 被管理员冻结的玩家不算）。 */
    public boolean isProtected(Player player) {
        if (!plugin.isProtectEnabled() || remainingSeconds(player) <= 0L) {
            return false;
        }
        if (isSelfDisabled(player) || isAdminPaused(player)) {
            return false;
        }
        // 打 Boss 战斗中且配置了「战斗不保护」→ 无效化
        if (getBossBattle().isInBattle(player) && !getBossBattle().isProtectInBattle()) {
            return false;
        }
        // 暂停区域内如果配置了「取消免伤」，则视为没有保护
        return !(isPaused(player) && !isProtectInPaused());
    }

    /* ------------------------------------------------------------------ */
    /* 暂停判定（世界列表 / 领地）                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 该玩家此刻是否处于「暂停计时」状态。
     *
     * <p>来源两种，任一命中即暂停：</p>
     * <ol>
     *     <li>站在配置「世界列表」命中的世界里</li>
     *     <li>站在自己的 Residence 领地里</li>
     * </ol>
     */
    public boolean isPaused(Player player) {
        if (player == null) {
            return false;
        }
        try {
            // 1) 世界列表
            if (plugin.isWorldListEnabled() && inListedWorld(player)) {
                return true;
            }
            // 2) 自己的领地
            if (plugin.isResidenceEnabled() && residenceHook.isAvailable()
                    && residenceHook.isInOwnResidence(player, plugin.isResidenceOwnerOnly())) {
                return true;
            }
        } catch (Throwable ignored) {
            // 任一判断出错都当作「不暂停」，保证不会意外卡住计时
        }
        return false;
    }

    private boolean inListedWorld(Player player) {
        org.bukkit.World world = player.getWorld();
        if (world == null) {
            return false;
        }
        boolean listed = plugin.isWorldListed(world);
        // allow = 只有列表内世界生效；deny = 列表内世界除外
        return "deny".equals(plugin.getWorldListMode()) ? !listed : listed;
    }

    /**
     * 暂停区域内，保护计时是否照常计算（true = 照常扣时间）。
     *
     * <p>世界和领地分别可配，任何一项设为「照常计算」都优先扣时间。</p>
     */
    public boolean isCountInPaused() {
        return plugin.getConfig().getBoolean("world-list.count-while-in-paused-world", false)
                || plugin.getConfig().getBoolean("residence.count-while-inside", false);
    }

    /**
     * 暂停区域内，是否保留免伤保护（true = 仍然免伤）。
     */
    public boolean isProtectInPaused() {
        return plugin.getConfig().getBoolean("world-list.protect-in-paused-world", false)
                || plugin.getConfig().getBoolean("residence.protect-in-own-residence", false);
    }

    /** 暂停原因文案（用于 Boss 条 / 消息），不暂停返回空串。 */
    public String pauseReason(Player player) {
        if (player == null) {
            return "";
        }
        if (plugin.isWorldListEnabled() && inListedWorld(player)) {
            String name = player.getWorld() == null ? "?" : player.getWorld().getName();
            return plugin.getConfig().getString("messages.pause-reason-world", "世界 %world% 内不计时")
                    .replace("%world%", name);
        }
        if (plugin.isResidenceEnabled() && residenceHook.isAvailable()
                && residenceHook.isInOwnResidence(player, plugin.isResidenceOwnerOnly())) {
            return plugin.getConfig().getString("messages.pause-reason-residence", "自己领地内不计时");
        }
        return "";
    }

    /** 该玩家是否被管理员冻结了计时。 */
    public boolean isAdminPaused(Player player) {
        if (player == null) {
            return false;
        }
        PlayerDataStore.Entry entry = plugin.getDataStore().get(player.getUniqueId());
        return entry != null && entry.adminPaused();
    }

    /** 管理员的暂停/恢复。返回 true 表示状态变化了。 */
    public boolean setAdminPaused(Player player, boolean paused) {
        if (player == null) {
            return false;
        }
        PlayerDataStore store = plugin.getDataStore();
        PlayerDataStore.Entry entry = store.getOrCreate(player.getUniqueId(), player.getName());
        if (entry.adminPaused() == paused) {
            return false;
        }
        entry.adminPaused(paused);
        store.markDirty();
        refresh(player);
        return true;
    }

    /**
     * 管理员设置玩家「还剩多少时间」。
     *
     * @param seconds 新的剩余秒数（0 = 立即结束保护）
     * @return true 表示设置成功
     */
    public boolean setRemainingSeconds(Player player, long seconds) {
        if (player == null) {
            return false;
        }
        PlayerDataStore store = plugin.getDataStore();
        PlayerDataStore.Entry entry = store.getOrCreate(player.getUniqueId(), player.getName());
        long total = plugin.getProtectDurationSeconds();
        long clamped = Math.max(0L, seconds);
        entry.usedSeconds(Math.max(0L, total - clamped));
        entry.finished(clamped <= 0L);
        store.markDirty();
        refresh(player);
        return true;
    }

    /**
     * 管理员清空（重置）玩家的保护记录，让他重新变回新人。
     */
    public void resetPlayer(Player player) {
        if (player == null) {
            return;
        }
        PlayerDataStore store = plugin.getDataStore();
        PlayerDataStore.Entry entry = store.getOrCreate(player.getUniqueId(), player.getName());
        entry.usedSeconds(0L);
        entry.finished(false);
        entry.selfDisabled(false);
        entry.adminPaused(false);
        entry.firstJoin(System.currentTimeMillis());
        store.markDirty();
        refresh(player);
    }

    /** 刷新某个玩家的 Boss 条 / 会话状态（在他自己的区域线程上）。 */
    public void refresh(Player player) {
        if (player == null) {
            return;
        }
        SchedulerUtils.runEntity(plugin, player, () -> {
            try {
                UUID uuid = player.getUniqueId();
                long left = remainingSeconds(player);
                if (left <= 0L) {
                    removeBossBar(uuid);
                    wasProtected.put(uuid, false);
                    return;
                }
                boolean paused = isPaused(player);
                updateBossBar(player, uuid, left, plugin.getProtectDurationSeconds(),
                        isSelfDisabled(player), paused, getBossBattle().isInBattle(player));
                wasProtected.put(uuid, !isSelfDisabled(player));
            } catch (Throwable ignored) {
                // 忽略
            }
        });
    }

    /** 该玩家是否被管理员用 /newbie pause 冻结了计时。 */
    public boolean adminPaused(Player player) {
        return isAdminPaused(player);
    }

    /** 该玩家是否自己用 /newbie off 关闭了保护（且人还在线）。 */
    public boolean isSelfDisabled(Player player) {
        if (player == null) {
            return false;
        }
        PlayerDataStore.Entry entry = plugin.getDataStore().get(player.getUniqueId());
        return entry != null && entry.selfDisabled();
    }

    /**
     * 设置玩家的「自助关闭保护」状态。
     *
     * <p>返回 true 表示状态发生了变化并已写入数据（需要落盘）。</p>
     */
    public boolean setSelfDisabled(Player player, boolean disabled) {
        if (player == null) {
            return false;
        }
        PlayerDataStore store = plugin.getDataStore();
        PlayerDataStore.Entry entry = store.getOrCreate(player.getUniqueId(), player.getName());
        if (entry.selfDisabled() == disabled) {
            return false;
        }
        entry.selfDisabled(disabled);
        store.markDirty();
        UUID uuid = player.getUniqueId();
        // 记录「上一秒是否保护中」，避免 tickOne 误发重复提示
        wasProtected.put(uuid, !disabled && remainingSeconds(player) > 0L);
        // 立刻让 Boss 条切成对应样式（不用等到下一秒 tick）
        try {
            updateBossBar(player, uuid,
                    Math.max(0L, remainingSeconds(player)),
                    plugin.getProtectDurationSeconds(), disabled, isPaused(player),
                    getBossBattle().isInBattle(player));
        } catch (Throwable ignored) {
            // 忽略
        }
        return true;
    }

    /** 玩家进服时调用：初始化会话状态。 */
    public void onJoin(Player player) {
        if (!plugin.isProtectEnabled()) {
            return;
        }
        long total = plugin.getProtectDurationSeconds();
        if (total <= 0) {
            return;
        }
        UUID uuid = player.getUniqueId();
        PlayerDataStore.Entry entry = plugin.getDataStore().getOrCreate(uuid, player.getName());
        if (entry.finished()) {
            plugin.sendConfigMessage(player, "join-expired", null);
            wasProtected.put(uuid, false);
            removeBossBar(uuid);
            return;
        }
        long left = Math.max(0L, total - entry.usedSeconds());
        if (entry.selfDisabled()) {
            // 上次自己关掉了保护，这次进服提醒一下怎么重新开启
            plugin.sendConfigMessage(player, "join-self-disabled",
                    "%time%", format(left),
                    "%timing%", timingNote());
            wasProtected.put(uuid, false);
        } else {
            plugin.sendConfigMessage(player, "join-protected", "%time%", format(left));
            wasProtected.put(uuid, left > 0);
        }
    }

    /** 「关闭期间计时规则」的说明文字（供消息/Boss 条使用）。 */
    public String timingNote() {
        return plugin.isCountWhileDisabled()
                ? "保护时间仍在继续倒计时（关闭期间不返还时间）"
                : "保护计时已暂停，剩余时间不会被消耗";
    }

    /** 玩家退服时调用：移除 Boss 条并清掉会话缓存。 */
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        removeBossBar(uuid);
        reminded.remove(uuid);
        wasProtected.remove(uuid);
        getBossBattle().clear(uuid);
    }

    /** reload 时清理全部界面状态。 */
    public void clearAllBossBars() {
        for (BossBar bar : bossBars.values()) {
            try {
                bar.removeAll();
            } catch (Throwable ignored) {
                // ignore
            }
        }
        bossBars.clear();
        reminded.clear();
        wasProtected.clear();
        if (bossBattle != null) {
            bossBattle.clearAll();
        }
    }

    /** 已在显示 Boss 条的玩家数量（诊断用）。 */
    public int bossBarCount() {
        return bossBars.size();
    }

    /** 指定玩家此刻是否有 Boss 条（诊断 / 测试用）。 */
    public boolean hasBossBar(UUID uuid) {
        return uuid != null && bossBars.containsKey(uuid);
    }

    /** 取指定玩家当前的 Boss 条实例（诊断 / 测试用，可能为 null）。 */
    public BossBar getBossBar(UUID uuid) {
        return uuid == null ? null : bossBars.get(uuid);
    }

    /* ------------------------------------------------------------------ */
    /* 时间格式化                                                          */
    /* ------------------------------------------------------------------ */

    /** 把秒数格式化为「1时2分3秒」；0 返回「0秒」。 */
    public static String format(long seconds) {
        if (seconds <= 0L) {
            return "0秒";
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append("时");
        }
        if (minutes > 0) {
            sb.append(minutes).append("分");
        }
        sb.append(secs).append('秒');
        return sb.toString();
    }
}
