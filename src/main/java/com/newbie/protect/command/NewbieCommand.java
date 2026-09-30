package com.newbie.protect.command;

import com.newbie.protect.NewbieProtect;
import com.newbie.protect.data.PlayerDataStore;
import com.newbie.protect.manager.ProtectionManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /newbie 命令。
 *
 * <p>玩家命令：</p>
 * <ul>
 *     <li>{@code /newbie}                    查看自己的剩余时间</li>
 *     <li>{@code /newbie off|on|toggle}      自己关闭 / 开启保护</li>
 * </ul>
 *
 * <p>管理员命令（newbieprotect.admin）：</p>
 * <ul>
 *     <li>{@code /newbie info <玩家>}          查看详情</li>
 *     <li>{@code /newbie set <玩家> <时间>}    设置剩余时间（如 1h30m / 45m / 90s）</li>
 *     <li>{@code /newbie add <玩家> <时间>}    增加剩余时间</li>
 *     <li>{@code /newbie take <玩家> <时间>}   减少剩余时间</li>
 *     <li>{@code /newbie pause <玩家>}         冻结计时（停止倒计时）</li>
 *     <li>{@code /newbie resume <玩家>}        恢复计时</li>
 *     <li>{@code /newbie open <玩家>}          强制开启保护</li>
 *     <li>{@code /newbie close <玩家>}         强制关闭保护（不再免伤）</li>
 *     <li>{@code /newbie clear <玩家>}         清空记录（重新变新人）</li>
 *     <li>{@code /newbie list [页码]}          列出所有记录</li>
 *     <li>{@code /newbie cleanup}              应急清理 Boss 条</li>
 *     <li>{@code /newbie reload}               重载配置</li>
 * </ul>
 */
public class NewbieCommand implements CommandExecutor, TabCompleter {

    /** 所有玩家都能用的子命令。 */
    private static final List<String> SUB_PLAYER = List.of("help", "on", "off", "toggle", "bar");
    /** 仅管理员可用的子命令。 */
    private static final List<String> SUB_ADMIN = List.of(
            "reload", "clear", "grant", "list", "info", "cleanup",
            "set", "add", "take", "pause", "resume", "open", "close",
            "bosscheck", "fixconfig");

    private static final int PAGE_SIZE = 8;

    private final NewbieProtect plugin;

    public NewbieCommand(NewbieProtect plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            return dispatch(sender, args);
        } catch (Throwable t) {
            plugin.getLogger().warning("执行 /newbie 出错: " + t);
            sender.sendMessage(color("&c命令执行出错，详情见控制台。"));
            return true;
        }
    }

    private boolean dispatch(CommandSender sender, String[] args) {
        if (args.length == 0) {
            showSelf(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "fixconfig": {
                // 手动触发配置补全（排查用）
                if (!requireAdmin(sender)) {
                    return true;
                }
                int n = plugin.forceConfigComplete();
                if (n > 0) {
                    plugin.sendConfigMessage(sender, "fixconfig-done",
                            "%count%", String.valueOf(n));
                } else {
                    plugin.sendConfigMessage(sender, "fixconfig-none", null);
                }
                return true;
            }

            case "bosscheck": {
                // 诊断：看附近实体有没有被识别为 Boss
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player self)) {
                    plugin.sendConfigMessage(sender, "player-only", null);
                    return true;
                }
                var bb = plugin.getManager().getBossBattle();
                sender.sendMessage(color("&8&m                                                  "));
                sender.sendMessage(color("&b[Boss 判定诊断]"));
                sender.sendMessage(color("  &7功能开关 &f" + bb.isEnabled()
                        + " &8| &7战斗中保护 &f" + bb.isProtectInBattle()
                        + " &8| &7战斗中暂停计时 &f" + bb.isPauseTimer()));
                sender.sendMessage(color("  &7" + bb.describeBosses()));
                sender.sendMessage(color("  &7附近 16 格内的实体判定："));
                int found = 0;
                for (var e : self.getNearbyEntities(16, 16, 16)) {
                    if (!(e instanceof org.bukkit.entity.LivingEntity le)) {
                        continue;
                    }
                    boolean isBoss = bb.isBoss(le);
                    if (isBoss) {
                        found++;
                    }
                    String cn = le.getCustomName();
                    sender.sendMessage(color("    " + (isBoss ? "&a✔" : "&8✘") + " &f"
                            + le.getType().name()
                            + (cn == null ? "" : " &7(名字: " + cn + "&7)")));
                }
                if (found == 0) {
                    sender.sendMessage(color("    &7没有识别到 Boss（上面的实体都算普通怪）"));
                }
                sender.sendMessage(color("  &7当前是否战斗中 &f" + bb.isInBattle(self)
                        + " &8| 脱战倒计时 &f" + bb.battleSecondsLeft(self) + " 秒"));
                sender.sendMessage(color("&8&m                                                  "));
                return true;
            }

            case "help":
                usage(sender);
                return true;

            /* ---------------- 玩家自助 ---------------- */
            case "on":
            case "off":
            case "toggle": {
                if (!(sender instanceof Player player)) {
                    plugin.sendConfigMessage(sender, "player-only", null);
                    return true;
                }
                boolean disabled;
                if (sub.equals("toggle")) {
                    disabled = !plugin.getManager().isSelfDisabled(player);
                } else {
                    disabled = sub.equals("off");
                }
                applySelfToggle(player, disabled);
                return true;
            }

            case "bar": {
                if (!(sender instanceof Player player)) {
                    plugin.sendConfigMessage(sender, "player-only", null);
                    return true;
                }
                applyBossBarToggle(player);
                return true;
            }

            /* ---------------- 管理员 ---------------- */
            case "reload":
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.reloadAll();
                plugin.sendConfigMessage(sender, "reload-done", null);
                return true;

            case "cleanup": {
                if (!requireAdmin(sender)) {
                    return true;
                }
                int before = plugin.getManager().bossBarCount();
                plugin.getManager().clearAllBossBars();
                plugin.getDataStore().save();
                plugin.sendConfigMessage(sender, "cleanup-done", "%count%", String.valueOf(before));
                return true;
            }

            case "list":
                if (!requireAdmin(sender)) {
                    return true;
                }
                showList(sender, args.length >= 2 ? args[1] : "1");
                return true;

            case "info":
                if (!requireAdmin(sender)) {
                    return true;
                }
                withTarget(sender, args, entry -> showInfo(sender, entry));
                return true;

            case "set":
                if (!requireAdmin(sender)) {
                    return true;
                }
                withTargetAndTime(sender, args, (entry, seconds) -> {
                    Player online = Bukkit.getPlayer(entry.uuid());
                    long total = plugin.getProtectDurationSeconds();
                    // 不做上限：可以给某个玩家单独设成比默认更长的时长
                    entry.usedSeconds(total - seconds);
                    entry.finished(seconds <= 0L);
                    plugin.getDataStore().markDirty();
                    if (online != null) {
                        plugin.getManager().refresh(online);
                    }
                    plugin.getDataStore().save();
                    plugin.sendConfigMessage(sender, "admin-set",
                            "%player%", entry.name(),
                            "%time%", ProtectionManager.format(seconds));
                });
                return true;

            case "add":
                if (!requireAdmin(sender)) {
                    return true;
                }
                withTargetAndTime(sender, args, (entry, seconds) -> {
                    long total = plugin.getProtectDurationSeconds();
                    long left = entry.finished() ? 0L : Math.max(0L, total - entry.usedSeconds());
                    long now = left + seconds;
                    // add 的上限要看「累加后的结果」，而不是增量本身
                    long limit = plugin.getAdminMaxSeconds();
                    if (limit > 0L && now > limit) {
                        plugin.sendConfigMessage(sender, "admin-limit-exceeded",
                                "%limit%", ProtectionManager.format(limit),
                                "%time%", ProtectionManager.format(now),
                                "%input%", args.length > 2 ? args[2] : "");
                        getLoggerSafe("管理员 " + sender.getName() + " 尝试给 " + entry.name()
                                + " 加 " + ProtectionManager.format(seconds)
                                + "（结果 " + ProtectionManager.format(now)
                                + "），超过上限 " + ProtectionManager.format(limit) + "，已拒绝。");
                        return;
                    }
                    entry.usedSeconds(total - now);
                    entry.finished(false);
                    plugin.getDataStore().markDirty();
                    Player online = Bukkit.getPlayer(entry.uuid());
                    if (online != null) {
                        plugin.getManager().refresh(online);
                    }
                    plugin.getDataStore().save();
                    plugin.sendConfigMessage(sender, "admin-add",
                            "%player%", entry.name(),
                            "%delta%", ProtectionManager.format(seconds),
                            "%time%", ProtectionManager.format(now));
                });
                return true;

            case "take":
                if (!requireAdmin(sender)) {
                    return true;
                }
                withTargetAndTime(sender, args, (entry, seconds) -> {
                    long total = plugin.getProtectDurationSeconds();
                    long left = entry.finished() ? 0L : Math.max(0L, total - entry.usedSeconds());
                    // 扣到 0 为止，不会变成负数
                    long now = Math.max(0L, left - seconds);
                    entry.usedSeconds(total - now);
                    entry.finished(now <= 0L);
                    plugin.getDataStore().markDirty();
                    Player online = Bukkit.getPlayer(entry.uuid());
                    if (online != null) {
                        plugin.getManager().refresh(online);
                    }
                    plugin.getDataStore().save();
                    plugin.sendConfigMessage(sender, "admin-take",
                            "%player%", entry.name(),
                            "%delta%", ProtectionManager.format(seconds),
                            "%time%", ProtectionManager.format(now));
                });
                return true;

            case "pause":
                if (!requireAdmin(sender)) {
                    return true;
                }
                withTarget(sender, args, entry -> {
                    entry.adminPaused(true);
                    plugin.getDataStore().markDirty();
                    Player online = Bukkit.getPlayer(entry.uuid());
                    if (online != null) {
                        plugin.getManager().refresh(online);
                    }
                    plugin.getDataStore().save();
                    plugin.sendConfigMessage(sender, "admin-paused-msg",
                            "%player%", entry.name());
                });
                return true;

            case "resume":
                if (!requireAdmin(sender)) {
                    return true;
                }
                withTarget(sender, args, entry -> {
                    entry.adminPaused(false);
                    plugin.getDataStore().markDirty();
                    Player online = Bukkit.getPlayer(entry.uuid());
                    if (online != null) {
                        plugin.getManager().refresh(online);
                    }
                    plugin.getDataStore().save();
                    plugin.sendConfigMessage(sender, "admin-resumed-msg",
                            "%player%", entry.name());
                });
                return true;

            case "open":
                if (!requireAdmin(sender)) {
                    return true;
                }
                withTarget(sender, args, entry -> {
                    entry.selfDisabled(false);
                    entry.adminPaused(false);
                    if (entry.finished()) {
                        entry.usedSeconds(0L);
                        entry.finished(false);
                        entry.firstJoin(System.currentTimeMillis());
                    }
                    plugin.getDataStore().markDirty();
                    Player online = Bukkit.getPlayer(entry.uuid());
                    if (online != null) {
                        plugin.getManager().refresh(online);
                    }
                    plugin.getDataStore().save();
                    long total = plugin.getProtectDurationSeconds();
                    long left = Math.max(0L, total - entry.usedSeconds());
                    plugin.sendConfigMessage(sender, "admin-opened",
                            "%player%", entry.name(),
                            "%time%", ProtectionManager.format(left));
                });
                return true;

            case "close":
                if (!requireAdmin(sender)) {
                    return true;
                }
                withTarget(sender, args, entry -> {
                    entry.selfDisabled(true);
                    plugin.getDataStore().markDirty();
                    Player online = Bukkit.getPlayer(entry.uuid());
                    if (online != null) {
                        plugin.getManager().refresh(online);
                    }
                    plugin.getDataStore().save();
                    plugin.sendConfigMessage(sender, "admin-closed",
                            "%player%", entry.name());
                });
                return true;

            case "clear":
                if (!requireAdmin(sender)) {
                    return true;
                }
                withTarget(sender, args, entry -> {
                    plugin.getDataStore().remove(entry.uuid());
                    plugin.getDataStore().save();
                    plugin.sendConfigMessage(sender, "cleared", "%player%", entry.name());
                });
                return true;

            case "grant":
                if (!requireAdmin(sender)) {
                    return true;
                }
                withTarget(sender, args, entry -> {
                    // grant 重置为「默认时长」，同样受管理员上限约束
                    long grantSeconds = plugin.clampAdminSeconds(plugin.getProtectDurationSeconds());
                    long total = plugin.getProtectDurationSeconds();
                    entry.usedSeconds(total - grantSeconds);
                    entry.finished(false);
                    entry.selfDisabled(false);
                    entry.adminPaused(false);
                    entry.firstJoin(System.currentTimeMillis());
                    plugin.getDataStore().save();
                    plugin.sendConfigMessage(sender, "granted",
                            "%player%", entry.name(),
                            "%time%", ProtectionManager.format(grantSeconds));
                    Player online = Bukkit.getPlayer(entry.uuid());
                    if (online != null) {
                        plugin.getManager().removeBossBar(entry.uuid());
                        plugin.getManager().refresh(online);
                    }
                });
                return true;

            default: {
                // 当作玩家名查询
                if (!requireAdmin(sender)) {
                    return true;
                }
                PlayerDataStore.Entry entry = find(args[0]);
                if (entry == null) {
                    plugin.sendConfigMessage(sender, "player-not-found", null);
                    return true;
                }
                long total = plugin.getProtectDurationSeconds();
                long left = entry.finished() ? 0L : Math.max(0L, total - entry.usedSeconds());
                plugin.sendConfigMessage(sender, "query-other",
                        "%player%", entry.name(),
                        "%time%", ProtectionManager.format(left));
                return true;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 子功能                                                              */
    /* ------------------------------------------------------------------ */

    private void showSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.sendConfigMessage(sender, "player-only", null);
            return;
        }
        long left = plugin.getManager().remainingSeconds(player);
        if (left <= 0L) {
            plugin.sendConfigMessage(sender, "expired", null);
        } else if (plugin.getManager().isSelfDisabled(player)) {
            plugin.sendConfigMessage(sender, "query-self-disabled",
                    "%time%", ProtectionManager.format(left),
                    "%timing%", plugin.getManager().timingNote());
        } else if (plugin.getManager().isPaused(player)) {
            plugin.sendConfigMessage(sender, "query-self-paused",
                    "%time%", ProtectionManager.format(left),
                    "%reason%", plugin.getManager().pauseReason(player));
        } else {
            plugin.sendConfigMessage(sender, "query-self",
                    "%time%", ProtectionManager.format(left));
        }
    }

    /** 展示单个玩家详情（卡片式）。 */
    private void showInfo(CommandSender sender, PlayerDataStore.Entry entry) {
        long total = plugin.getProtectDurationSeconds();
        long left = entry.finished() ? 0L : Math.max(0L, total - entry.usedSeconds());
        long used = entry.usedSeconds();
        double percent = total <= 0 ? 0 : (left * 100.0 / total);
        Player target = Bukkit.getPlayer(entry.uuid());

        String prefix = plugin.getConfig().getString("messages.prefix", "");
        sender.sendMessage(color("&8&m                                                  "));
        send(sender, target, prefix + "&f玩家 &b" + entry.name());
        send(sender, target, "  &7剩余时间   &a" + ProtectionManager.format(left)
                + " &8(" + String.format("%.1f", percent) + "%)");
        send(sender, target, "  &7默认时长   &f" + ProtectionManager.format(total));
        if (used < 0L) {
            // 负值 = 管理员额外赠送的时间
            send(sender, target, "  &7额外赠送   &d+" + ProtectionManager.format(-used));
            send(sender, target, "  &7已消耗     &f" + ProtectionManager.format(0L));
        } else {
            send(sender, target, "  &7已消耗     &f" + ProtectionManager.format(used));
        }
        send(sender, target, "  &7状态       " + stateText(entry));
        long first = entry.firstJoin();
        send(sender, target, "  &7首次进服   &f"
                + (first > 0L
                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(first))
                : "未知"));
        send(sender, target, "  &7进度       " + progressBar(left, total));
        send(sender, target, "  &8(&7以上内容支持 PlaceholderAPI 变量&8)");
        sender.sendMessage(color("&8&m                                                  "));
    }

    /** 统一发送：走插件的占位符解析（插件自身 + PAPI 变量）。 */
    private void send(CommandSender sender, Player ctx, String text) {
        sender.sendMessage(plugin.parsePlaceholders(text, ctx));
    }

    private String stateText(PlayerDataStore.Entry entry) {
        if (entry.finished()) {
            return "&8已结束";
        }
        if (entry.adminPaused()) {
            return "&e已冻结（管理员暂停计时）";
        }
        if (entry.selfDisabled()) {
            return "&c已关闭（玩家自己关的）";
        }
        Player online = Bukkit.getPlayer(entry.uuid());
        if (online != null && plugin.getManager().isPaused(online)) {
            return "&e暂停中（" + plugin.getManager().pauseReason(online) + "&e）";
        }
        if (online == null) {
            return "&b保护中 &7(离线，计时暂停)";
        }
        return "&a保护中";
    }

    /** 文字进度条。 */
    private static String progressBar(long left, long total) {
        if (total <= 0) {
            return "&8----------------";
        }
        int bars = 12;
        int filled = (int) Math.round(bars * (left / (double) total));
        filled = Math.max(0, Math.min(bars, filled));
        StringBuilder sb = new StringBuilder("&a");
        for (int i = 0; i < bars; i++) {
            if (i == filled) {
                sb.append("&7");
            }
            sb.append('|');
        }
        return sb.toString();
    }

    /** 分页列出所有记录。 */
    private void showList(CommandSender sender, String pageArg) {
        long total = plugin.getProtectDurationSeconds();
        List<PlayerDataStore.Entry> all = new ArrayList<>(plugin.getDataStore().all());
        // 按剩余时间从少到多排（快到期 / 已结束的在前，方便管理员关注）
        all.sort((a, b) -> {
            long ra = a.finished() ? 0L : Math.max(0L, total - a.usedSeconds());
            long rb = b.finished() ? 0L : Math.max(0L, total - b.usedSeconds());
            return Long.compare(ra, rb);
        });

        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) PAGE_SIZE));
        int page;
        try {
            page = Math.max(1, Math.min(pages, Integer.parseInt(pageArg)));
        } catch (NumberFormatException e) {
            page = 1;
        }
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);

        String prefix = plugin.getConfig().getString("messages.prefix", "");
        sender.sendMessage(color("&8&m                                                  "));
        sender.sendMessage(color(prefix + "&f新人保护记录 &7(共 &b" + all.size() + " &7人) "
                + "&8第 &f" + page + "&8/&f" + pages + " &8页"));
        if (all.isEmpty()) {
            sender.sendMessage(color("  &7暂无记录。"));
        }
        for (int i = from; i < to; i++) {
            PlayerDataStore.Entry e = all.get(i);
            long left = e.finished() ? 0L : Math.max(0L, total - e.usedSeconds());
            String state = e.finished() ? "&8结束"
                    : e.adminPaused() ? "&e冻结"
                    : e.selfDisabled() ? "&c关闭"
                    : "&a保护";
            // 按玩家上下文解析 PAPI 变量（离线玩家也能用静态变量）
            send(sender, Bukkit.getPlayer(e.uuid()),
                    "  &8" + (i + 1) + ". &f" + pad(e.name(), 16)
                            + " " + state + " &7剩余 &f" + ProtectionManager.format(left));
        }
        sender.sendMessage(color("&8&m                                                  "));
        if (page < pages) {
            sender.sendMessage(color("  &7下一页: &f/newbie list " + (page + 1)));
        }
    }

    private static String pad(String text, int len) {
        if (text == null) {
            text = "";
        }
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < len) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 参数解析辅助                                                        */
    /* ------------------------------------------------------------------ */

    private interface EntryAction {
        void run(PlayerDataStore.Entry entry);
    }

    private interface EntryTimeAction {
        void run(PlayerDataStore.Entry entry, long seconds);
    }

    private void withTarget(CommandSender sender, String[] args, EntryAction action) {
        if (args.length < 2) {
            plugin.sendConfigMessage(sender, "need-player", null);
            return;
        }
        PlayerDataStore.Entry entry = find(args[1]);
        if (entry == null) {
            plugin.sendConfigMessage(sender, "player-not-found", null);
            return;
        }
        action.run(entry);
    }

    private void withTargetAndTime(CommandSender sender, String[] args, EntryTimeAction action) {
        if (args.length < 3) {
            plugin.sendConfigMessage(sender, "need-player-time", null);
            return;
        }
        PlayerDataStore.Entry entry = find(args[1]);
        if (entry == null) {
            plugin.sendConfigMessage(sender, "player-not-found", null);
            return;
        }
        Long seconds = parseTime(args[2]);
        if (seconds == null) {
            plugin.sendConfigMessage(sender, "bad-time", "%input%", args[2]);
            return;
        }
        // 管理员操作上限（protection.admin-limit）
        long limit = plugin.getAdminMaxSeconds();
        if (limit > 0L && seconds > limit) {
            plugin.sendConfigMessage(sender, "admin-limit-exceeded",
                    "%limit%", ProtectionManager.format(limit),
                    "%time%", ProtectionManager.format(seconds),
                    "%input%", args[2]);
            getLoggerSafe("管理员 " + sender.getName() + " 尝试给 " + entry.name()
                    + " 设置 " + ProtectionManager.format(seconds)
                    + "，超过上限 " + ProtectionManager.format(limit) + "，已拒绝。");
            return;
        }
        action.run(entry, seconds);
    }

    /** 记录管理员越权尝试（只写控制台，不打断流程）。 */
    private void getLoggerSafe(String message) {
        try {
            plugin.getLogger().info(message);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 解析时间字符串。
     *
     * <p>支持 {@code 1h30m}、{@code 45m}、{@code 90s}、{@code 2h}、纯数字（秒）。
     * 也兼容中文单位：{@code 1时30分}、{@code 45分}。</p>
     *
     * @return 秒数；无法解析返回 null
     */
    public static Long parseTime(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        // 纯数字 = 秒
        if (s.matches("\\d+")) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        long total = 0L;
        StringBuilder num = new StringBuilder();
        boolean matchedAny = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
                continue;
            }
            long value;
            try {
                value = Long.parseLong(num.toString());
            } catch (NumberFormatException e) {
                return null;
            }
            num.setLength(0);
            char next = (i + 1 < s.length()) ? s.charAt(i + 1) : 0;
            long mult;
            switch (c) {
                case 'd':
                    mult = 86_400L;
                    break;
                case 'h':
                    mult = 3_600L;
                    break;
                case 'm':
                    mult = 60L;
                    break;
                case 's':
                    mult = 1L;
                    break;
                case '天':
                    mult = 86_400L;
                    break;
                case '时':
                case '小':
                    mult = 3_600L;
                    if (c == '小' && next == '时') {
                        i++;
                    }
                    break;
                case '分':
                    mult = 60L;
                    break;
                case '秒':
                    mult = 1L;
                    break;
                default:
                    return null;
            }
            total += value * mult;
            matchedAny = true;
        }
        // 结尾还有剩余数字（没有单位）→ 当作秒
        if (num.length() > 0) {
            try {
                total += Long.parseLong(num.toString());
                matchedAny = true;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return matchedAny ? total : null;
    }

    private PlayerDataStore.Entry find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            PlayerDataStore.Entry e = plugin.getDataStore().get(online.getUniqueId());
            if (e != null) {
                return e;
            }
            return plugin.getDataStore().getOrCreate(online.getUniqueId(), online.getName());
        }
        return plugin.getDataStore().findByName(name);
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("newbieprotect.admin")) {
            return true;
        }
        plugin.sendConfigMessage(sender, "no-permission", null);
        return false;
    }

    /** 玩家切换 Boss 条显示。 */
    private void applyBossBarToggle(Player player) {
        if (!plugin.isBossBarToggleEnabled()) {
            plugin.sendConfigMessage(player, "bar-toggle-disabled", null);
            return;
        }
        boolean nowHidden = !plugin.isBossBarHidden(player.getUniqueId());
        if (!plugin.setBossBarHidden(player, nowHidden)) {
            plugin.sendConfigMessage(player, "bar-toggle-failed", null);
            return;
        }
        plugin.getDataStore().save();
        plugin.sendConfigMessage(player, nowHidden ? "bar-hidden" : "bar-shown", null);
    }

    /**
     * 执行玩家的自助开关。
     */
    private void applySelfToggle(Player player, boolean disabled) {
        if (!plugin.isSelfToggleEnabled()) {
            plugin.sendConfigMessage(player, "self-toggle-disabled", null);
            return;
        }
        long left = plugin.getManager().remainingSeconds(player);
        if (left <= 0L) {
            plugin.sendConfigMessage(player, "self-toggle-expired", null);
            return;
        }
        if (!plugin.getManager().setSelfDisabled(player, disabled)) {
            plugin.sendConfigMessage(player, disabled ? "self-already-off" : "self-already-on", null);
            return;
        }
        plugin.getDataStore().save();

        if (disabled) {
            plugin.sendConfigMessage(player, "self-off",
                    "%time%", ProtectionManager.format(left),
                    "%timing%", plugin.getManager().timingNote());
        } else {
            plugin.sendConfigMessage(player, "self-on",
                    "%time%", ProtectionManager.format(plugin.getManager().remainingSeconds(player)));
        }
    }

    /** 漂亮的 help 界面。 */
    private void usage(CommandSender sender) {
        boolean admin = sender.hasPermission("newbieprotect.admin");
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String line = "&8&m                                                  ";

        sender.sendMessage(color(line));
        sender.sendMessage(color("  " + prefix + "&b&l新人保护 &7v" + plugin.getDescription().getVersion()));
        sender.sendMessage(color("  &7新人入服后一段时间内免受怪物与玩家伤害"));
        sender.sendMessage(color(""));
        sender.sendMessage(color("  &f&l玩家命令"));
        sender.sendMessage(color("  &b/newbie &8» &7查看自己的剩余保护时间"));
        sender.sendMessage(color("  &b/newbie off &8» &7关闭保护 &8(&7不再免伤，时间照常计算&8)"));
        sender.sendMessage(color("  &b/newbie on &8» &7重新开启保护"));
        sender.sendMessage(color("  &b/newbie toggle &8» &7在开 / 关之间切换"));
        sender.sendMessage(color("  &b/newbie bar &8» &7开关屏幕上的 Boss 条 &8(&7嫌挡屏幕可关掉&8)"));

        if (admin) {
            sender.sendMessage(color(""));
            sender.sendMessage(color("  &c&l管理员命令"));
            sender.sendMessage(color("  &b/newbie info &f<玩家> &8» &7查看详情"));
            sender.sendMessage(color("  &b/newbie set &f<玩家> <时间> &8» &7设置剩余时间"));
            sender.sendMessage(color("  &b/newbie add &f<玩家> <时间> &8» &7增加时间"));
            sender.sendMessage(color("  &b/newbie take &f<玩家> <时间> &8» &7减少时间"));
            sender.sendMessage(color("  &b/newbie pause &f<玩家> &8» &7冻结计时 &8(&7停表&8)"));
            sender.sendMessage(color("  &b/newbie resume &f<玩家> &8» &7恢复计时"));
            sender.sendMessage(color("  &b/newbie open &f<玩家> &8» &7强制开启保护"));
            sender.sendMessage(color("  &b/newbie close &f<玩家> &8» &7强制关闭保护"));
            sender.sendMessage(color("  &b/newbie clear &f<玩家> &8» &7清空记录 &8(&7重新变新人&8)"));
            sender.sendMessage(color("  &b/newbie grant &f<玩家> &8» &7重置为满时长"));
            sender.sendMessage(color("  &b/newbie list &f[页码] &8» &7列出所有记录"));
            sender.sendMessage(color("  &b/newbie bosscheck &8» &7诊断附近实体是否被认作 Boss"));
            sender.sendMessage(color("  &b/newbie fixconfig &8» &7手动补全缺失的配置项"));
            sender.sendMessage(color("  &b/newbie cleanup &8» &7应急清理 Boss 条"));
            sender.sendMessage(color("  &b/newbie reload &8» &7重载配置"));
            sender.sendMessage(color(""));
            sender.sendMessage(color("  &7时间写法：&f1h30m &7/ &f45m &7/ &f90s &7/ &f2d &7/ &f1时30分"));
        } else {
            sender.sendMessage(color(""));
            sender.sendMessage(color("  &8（管理员命令需要 &7newbieprotect.admin &8权限）"));
        }

        sender.sendMessage(color("  &7别名：&f/nb &7/ &f/newb"));
        sender.sendMessage(color(line));
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : SUB_PLAYER) {
                if (s.startsWith(prefix)) {
                    result.add(s);
                }
            }
            if (sender.hasPermission("newbieprotect.admin")) {
                for (String s : SUB_ADMIN) {
                    if (s.startsWith(prefix)) {
                        result.add(s);
                    }
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        result.add(p.getName());
                    }
                }
            }
            return result;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("newbieprotect.admin")) {
            return result;
        }

        if (args.length == 2) {
            if (SUB_ADMIN.contains(sub) && !sub.equals("reload")
                    && !sub.equals("cleanup") && !sub.equals("list")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        result.add(p.getName());
                    }
                }
            }
            return result;
        }

        // 时间参数补全示例
        if (args.length == 3 && (sub.equals("set") || sub.equals("add") || sub.equals("take"))) {
            for (String sample : new String[]{"30m", "1h", "2h", "1h30m", "10s"}) {
                if (sample.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    result.add(sample);
                }
            }
        }
        return result;
    }
}
