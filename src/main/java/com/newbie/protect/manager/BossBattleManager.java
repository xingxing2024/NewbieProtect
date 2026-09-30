package com.newbie.protect.manager;

import com.newbie.protect.NewbieProtect;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 打 Boss 无效化。
 *
 * <p>防止新人靠保护摸 Boss 偷奖励：一旦新人**自己攻击**了 Boss：</p>
 * <ol>
 *     <li>进入「Boss 战斗」状态 —— 本次战斗中保护失效（按配置）</li>
 *     <li>战斗中暂停计时（按配置，默认暂停，不浪费玩家时间）</li>
 *     <li>立刻发消息告诉玩家（防止他以为插件坏了）</li>
 *     <li>脱战后（{@code boss-battle.battle-timeout-seconds} 秒没再打 Boss）自动恢复</li>
 * </ol>
 *
 * <p>判定「哪些算 Boss」：内置名单 + 自定义名单 + Boss 血条实体 + 名字含 boss 的实体。</p>
 */
public class BossBattleManager {

    /** 玩家 → 该玩家最后一次攻击 Boss 的时间戳。 */
    private final Map<UUID, Long> lastBossHit = new ConcurrentHashMap<>();
    /** 玩家 → 是否已经发过「进入战斗」提示（避免刷屏）。 */
    private final Set<UUID> notified = ConcurrentHashMap.newKeySet();

    private final NewbieProtect plugin;

    public BossBattleManager(NewbieProtect plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------------ */
    /* 判定                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 该实体是否算 Boss。
     */
    public boolean isBoss(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        EntityType type = living.getType();

        // 1) 内置 + 自定义名单
        if (builtinBosses().contains(type)) {
            return true;
        }
        if (customBosses().contains(type)) {
            return true;
        }

        // 2) 带 Boss 血条的实体（末影龙 / 凋灵等）
        if (plugin.getConfig().getBoolean("boss-battle.count-bossbar-entities", true)) {
            try {
                java.util.Iterator<org.bukkit.boss.KeyedBossBar> it =
                        living.getServer().getBossBars();
                while (it.hasNext()) {
                    org.bukkit.boss.KeyedBossBar bar = it.next();
                    if (bar.getPlayers().contains(living)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 忽略，继续其他判断
            }
        }

        // 3) 自定义名称里含 boss 的实体（很多服这么标记 Boss 怪）
        if (plugin.getConfig().getBoolean("boss-battle.count-named-bosses", true)) {
            try {
                String name = living.getCustomName();
                if (name != null) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.contains("boss") || name.contains("BOSS") || name.contains("首领")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        return false;
    }

    /** 内置默认 Boss 名单（配置里没写时用这个，保证功能开箱即用）。 */
    private static final List<String> DEFAULT_BOSSES = List.of(
            "ENDER_DRAGON", "WITHER", "WARDEN", "ELDER_GUARDIAN", "RAVAGER", "PIGLIN_BRUTE"
    );

    /** 列表型配置读不到时返回 {@code fallback}，避免「配置缺项 = 功能静默失效」。 */
    private List<String> stringListOr(String path, List<String> fallback) {
        try {
            List<String> raw = plugin.getConfig().getStringList(path);
            // 配置里显式写成 [] 表示「不要」；键不存在才用兜底
            if (!plugin.getConfig().isSet(path)) {
                return fallback;
            }
            return raw;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private Set<EntityType> builtinBosses() {
        Set<EntityType> set = ConcurrentHashMap.newKeySet();
        for (String raw : stringListOr("boss-battle.builtin-bosses", DEFAULT_BOSSES)) {
            EntityType t = parseType(raw);
            if (t != null) {
                set.add(t);
            }
        }
        return set;
    }

    private Set<EntityType> customBosses() {
        Set<EntityType> set = ConcurrentHashMap.newKeySet();
        for (String raw : stringListOr("boss-battle.custom-bosses", List.of())) {
            EntityType t = parseType(raw);
            if (t != null) {
                set.add(t);
            }
        }
        return set;
    }

    private static EntityType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 战斗状态                                                            */
    /* ------------------------------------------------------------------ */

    /** 是否启用。 */
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("boss-battle.enabled", true);
    }

    /** 记录「该玩家打了 Boss」，进入 / 刷新战斗状态。 */
    public void markBossHit(Player player, Entity boss) {
        if (player == null || !isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long prev = lastBossHit.put(player.getUniqueId(), now);
        boolean first = prev == null || !isInBattle(player);

        if (first) {
            notified.add(player.getUniqueId());
            String bossName = bossName(boss);
            plugin.sendConfigMessage(player, "boss-battle-enter",
                    "%boss%", bossName,
                    "%timing%", isPauseTimer()
                            ? plugin.langText("boss-battle-timing-paused", "保护计时已暂停")
                            : plugin.langText("boss-battle-timing-continue", "保护计时继续"));
            plugin.getLogger().info("玩家 " + player.getName()
                    + " 攻击了 Boss(" + bossName + ")，进入 Boss 战斗状态，保护暂时失效。");
        }
    }

    /** 该玩家此刻是否处于 Boss 战斗中。 */
    public boolean isInBattle(Player player) {
        if (player == null || !isEnabled()) {
            return false;
        }
        Long last = lastBossHit.get(player.getUniqueId());
        if (last == null) {
            return false;
        }
        long timeout = Math.max(1L, plugin.getConfig()
                .getLong("boss-battle.battle-timeout-seconds", 15L)) * 1000L;
        if (System.currentTimeMillis() - last > timeout) {
            // 超时，清掉记录
            lastBossHit.remove(player.getUniqueId());
            notified.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    /** 战斗中是否暂停计时。 */
    public boolean isPauseTimer() {
        return plugin.getConfig().getBoolean("boss-battle.pause-timer-in-battle", true);
    }

    /** 战斗中是否仍然免伤。 */
    public boolean isProtectInBattle() {
        return plugin.getConfig().getBoolean("boss-battle.protect-in-battle", false);
    }

    /** 剩余战斗秒数（用于 Boss 条展示）。 */
    public long battleSecondsLeft(Player player) {
        if (player == null) {
            return 0L;
        }
        Long last = lastBossHit.get(player.getUniqueId());
        if (last == null) {
            return 0L;
        }
        long timeout = Math.max(1L, plugin.getConfig()
                .getLong("boss-battle.battle-timeout-seconds", 15L)) * 1000L;
        long left = timeout - (System.currentTimeMillis() - last);
        return Math.max(0L, left / 1000L);
    }

    /** 诊断用：当前生效的 Boss 名单。 */
    public String describeBosses() {
        StringBuilder sb = new StringBuilder();
        sb.append("内置/自定义名单: ");
        Set<EntityType> all = ConcurrentHashMap.newKeySet();
        all.addAll(builtinBosses());
        all.addAll(customBosses());
        if (all.isEmpty()) {
            sb.append("(空)");
        } else {
            all.forEach(t -> sb.append(t.name()).append(' '));
        }
        sb.append(" | 认血条=").append(plugin.getConfig()
                .getBoolean("boss-battle.count-bossbar-entities", true));
        sb.append(" | 认名字=").append(plugin.getConfig()
                .getBoolean("boss-battle.count-named-bosses", true));
        return sb.toString();
    }

    /** 玩家退服 / 关闭时清理。 */
    public void clear(UUID uuid) {        if (uuid != null) {
            lastBossHit.remove(uuid);
            notified.remove(uuid);
        }
    }

    /** 全部清理（关服 / reload）。 */
    public void clearAll() {
        lastBossHit.clear();
        notified.clear();
    }

    private static String bossName(Entity boss) {
        try {
            String custom = boss instanceof LivingEntity le ? le.getCustomName() : null;
            if (custom != null && !custom.isBlank()) {
                return custom;
            }
            return boss.getType().name();
        } catch (Throwable ignored) {
            return "Boss";
        }
    }
}
