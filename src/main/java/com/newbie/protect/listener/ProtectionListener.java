package com.newbie.protect.listener;

import com.newbie.protect.NewbieProtect;
import com.newbie.protect.manager.ProtectionManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 伤害拦截：保护期内的新人免受怪物伤害与玩家 PVP。
 */
public class ProtectionListener implements Listener {

    private final NewbieProtect plugin;

    public ProtectionListener(NewbieProtect plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------------ */
    /* 进服 / 退服                                                         */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 延后 1 tick 处理，避免与其他插件抢时序；
        // Folia 下对玩家的操作必须在实体所属区域线程执行。
        com.newbie.protect.util.SchedulerUtils.runEntity(plugin, player,
                () -> com.newbie.protect.util.SchedulerUtils.runEntityLater(plugin, player,
                        () -> plugin.getManager().onJoin(player), 1L));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.getManager().onQuit(event.getPlayer());
        // 立即落盘：即使之后服务器被强杀，该玩家的已消耗时间也不会丢
        plugin.saveOnQuit();
    }

    /* ------------------------------------------------------------------ */
    /* 伤害拦截                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 实体伤害总入口。
     *
     * <p>优先级设为 HIGHEST，保证在其它插件（如领地/反作弊）取消之后做最终判断；
     * 只要事件已被取消就不重复处理。</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!plugin.getManager().isProtected(victim)) {
            return;
        }

        EntityDamageEvent.DamageCause cause = event.getCause();

        // 1) 玩家 PVP（含玩家射出的投射物）
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            Entity source = resolveSource(damager);

            if (plugin.getConfig().getBoolean("protection.block-player-damage", true)) {
                if (damager instanceof Player || source instanceof Player) {
                    event.setCancelled(true);
                    return;
                }
            }

            // 2) 投射物伤害（箭 / 三叉戟 / 雪球等）
            if (plugin.getConfig().getBoolean("protection.block-projectile-damage", true)) {
                if (damager instanceof Projectile) {
                    // 非玩家射出的投射物：若发射者是怪物也算怪物伤害
                    if (source instanceof LivingEntity && !(source instanceof Player)) {
                        if (plugin.getConfig().getBoolean("protection.block-mob-damage", true)) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                    // 发射者已消失（纯环境投射物）也拦掉，保护期内更安全
                    if (source == null && plugin.getConfig().getBoolean("protection.block-mob-damage", true)) {
                        event.setCancelled(true);
                    }
                    return;
                }
            }

            // 3) 近战怪物伤害
            if (plugin.getConfig().getBoolean("protection.block-mob-damage", true)) {
                if (damager instanceof LivingEntity && !(damager instanceof Player)) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }

        // 4) 非实体来源的伤害（摔落 / 火焰 / 中毒等）默认不拦
        //    如需扩展，可在此按 cause 判断
        if (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.PROJECTILE) {
            // 理论上上面已处理，这里兜底
            if (plugin.getConfig().getBoolean("protection.block-mob-damage", true)) {
                event.setCancelled(true);
            }
        }
    }

    /** 把投射物解析成它的发射者；非投射物返回自身。 */
    private static Entity resolveSource(Entity damager) {
        if (damager instanceof Projectile projectile) {
            try {
                return (Entity) projectile.getShooter();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return damager;
    }

    /* ------------------------------------------------------------------ */
    /* 怪物不主动攻击新人                                                  */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!plugin.getConfig().getBoolean("protection.mob-ignore-protected-player", true)) {
            return;
        }
        if (!(event.getTarget() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity mob)) {
            return;
        }
        if (mob instanceof Player) {
            return;
        }
        if (plugin.getManager().isProtected(player)) {
            event.setCancelled(true);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 打 Boss 无效化                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * 新人攻击了 Boss → 进入战斗状态（保护失效 + 暂停计时）。
     *
     * <p>用 MONITOR 优先级，保证即使其他插件取消了伤害也照常判定
     * （玩家确实挥了手、打了 Boss）。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBossHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        Entity target = event.getEntity();
        if (!plugin.getManager().getBossBattle().isBoss(target)) {
            return;
        }
        // 只有还在保护期内的新人才需要处理
        if (plugin.getManager().remainingSeconds(attacker) <= 0L) {
            return;
        }
        plugin.getManager().getBossBattle().markBossHit(attacker, target);
    }

    /* ------------------------------------------------------------------ */
    /* 可选：保护期内禁止主动打人 / 破坏（默认关闭）                        */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttackOthers(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("extra.block-attack-others", false)) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        if (plugin.getManager().isProtected(attacker)) {
            event.setCancelled(true);
            plugin.sendConfigMessage(attacker, "no-permission", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("extra.block-break", false)) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.getManager().isProtected(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("extra.block-place", false)) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.getManager().isProtected(player)) {
            event.setCancelled(true);
        }
    }

    /** 供诊断输出用。 */
    public static String describe(ProtectionManager manager, Player player) {
        return "剩余 " + ProtectionManager.format(manager.remainingSeconds(player));
    }
}
