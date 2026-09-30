package com.newbie.protect;

import com.newbie.protect.command.NewbieCommand;
import com.newbie.protect.data.PlayerDataStore;
import com.newbie.protect.listener.ProtectionListener;
import com.newbie.protect.manager.ProtectionManager;
import com.newbie.protect.util.SchedulerUtils;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NewbieProtect - 新人保护插件。
 *
 * <p>首次进入服务器的玩家，在累计在线时长用满配置的总时长之前，
 * 免受怪物伤害与玩家 PVP。</p>
 *
 * <p>特性：</p>
 * <ul>
 *     <li>只计在线时长，下线暂停</li>
 *     <li>Boss 条实时倒计时，归零自动消失</li>
 *     <li>到期提醒（可配置档位）</li>
 *     <li>数据持久化，重启不丢</li>
 *     <li>config.yml 缺失时 reload 自动重新生成</li>
 * </ul>
 */
public final class NewbieProtect extends JavaPlugin {

    private static final String[] DEFAULT_FILES = {"config.yml"};
    /** 当前配置结构版本（config.yml 里的 config-version）。 */
    private static final int CONFIG_VERSION = 2;

    private PlayerDataStore dataStore;
    private ProtectionManager manager;
    private SchedulerUtils.Cancellable saveTask;
    private volatile boolean shuttingDown = false;
    /** JVM 关闭钩子（应对 SIGTERM / 面板停止）。 */
    private Thread shutdownHook;

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void onEnable() {
        shuttingDown = false;

        // 1. 配置：先确保文件存在，再读进内存，最后补齐缺失项
        //    顺序很重要：saveResource 只负责写文件，必须 reloadConfig 才能读到内容
        ensureResources();
        reloadConfig();
        ensureConfigComplete();

        // 2. 数据
        this.dataStore = new PlayerDataStore(this);
        this.dataStore.load();

        // 3. 管理器
        this.manager = new ProtectionManager(this);

        // 4. 监听器
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);

        // 5. 命令
        PluginCommand command = getCommand("newbie");
        if (command != null) {
            NewbieCommand executor = new NewbieCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().severe("plugin.yml 缺少 newbie 命令定义！");
        }

        // 6. 注册 JVM 关闭钩子：应对「面板停止 / SIGTERM」这类
        //    不一定走完 Bukkit onDisable 的场景
        registerShutdownHook();

        // 7. 启动计时任务
        manager.start();

        // 8. 定时落盘（每 10 秒，若有改动才写）
        this.saveTask = SchedulerUtils.runTimer(this, this::flushIfDirty, 20L * 10L, 20L * 10L);

        // 9. 防御性清理：如果服务器已在运行（例如 /reload 后重入），
        //    清掉可能残留的 Boss 条，避免旧实例留下的界面残留
        try {
            manager.clearAllBossBars();
        } catch (Throwable ignored) {
            // ignore
        }

        // 10. 注册 PlaceholderAPI 变量扩展（软依赖）
        registerPlaceholders();

        getLogger().info("NewbieProtect 已启用"
                + " (Folia=" + SchedulerUtils.isFolia()
                + ", 保护=" + (isProtectEnabled() ? "开" : "关")
                + ", 总时长=" + (getProtectDurationSeconds() / 60) + " 分钟"
                + ", 自助开关=" + (isSelfToggleEnabled() ? "允许" : "禁止")
                + ", 已记录=" + dataStore.size() + " 人)");
        getLogger().info("提示：本插件不修改任何玩家持久状态，"
                + "卸载/重启后不会出现「无敌残留」。");
    }

    /* ------------------------------------------------------------------ */
    /* PlaceholderAPI                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * 注册 PAPI 变量扩展（软依赖）。
     *
     * <p>没装 PlaceholderAPI 时静默跳过，不影响其他功能。</p>
     */
    private void registerPlaceholders() {
        // 关键：先判断有没有 PAPI，再决定是否加载引用 PAPI 的类，
        // 否则没装 PAPI 的服务器会抛 NoClassDefFoundError。
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("未检测到 PlaceholderAPI，跳过变量注册"
                    + "（变量如 %newbieprotect_time% 需要 PAPI）。");
            return;
        }
        try {
            boolean ok = new com.newbie.protect.hook.PlaceholderBridgeExtension(this).register();
            if (ok) {
                getLogger().info("已注册 PlaceholderAPI 变量：%newbieprotect_time% 等。");
            } else {
                getLogger().warning("PlaceholderAPI 变量注册失败（返回 false）。");
            }
        } catch (Throwable t) {
            getLogger().warning("注册 PlaceholderAPI 变量时出错（已跳过）: "
                    + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    @Override
    public void onDisable() {
        performShutdown("Bukkit onDisable");
        unregisterShutdownHook();
    }

    /**
     * 统一的关闭流程（幂等，可重复调用）。
     *
     * <p>关服 / 重载 / 插件卸载 / JVM 关闭钩子都会走这里：</p>
     * <ol>
     *     <li>置 shuttingDown，所有入口立即失效</li>
     *     <li>取消定时任务</li>
     *     <li>移除全部 Boss 条（避免卸载后界面残留）</li>
     *     <li>强制落盘数据</li>
     * </ol>
     */
    public void performShutdown(String reason) {
        if (shuttingDown && reason.equals("repeat")) {
            return;
        }
        boolean first = !shuttingDown;
        shuttingDown = true;

        if (first) {
            getLogger().info("正在关闭新人保护（" + reason + "）...");
        }

        // 1) 取消定时任务
        try {
            if (saveTask != null) {
                saveTask.cancel();
                saveTask = null;
            }
        } catch (Throwable t) {
            getLogger().warning("取消落盘任务失败: " + t.getMessage());
        }

        // 2) 移除所有 Boss 条
        try {
            if (manager != null) {
                manager.shutdown();
            }
        } catch (Throwable t) {
            getLogger().warning("清理 Boss 条失败: " + t.getMessage());
        }

        // 3) 落盘
        try {
            if (dataStore != null) {
                dataStore.save();
                getLogger().info("新人保护数据已保存（" + dataStore.size() + " 条）。");
            }
        } catch (Throwable t) {
            getLogger().warning("保存数据失败: " + t.getMessage());
        }

        if (first) {
            getLogger().info("NewbieProtect 已安全关闭。");
        }
    }

    /* ------------------------------------------------------------------ */
    /* JVM 关闭钩子                                                        */
    /* ------------------------------------------------------------------ */

    private void registerShutdownHook() {
        try {
            Thread hook = new Thread(() -> {
                // 用 System.out 而不是 logger：正常关服时 Bukkit 可能已经关闭了
                // log4j，此时 logger 输出会丢失，而 System.out 仍然可用。
                try {
                    if (shuttingDown) {
                        // onDisable 已经完整清理过了，钩子只需确认
                        System.out.println("[NewbieProtect] 关闭钩子触发："
                                + "onDisable 已完成清理，无需重复。");
                        return;
                    }
                    System.out.println("[NewbieProtect] 关闭钩子触发："
                            + "onDisable 未执行（面板停止 / SIGTERM），现在执行清理。");
                    performShutdown("JVM 关闭钩子");
                } catch (Throwable ignored) {
                    // 钩子里绝不能再抛异常
                }
            }, "NewbieProtect-Shutdown");
            hook.setDaemon(false);
            Runtime.getRuntime().addShutdownHook(hook);
            this.shutdownHook = hook;
            getLogger().info("已注册 JVM 关闭钩子（应对面板停止 / SIGTERM）。");
        } catch (Throwable t) {
            getLogger().warning("注册 JVM 关闭钩子失败: " + t.getMessage());
        }
    }

    private void unregisterShutdownHook() {
        Thread hook = this.shutdownHook;
        if (hook == null) {
            return;
        }
        this.shutdownHook = null;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (Throwable ignored) {
            // 已在关闭流程中，移除会抛 IllegalStateException，忽略
        }
    }

    /** 玩家退出时调用：立即落盘，避免强杀丢数据。 */
    public void saveOnQuit() {
        if (shuttingDown || dataStore == null) {
            return;
        }
        try {
            dataStore.save();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 延迟落盘（有改动才写）。 */
    private void flushIfDirty() {
        if (shuttingDown || dataStore == null) {
            return;
        }
        if (dataStore.isDirty()) {
            dataStore.save();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 配置                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 确保 config.yml 存在；不存在时从 jar 内重新生成。
     *
     * <p>这样即使用户误删了配置文件，`/newbie reload` 也能自动恢复默认配置，
     * 而不会一直沿用内存里的旧值。</p>
     */
    private void ensureResources() {
        for (String name : DEFAULT_FILES) {
            File file = new File(getDataFolder(), name);
            if (!file.isFile()) {
                try {
                    saveResource(name, false);
                    getLogger().info("未找到 " + name + "，已自动生成默认配置。");
                } catch (IllegalArgumentException e) {
                    getLogger().warning("自动生成 " + name + " 失败: " + e.getMessage());
                }
            }
        }
        if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
            getLogger().warning("无法创建数据目录: " + getDataFolder());
        }
    }

    /**
     * 手动触发配置补全（供 /newbie fixconfig 调用）。
     *
     * @return 补写的项数
     */
    public int forceConfigComplete() {
        try {
            ensureResources();
            reloadConfig();
            return ensureConfigComplete();
        } catch (Throwable t) {
            getLogger().warning("手动补全配置失败: " + t);
            return 0;
        }
    }

    /**
     * 确保配置完整：把 jar 内默认配置里缺失的项**补进**用户的 config.yml。
     *
     * <p>实现委托给 {@link com.newbie.protect.config.ConfigUpdater}：
     * 按缩进解析 YAML，把缺失的键（连同注释和子项）插入到**正确位置**，
     * 不覆盖已有值、不删除注释，也不需要重启服务器。</p>
     *
     * @return 补写的键数量；-1 表示整个文件被重新生成
     */
    private int ensureConfigComplete() {
        try {
            int n = com.newbie.protect.config.ConfigUpdater.update(this);
            if (n > 0) {
                // 重新读一遍，让内存配置与文件一致
                reloadConfig();
            }
            return n;
        } catch (Throwable t) {
            getLogger().warning("配置补全时出错: " + t);
            return 0;
        }
    }
    public void reloadAll() {
        if (shuttingDown) {
            getLogger().warning("插件正在卸载，忽略 reload。");
            return;
        }
        try {
            ensureResources();
            reloadConfig();
        } catch (Throwable t) {
            getLogger().warning("重载配置失败: " + t);
            return;
        }
        ensureConfigComplete();
        try {
            if (manager != null) {
                manager.clearAllBossBars();
                manager.start();
            }
        } catch (Throwable t) {
            getLogger().warning("重启计时任务失败: " + t);
        }
        getLogger().info("配置已重载（总时长 " + (getProtectDurationSeconds() / 60) + " 分钟）。");
    }

    /** 总开关。 */
    public boolean isProtectEnabled() {
        return getConfig().getBoolean("enabled", true);
    }

    /** 是否允许玩家自助开启/关闭保护（/newbie on|off）。 */
    public boolean isSelfToggleEnabled() {
        return getConfig().getBoolean("self-toggle.enabled", true);
    }

    /** 是否允许玩家自己隐藏 / 显示 Boss 条（/newbie bar）。 */
    public boolean isBossBarToggleEnabled() {
        return getConfig().getBoolean("boss-bar.allow-player-toggle", true);
    }

    /** 该玩家的 Boss 条是否被自己关掉了。 */
    public boolean isBossBarHidden(java.util.UUID uuid) {
        if (uuid == null || dataStore == null) {
            return false;
        }
        PlayerDataStore.Entry entry = dataStore.get(uuid);
        return entry != null && entry.bossBarHidden();
    }

    /** PAPI 用：该玩家的 Boss 条当前是否处于「显示」状态。 */
    public boolean isBossBarVisibleTo(java.util.UUID uuid) {
        if (!getConfig().getBoolean("boss-bar.enabled", true)) {
            return false;
        }
        return !isBossBarHidden(uuid);
    }

    /**
     * 设置玩家的 Boss 条显示开关。
     *
     * @return true 表示状态变化了
     */
    public boolean setBossBarHidden(Player player, boolean hidden) {
        if (player == null || dataStore == null) {
            return false;
        }
        PlayerDataStore.Entry entry = dataStore.getOrCreate(player.getUniqueId(), player.getName());
        if (entry.bossBarHidden() == hidden) {
            return false;
        }
        entry.bossBarHidden(hidden);
        dataStore.markDirty();
        if (manager != null) {
            if (hidden) {
                manager.removeBossBar(player.getUniqueId());
            } else {
                manager.refresh(player);
            }
        }
        return true;
    }

    /**
     * 玩家自己关闭保护期间，保护时长是否照常计算。
     *
     * <p>默认 {@code true}：关掉保护并不会省下时间 —— 说服玩家「要就现在用」，
     * 避免有人长时间关着保护、留着时长以后再开。</p>
     */
    public boolean isCountWhileDisabled() {
        return getConfig().getBoolean("self-toggle.count-while-disabled", true);
    }

    /* ------------------------------------------------------------------ */
    /* 世界列表（暂停计时 / 忽略保护）                                      */
    /* ------------------------------------------------------------------ */

    /** world-list.mode：allow = 只有列表内世界生效；deny = 列表内世界除外。 */
    public String getWorldListMode() {
        String mode = getConfig().getString("world-list.mode", "allow");
        return mode == null ? "allow" : mode.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 世界列表功能是否启用（列表为空时自动视为不启用）。 */
    public boolean isWorldListEnabled() {
        if (!getConfig().getBoolean("world-list.enabled", true)) {
            return false;
        }
        return !getListedWorlds().isEmpty() || !getListedWorldTypes().isEmpty();
    }

    /** 配置里列出的世界名（小写）。 */
    public java.util.Set<String> getListedWorlds() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String raw : getConfig().getStringList("world-list.worlds")) {
            if (raw != null && !raw.isBlank()) {
                set.add(raw.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return set;
    }

    /** 配置里列出的世界类型：NORMAL / NETHER / THE_END / CUSTOM。 */
    public java.util.Set<String> getListedWorldTypes() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String raw : getConfig().getStringList("world-list.world-types")) {
            if (raw != null && !raw.isBlank()) {
                set.add(raw.trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return set;
    }

    /**
     * 判断某世界是否落在「列表内」。
     *
     * <p>世界名与环境类型任一命中即算命中。只写 world-types（如 NETHER）
     * 就能一次性匹配所有下界世界，不用逐个写名字。</p>
     */
    public boolean isWorldListed(org.bukkit.World world) {
        if (world == null) {
            return false;
        }
        String name = world.getName() == null ? "" : world.getName().toLowerCase(java.util.Locale.ROOT);
        if (getListedWorlds().contains(name)) {
            return true;
        }
        try {
            String env = world.getEnvironment().name().toUpperCase(java.util.Locale.ROOT);
            return getListedWorldTypes().contains(env);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 领地（Residence）                                                    */
    /* ------------------------------------------------------------------ */

    /** 是否启用「在自己领地里暂停计时」。 */
    public boolean isResidenceEnabled() {
        return getConfig().getBoolean("residence.enabled", true);
    }

    /** 领地暂停是否只对自己名下的领地生效（true = 只有房主自己享受）。 */
    public boolean isResidenceOwnerOnly() {
        return getConfig().getBoolean("residence.owner-only", true);
    }

    /** 保护总时长（秒）。 */
    public long getProtectDurationSeconds() {
        long minutes = getConfig().getLong("protection.duration-minutes", 120L);
        return Math.max(0L, minutes) * 60L;
    }

    /* ------------------------------------------------------------------ */
    /* 管理员操作上限                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * 管理员能给玩家设置的最大剩余时长（秒）。
     *
     * <p>{@code -1} 表示不限制；{@code protection.admin-limit.enabled}
     * 为 false 时也不限制。</p>
     */
    public long getAdminMaxSeconds() {
        try {
            if (!getConfig().getBoolean("protection.admin-limit.enabled", false)) {
                return -1L;
            }
            long max = getConfig().getLong("protection.admin-limit.max-seconds", -1L);
            return max <= 0L ? -1L : max;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** 是否启用了管理员操作上限。 */
    public boolean hasAdminLimit() {
        return getAdminMaxSeconds() > 0L;
    }

    /** 把管理员输入的时间按上限裁剪。 */
    public long clampAdminSeconds(long seconds) {
        long max = getAdminMaxSeconds();
        return max <= 0L ? seconds : Math.min(seconds, max);
    }

    /** 到期提醒档位（秒），空列表表示不提醒。 */
    public List<Long> getRemindSeconds() {
        List<Long> result = new ArrayList<>();
        try {
            // 注意：配置里写成 [300, 60, 10] 时 getIntegerList 可用；
            // 但也兼容写成字符串形式，避免类型转换异常导致 reload 失败。
            for (Object raw : getConfig().getList("remind-seconds", new ArrayList<>())) {
                if (raw == null) {
                    continue;
                }
                if (raw instanceof Number number) {
                    long value = number.longValue();
                    if (value > 0L) {
                        result.add(value);
                    }
                    continue;
                }
                try {
                    long value = Long.parseLong(String.valueOf(raw).trim());
                    if (value > 0L) {
                        result.add(value);
                    }
                } catch (NumberFormatException ignored) {
                    // 跳过无法解析的项
                }
            }
        } catch (Throwable t) {
            getLogger().warning("读取 remind-seconds 失败，将不发送到期提醒: " + t.getMessage());
        }
        return result;
    }

    /** 按 config 的 messages.<key> 发送消息，支持插件占位符 + PAPI 变量。 */
    public void sendConfigMessage(CommandSender target, String key, String... placeholders) {
        if (target == null) {
            return;
        }
        // 注意：调用方可能传 null（例如无占位符时），
        // 此时可变参数数组本身为 null，必须兜底，否则读 length 会 NPE。
        String[] vars = placeholders == null ? new String[0] : placeholders;
        try {
            String raw = getConfig().getString("messages." + key);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            String prefix = getConfig().getString("messages.prefix", "");
            // 用统一的解析逻辑：插件占位符 + PAPI 变量（装了 PAPI 且目标是玩家时）
            org.bukkit.entity.Player player =
                    target instanceof org.bukkit.entity.Player p ? p : null;
            target.sendMessage(parsePlaceholders(prefix + raw, player, vars));
        } catch (Throwable t) {
            getLogger().warning("发送消息 " + key + " 失败: " + t.getMessage());
        }
    }

    /**
     * 解析文本里的占位符。
     *
     * <p>双向支持：</p>
     * <ol>
     *     <li>插件自身的 {@code %time% %player% %reason% ...}（由调用方传入）</li>
     *     <li>任意 PlaceholderAPI 变量 {@code %newbieprotect_time%}、
     *         {@code %vault_eco_balance%}、{@code %player_name%} 等</li>
     * </ol>
     *
     * <p>没装 PlaceholderAPI 时只处理插件自身占位符，不会报错。</p>
     *
     * @param text         含占位符的原始文本（已含 &amp; 颜色代码）
     * @param player       用于解析 PAPI 变量的玩家（可为 null）
     * @param placeholders 成对传入的插件占位符与值
     */
    public String parsePlaceholders(String text, org.bukkit.entity.Player player, String... placeholders) {
        if (text == null) {
            return "";
        }
        String result = text;
        // 1. 插件自身占位符
        String[] vars = placeholders == null ? new String[0] : placeholders;
        for (int i = 0; i + 1 < vars.length; i += 2) {
            result = result.replace(vars[i], vars[i + 1]);
        }
        // 2. PAPI 变量（装了才解析；用 try/catch 兜住版本差异）
        if (player != null && isPlaceholderApiAvailable()) {
            try {
                result = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, result);
            } catch (Throwable ignored) {
                // PAPI 出错不影响插件自身占位符
            }
        }
        return ChatColor.translateAlternateColorCodes('&', result);
    }

    /** 服务器是否装了 PlaceholderAPI。 */
    public boolean isPlaceholderApiAvailable() {
        try {
            return getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 批量解析文本列表（用于 tablist / 多行消息）。 */
    public List<String> parsePlaceholders(List<String> lines, org.bukkit.entity.Player player) {
        List<String> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            out.add(parsePlaceholders(line, player));
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* 访问器                                                              */
    /* ------------------------------------------------------------------ */

    public PlayerDataStore getDataStore() {
        return dataStore;
    }

    public ProtectionManager getManager() {
        return manager;
    }

    public boolean isShuttingDown() {
        return shuttingDown || !isEnabled();
    }
}
