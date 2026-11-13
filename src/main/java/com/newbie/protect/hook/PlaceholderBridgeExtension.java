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
    public String getRequiredPlugin() {
        return plugin.getName();
    }

    @Override
    public boolean canRegister() {
        return !plugin.isShuttingDown();
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

        ProtectionManager.ProtectionState state = isOnline(online) ? mgr.state(online)
                : mgr.state(player.getUniqueId());
        long used = entry == null ? 0L : entry.usedSeconds();
        long left = state.remainingSeconds();
        boolean finished = entry != null && (entry.finished() || "finished".equals(state.statusKey()));
        boolean selfDisabled = entry != null && entry.selfDisabled();
        boolean adminPaused = entry != null && entry.adminPaused();
        boolean protectedNow = !plugin.isShuttingDown() && state.protectedNow();

        switch (params == null ? "" : params.toLowerCase(Locale.ROOT)) {
            case "time":
            case "left":
            case "remaining":
                return plugin.formatDuration(left);
            case "seconds":
            case "left_seconds":
            case "remaining_seconds":
                return String.valueOf(left);
            case "minutes":
            case "left_minutes":
                return String.valueOf(left / 60L + (left % 60L == 0L ? 0L : 1L));
            case "hours":
                return String.valueOf(left / 3600L);
            case "used":
            case "used_time":
                return plugin.formatDuration(used);
            case "used_seconds":
                return String.valueOf(used);
            case "total":
            case "total_time":
                return plugin.formatDuration(total);
            case "total_seconds":
                return String.valueOf(total);
            case "percent":
                return total <= 0 ? "0.0" : String.format(Locale.ROOT, "%.1f", left * 100.0 / total);
            case "percent_int":
                return total <= 0 ? "0" : String.valueOf(Math.round(left * 100.0 / total));
            case "bar":
                return "&a" + barSymbol(left, total);
            case "bar_symbol":
                return barSymbol(left, total);
            case "status":
                String statusKey = plugin.isShuttingDown() ? "inactive" : state.statusKey();
                return plugin.langText("status." + statusKey, statusKey, "%reason%", state.pauseReason());
            case "protected":
            case "is_protected":
                return String.valueOf(protectedNow);
            case "paused":
            case "is_paused":
                return String.valueOf(state.paused());
            case "pause_reason":
                return state.pauseReason();
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
                return String.valueOf(!plugin.isShuttingDown() && plugin.isProtectEnabled());
            default:
                return null;
        }
    }

    private static boolean isOnline(Player player) {
        return player != null && player.isOnline();
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
