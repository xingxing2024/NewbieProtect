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
    private final BossBattleManager bossBattle;
    private SchedulerUtils.Cancellable tickTask;
    /** 是否已彻底关闭（关闭后 tick 不再执行任何逻辑）。 */
    private volatile boolean shutDown = false;

    public ProtectionManager(NewbieProtect plugin) {
        this.plugin = plugin;
        this.bossBattle = new BossBattleManager(plugin);
    }

    public com.newbie.protect.hook.ResidenceHook getResidenceHook() {
        return residenceHook;
    }

    public com.newbie.protect.manager.BossBattleManager getBossBattle() {
        return bossBattle;
    }

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    public void start() {
        stop();
        shutDown = false;
        bossBattle.reload();
        if (!plugin.isProtectEnabled()) {
            plugin.getLogger().info(reason("console.timer-disabled", "新人保护总开关已关闭，不启动计时任务。"));
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
        plugin.getLogger().info(reason("console.timer-started", "新人保护计时任务已启动（总时长 %minutes% 分钟）。")
                .replace("%minutes%", String.valueOf(plugin.getProtectDurationSeconds() / 60)));
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
        bossBattle.clearAll();
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
        if (shutDown || plugin.isShuttingDown() || !plugin.isProtectEnabled()
                || player == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        PlayerDataStore store = plugin.getDataStore();
        PlayerDataStore.Entry entry = store.get(uuid);
        if (entry == null) return;

        ProtectionState before = state(player);
        if (before.remainingSeconds() <= 0L) {
            if (!entry.finished()) {
                entry.finished(true);
                store.markDirty();
            }
            if (Boolean.TRUE.equals(wasProtected.put(uuid, false))) {
                plugin.sendConfigMessage(player, "expired");
            }
            removeBossBar(uuid);
            return;
        }
        if (before.counting()) {
            entry.addUsedSeconds(1L);
            store.markDirty();
        }
        ProtectionState now = state(player);
        long left = now.remainingSeconds();
        Boolean previous = wasProtected.put(uuid, now.protectedNow());
        if (left <= 0L) {
            entry.finished(true);
            store.markDirty();
            removeBossBar(uuid);
            plugin.sendConfigMessage(player, "expired");
            return;
        }
        if (now.protectedNow() && !Boolean.TRUE.equals(previous)) {
            plugin.sendConfigMessage(player, "join-protected", "%time%", plugin.formatDuration(left));
        }
        if (now.protectedNow()) checkRemind(player, uuid, left);
        updateBossBar(player, uuid, left, total, entry.selfDisabled(),
                now.paused() || areaState(player).paused(), getBossBattle().isInBattle(player));
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
                plugin.sendConfigMessage(player, "ending-soon", "%time%", plugin.formatDuration(point));
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
            ProtectionState actual = state(player);
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
            // 标题从语言文件读取（找不到则用上面的内置 fallback）
            String raw = plugin.langRaw(key, fallback);
            if (raw == null || raw.isEmpty()) {
                raw = fallback;
            }

            String timing = timingNote(actual);

            // Boss 条标题：支持插件占位符 + PAPI 变量
            String title = plugin.parsePlaceholders(raw, player,
                    "%time%", plugin.formatDuration(left),
                    "%seconds%", String.valueOf(left),
                    "%player%", player.getName(),
                    "%state%", actual.protectedNow()
                            ? plugin.langRaw("boss-bar.state-on", "保护中")
                            : plugin.langRaw("boss-bar.state-unprotected", "不受保护"),
                    "%reason%", actual.pauseReason(),
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

    public record ProtectionState(long remainingSeconds, boolean protectedNow, boolean counting,
                                  boolean paused, String pauseReason, String statusKey) {}

    public ProtectionState state(Player player) {
        if (player == null) return inactiveState(0L, "unknown", "");
        PlayerDataStore.Entry entry = plugin.getDataStore().get(player.getUniqueId());
        if (entry == null) return inactiveState(0L, "unknown", "");
        long left = remainingSeconds(entry);
        if (left <= 0L) return inactiveState(0L, "finished", "");
        if (shutDown || plugin.isShuttingDown() || !plugin.isProtectEnabled()) {
            return inactiveState(left, "inactive", reason("messages.pause-reason-inactive", "插件已停用"));
        }
        if (!player.isOnline()) return state(player.getUniqueId());
        AreaState area = areaState(player);
        boolean battle = bossBattle.isInBattle(player);
        boolean count = !entry.adminPaused() && !(battle && bossBattle.isPauseTimer())
                && area.countTime() && (!entry.selfDisabled() || plugin.isCountWhileDisabled());
        boolean protection = !entry.adminPaused() && !entry.selfDisabled() && area.protectHere()
                && (!battle || bossBattle.isProtectInBattle());
        String status = entry.adminPaused() ? "admin-paused" : entry.selfDisabled() ? "disabled"
                : battle ? "boss-battle" : !count ? "paused" : !protection ? "unprotected" : "protected";
        String why = entry.adminPaused() ? reason("messages.pause-reason-admin", "管理员冻结")
                : entry.selfDisabled() && !plugin.isCountWhileDisabled()
                    ? reason("messages.pause-reason-self", "玩家已关闭保护，计时暂停")
                : battle ? reason("messages.pause-reason-boss-battle", "Boss 战斗中")
                : area.paused() ? pauseReason(player) : "";
        return new ProtectionState(left, protection, count, !count, why, status);
    }

    /** 离线查询仅访问已保存的记录，不读取实体位置，也不创建保护。 */
    public ProtectionState state(UUID uuid) {
        PlayerDataStore.Entry entry = uuid == null ? null : plugin.getDataStore().get(uuid);
        if (entry == null) return inactiveState(0L, "unknown", "");
        long left = remainingSeconds(entry);
        if (left <= 0L) return inactiveState(0L, "finished", "");
        if (shutDown || plugin.isShuttingDown() || !plugin.isProtectEnabled()) {
            return inactiveState(left, "inactive", reason("messages.pause-reason-inactive", "插件已停用"));
        }
        String status = entry.adminPaused() ? "admin-paused" : entry.selfDisabled() ? "disabled" : "protected-offline";
        String why = entry.adminPaused() ? reason("messages.pause-reason-admin", "管理员冻结")
                : reason("messages.pause-reason-offline", "玩家离线，计时暂停");
        return inactiveState(left, status, why);
    }

    private ProtectionState inactiveState(long left, String status, String why) {
        return new ProtectionState(left, false, false, left > 0, why, status);
    }

    private String reason(String key, String fallback) {
        String text = plugin.langText(key, fallback);
        return text == null ? fallback : text;
    }

    public long remainingSeconds(Player player) {
        return player == null ? 0L : remainingSeconds(plugin.getDataStore().get(player.getUniqueId()));
    }

    private long remainingSeconds(PlayerDataStore.Entry entry) {
        long total = plugin.getProtectDurationSeconds();
        if (entry == null || entry.finished() || total <= 0L) return 0L;
        long maxDays = plugin.getConfig().getLong("protection.max-absolute-days", 30L);
        if (maxDays > 0L && entry.firstJoin() > 0L
                && System.currentTimeMillis() - entry.firstJoin() > java.util.concurrent.TimeUnit.DAYS.toMillis(maxDays)) {
            return 0L;
        }
        try {
            return Math.max(0L, Math.subtractExact(total, entry.usedSeconds()));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public boolean isProtected(Player player) {
        return state(player).protectedNow();
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
        return areaState(player).paused();
    }

    private record AreaState(boolean paused, boolean countTime, boolean protectHere) {}

    /** 只应用当前位置命中的规则；重叠时暂停 / 禁止免伤优先。 */
    private AreaState areaState(Player player) {
        boolean paused = false;
        boolean count = true;
        boolean protect = true;
        if (player == null) return new AreaState(false, true, true);
        try {
            if (plugin.isWorldListEnabled() && inListedWorld(player)) {
                paused = true;
                count &= plugin.getConfig().getBoolean("world-list.count-while-in-paused-world", false);
                protect &= plugin.getConfig().getBoolean("world-list.protect-in-paused-world", false);
            }
            if (plugin.isResidenceEnabled() && residenceHook.isAvailable()
                    && residenceHook.isInOwnResidence(player, plugin.isResidenceOwnerOnly())) {
                paused = true;
                count &= plugin.getConfig().getBoolean("residence.count-while-inside", false);
                protect &= plugin.getConfig().getBoolean("residence.protect-in-own-residence", false);
            }
        } catch (RuntimeException ignored) {
            // 保留已判定的规则；集成暂不可用时跳过该集成。
        }
        return new AreaState(paused, count, protect);
    }

    public boolean isCountInPaused(Player player) {
        return areaState(player).countTime();
    }

    public boolean isProtectInPaused(Player player) {
        return areaState(player).protectHere();
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
     * @deprecated 仅汇总配置；实际玩家规则请使用带 Player 参数的方法。
     */
    @Deprecated
    public boolean isCountInPaused() {
        return plugin.getConfig().getBoolean("world-list.count-while-in-paused-world", false)
                || plugin.getConfig().getBoolean("residence.count-while-inside", false);
    }

    /**
     * 暂停区域内，是否保留免伤保护（true = 仍然免伤）。
     */
    @Deprecated
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
            return plugin.langText("messages.pause-reason-world", "世界 %world% 区域规则",
                    "%world%", name);
        }
        if (plugin.isResidenceEnabled() && residenceHook.isAvailable()
                && residenceHook.isInOwnResidence(player, plugin.isResidenceOwnerOnly())) {
            return plugin.langText("messages.pause-reason-residence", "领地区域规则");
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
        if (player == null || seconds < 0L) return false;
        setRemainingSeconds(player.getUniqueId(), player.getName(), seconds);
        refresh(player);
        return true;
    }

    public void setRemainingSeconds(UUID uuid, String name, long seconds) {
        if (seconds < 0L) throw new IllegalArgumentException("Negative remaining duration");
        PlayerDataStore.Entry entry = plugin.getDataStore().getOrCreate(uuid, name);
        long old = remainingSeconds(entry);
        long total = Math.max(0L, plugin.getProtectDurationSeconds());
        entry.usedSeconds(total - seconds);
        entry.finished(seconds == 0L);
        if (seconds > 0L && old <= 0L) entry.firstJoin(System.currentTimeMillis());
        if (seconds > old) reminded.remove(uuid);
        wasProtected.remove(uuid);
        plugin.getDataStore().markDirty();
    }

    public void resetRecord(UUID uuid, String name, long seconds) {
        PlayerDataStore.Entry entry = plugin.getDataStore().reset(uuid, name);
        entry.usedSeconds(Math.max(0L, plugin.getProtectDurationSeconds()) - Math.max(0L, seconds));
        entry.finished(seconds <= 0L);
        onRecordCleared(uuid);
        plugin.getDataStore().markDirty();
    }

    public void onRecordCleared(UUID uuid) {
        reminded.remove(uuid);
        wasProtected.remove(uuid);
        removeBossBar(uuid);
        bossBattle.clear(uuid);
    }

    /**
     * 管理员清空（重置）玩家的保护记录，让他重新变回新人。
     */
    public void resetPlayer(Player player) {
        if (player == null) return;
        resetRecord(player.getUniqueId(), player.getName(), plugin.getProtectDurationSeconds());
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
                boolean paused = isPaused(player) || isAdminPaused(player)
                        || (getBossBattle().isInBattle(player) && getBossBattle().isPauseTimer());
                updateBossBar(player, uuid, left, plugin.getProtectDurationSeconds(),
                        isSelfDisabled(player), paused, getBossBattle().isInBattle(player));
                wasProtected.put(uuid, isProtected(player));
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
        if (!plugin.isProtectEnabled() || plugin.isShuttingDown() || shutDown) return;
        UUID uuid = player.getUniqueId();
        PlayerDataStore.Entry entry = plugin.getDataStore().get(uuid);
        if (entry == null) {
            if (!plugin.getConfig().getBoolean("protection.start-on-first-join", true)
                    || plugin.getProtectDurationSeconds() <= 0L) return;
            entry = plugin.getDataStore().getOrCreate(uuid, player.getName());
        }
        ProtectionState current = state(player);
        wasProtected.put(uuid, current.protectedNow());
        if (current.remainingSeconds() <= 0L) {
            plugin.sendConfigMessage(player, "join-expired");
            removeBossBar(uuid);
        } else if (entry.selfDisabled()) {
            plugin.sendConfigMessage(player, "join-self-disabled", "%time%", plugin.formatDuration(current.remainingSeconds()),
                    "%timing%", timingNote(current));
        } else if (current.protectedNow()) {
            plugin.sendConfigMessage(player, "join-protected", "%time%", plugin.formatDuration(current.remainingSeconds()));
        } else {
            plugin.sendConfigMessage(player, "join-not-protected", "%time%", plugin.formatDuration(current.remainingSeconds()),
                    "%reason%", current.pauseReason(), "%timing%", timingNote(current));
        }
    }

    /** 文本和实际计时使用同一状态，不由自助开关配置推测。 */
    public String timingNote(ProtectionState state) {
        return state.counting()
                ? plugin.langRaw("boss-bar.pause-note-counting", "时间照常计算")
                : plugin.langRaw("boss-bar.pause-note-paused", "计时已暂停");
    }

    /** 「关闭期间计时规则」的说明文字（供消息/Boss 条使用）。 */
    public String timingNote() {
        return plugin.isCountWhileDisabled()
                ? plugin.langRaw("boss-bar.timing-note-counting", "保护时间仍在继续倒计时")
                : plugin.langRaw("boss-bar.timing-note-paused", "保护计时已暂停，剩余时间保留");
    }

    /** 玩家退服时调用：移除 Boss 条并清掉会话缓存。 */
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        removeBossBar(uuid);
        reminded.remove(uuid);
        wasProtected.remove(uuid);
        // 保留战斗时间戳，防止重登绕过脱战超时。
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
