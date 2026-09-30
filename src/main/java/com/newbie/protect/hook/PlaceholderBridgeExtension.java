package com.newbie.protect.hook;

import com.newbie.protect.NewbieProtect;
import com.newbie.protect.data.PlayerDataStore;
import com.newbie.protect.manager.ProtectionManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * PlaceholderAPI 变量扩展。
 *
 * <p>服务器没装 PAPI 时，主类会**先判断再加载**本类，
 * 所以不会抛 {@code NoClassDefFoundError}。</p>
 *
 * <p>变量清单见 config.yml 顶部的「PlaceholderAPI 变量速查表」。</p>
 */
public class PlaceholderBridgeExtension extends PlaceholderExpansion {

    private final NewbieProtect plugin;

    public PlaceholderBridgeExtension(NewbieProtect plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "newbieprotect";
    }

    @Override
    public String getAuthor() {
        return "NewbieProtect";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** 不随 /papi reload 掉线。 */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || plugin.getManager() == null) {
            return "";
        }
        ProtectionManager mgr = plugin.getManager();
        Player online = player.getPlayer();
        PlayerDataStore.Entry entry = plugin.getDataStore().get(player.getUniqueId());
        long total = plugin.getProtectDurationSeconds();

        long used = entry == null ? 0L : entry.usedSeconds();
        boolean finished = entry != null && entry.finished();
        long left = finished ? 0L : Math.max(0L, total - used);

        boolean isOnline = online != null && online.isOnline();
        boolean paused = isOnline && mgr.isPaused(online);
        boolean selfDisabled = entry != null && entry.selfDisabled();
        boolean adminPaused = entry != null && entry.adminPaused();
        boolean protectedNow = isOnline && mgr.isProtected(online);

        switch (params == null ? "" : params.toLowerCase(Locale.ROOT)) {
            case "time":
            case "left":
            case "remaining":
                return ProtectionManager.format(left);
            case "seconds":
            case "left_seconds":
            case "remaining_seconds":
                return String.valueOf(left);
            case "minutes":
            case "left_minutes":
                return String.valueOf((left + 59L) / 60L);
            case "hours":
                return String.valueOf(left / 3600L);
            case "used":
            case "used_time":
                return ProtectionManager.format(used);
            case "used_seconds":
                return String.valueOf(used);
            case "total":
            case "total_time":
                return ProtectionManager.format(total);
            case "total_seconds":
                return String.valueOf(total);
            case "percent":
                return total <= 0 ? "0.0" : String.format("%.1f", left * 100.0 / total);
            case "percent_int":
                return total <= 0 ? "0" : String.valueOf(Math.round(left * 100.0 / total));
            case "bar":
                return "&a" + barSymbol(left, total);
            case "bar_symbol":
                return barSymbol(left, total);
            case "status":
                return statusText(entry, isOnline, paused, selfDisabled, adminPaused, finished);
            case "protected":
            case "is_protected":
                return String.valueOf(protectedNow);
            case "paused":
            case "is_paused":
                return String.valueOf(paused || adminPaused);
            case "pause_reason":
                return isOnline ? mgr.pauseReason(online) : "";
            case "self_disabled":
                return String.valueOf(selfDisabled);
            case "admin_paused":
                return String.valueOf(adminPaused);
            case "finished":
            case "is_finished":
                return String.valueOf(finished);
            case "first_join":
                if (entry == null || entry.firstJoin() <= 0L) {
                    return "";
                }
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(entry.firstJoin()));
            case "first_join_ago":
                if (entry == null || entry.firstJoin() <= 0L) {
                    return "";
                }
                return String.valueOf(Math.max(0L,
                        (System.currentTimeMillis() - entry.firstJoin()) / 86_400_000L));
            case "bossbar":
            case "bossbar_enabled":
                return String.valueOf(plugin.isBossBarVisibleTo(player.getUniqueId()));
            case "enabled":
            case "is_enabled":
                return String.valueOf(plugin.isProtectEnabled());
            default:
                return null;
        }
    }

    private static String statusText(PlayerDataStore.Entry entry, boolean online, boolean paused,
                                     boolean selfDisabled, boolean adminPaused, boolean finished) {
        if (entry == null) {
            return online ? "新人" : "未知";
        }
        if (finished) {
            return "已结束";
        }
        if (adminPaused) {
            return "已冻结";
        }
        if (selfDisabled) {
            return "已关闭";
        }
        if (paused) {
            return "暂停中";
        }
        return online ? "保护中" : "保护中(离线)";
    }

    private static String barSymbol(long left, long total) {
        int bars = 12;
        int filled = total <= 0 ? 0 : (int) Math.round(bars * (left / (double) total));
        filled = Math.max(0, Math.min(bars, filled));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bars; i++) {
            sb.append(i < filled ? '|' : '-');
        }
        return sb.toString();
    }
}
