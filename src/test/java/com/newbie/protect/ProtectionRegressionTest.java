package com.newbie.protect;

import com.newbie.protect.data.PlayerDataStore;
import com.newbie.protect.listener.ProtectionListener;
import com.newbie.protect.manager.ProtectionManager;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProtectionRegressionTest {
    @TempDir Path folder;
    NewbieProtect plugin;
    YamlConfiguration config;
    ProtectionManager manager;
    PlayerDataStore store;
    Player newbie;
    ProtectionListener listener;

    @BeforeEach void setup() {
        plugin = mock(NewbieProtect.class);
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("regression"));
        when(plugin.isProtectEnabled()).thenReturn(true);
        when(plugin.getProtectDurationSeconds()).thenReturn(7200L);
        when(plugin.getRemindSeconds()).thenReturn(List.of());
        store = new PlayerDataStore(plugin);
        when(plugin.getDataStore()).thenReturn(store);
        manager = new ProtectionManager(plugin);
        when(plugin.getManager()).thenReturn(manager);
        newbie = mock(Player.class);
        when(newbie.getUniqueId()).thenReturn(UUID.randomUUID());
        when(newbie.getName()).thenReturn("Newbie");
        when(newbie.isOnline()).thenReturn(true);
        store.getOrCreate(newbie.getUniqueId(), "Newbie");
        config.set("boss-bar.enabled", false);
        listener = new ProtectionListener(plugin);
    }

    EntityDamageByEntityEvent hit(Entity attacker, Entity target) {
        return new EntityDamageByEntityEvent(attacker, target, DamageCause.PROJECTILE,
                mock(DamageSource.class), 5.0);
    }

    @Test void enabledAttackRestrictionBlocksPlayerArrow() {
        config.set("extra.block-attack-others", true);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(newbie);
        var event = hit(arrow, mock(Player.class));
        listener.onAttackOthers(event);
        assertTrue(event.isCancelled());
    }

    @Test void enabledAttackRestrictionBlocksPlayerTrident() {
        config.set("extra.block-attack-others", true);
        Trident trident = mock(Trident.class);
        when(trident.getShooter()).thenReturn(newbie);
        var event = hit(trident, mock(Player.class));
        listener.onAttackOthers(event);
        assertTrue(event.isCancelled());
    }

    @Test void disabledAttackRestrictionStillAllowsPlayerArrow() {
        config.set("extra.block-attack-others", false);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(newbie);
        var event = hit(arrow, mock(Player.class));
        listener.onAttackOthers(event);
        assertFalse(event.isCancelled());
    }

    @Test void unprotectedPlayerCanStillAttack() {
        config.set("extra.block-attack-others", true);
        store.get(newbie.getUniqueId()).selfDisabled(true);
        var event = hit(newbie, mock(Player.class));
        listener.onAttackOthers(event);
        assertFalse(event.isCancelled());
    }

    @Test void arrowAgainstBossDisablesProtection() {
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(newbie);
        Wither boss = mock(Wither.class);
        when(boss.getType()).thenReturn(EntityType.WITHER);
        listener.onBossHit(hit(arrow, boss));
        assertTrue(manager.getBossBattle().isInBattle(newbie));
        assertFalse(manager.isProtected(newbie));
    }

    @Test void bossbarSettingRecognizesNativeBossWithoutTypeList() {
        config.set("boss-battle.builtin-bosses", List.of());
        manager.getBossBattle().reload();
        Wither boss = mock(Wither.class);
        when(boss.getType()).thenReturn(EntityType.WITHER);
        assertTrue(manager.getBossBattle().isBoss(boss));
    }

    @Test void unrelatedResidenceSettingDoesNotEnableProtectionInPausedWorld() {
        when(plugin.isWorldListEnabled()).thenReturn(true);
        when(plugin.getWorldListMode()).thenReturn("allow");
        World world = mock(World.class);
        when(newbie.getWorld()).thenReturn(world);
        when(plugin.isWorldListed(world)).thenReturn(true);
        config.set("world-list.protect-in-paused-world", false);
        config.set("residence.protect-in-own-residence", true);
        assertFalse(manager.isProtected(newbie));
    }

    void tick() throws Exception {
        Method method = ProtectionManager.class.getDeclaredMethod("tickOne", Player.class, long.class);
        method.setAccessible(true);
        method.invoke(manager, newbie, 7200L);
    }

    @Test void unrelatedResidenceSettingDoesNotConsumeTimeInPausedWorld() throws Exception {
        when(plugin.isWorldListEnabled()).thenReturn(true);
        when(plugin.getWorldListMode()).thenReturn("allow");
        World world = mock(World.class);
        when(newbie.getWorld()).thenReturn(world);
        when(plugin.isWorldListed(world)).thenReturn(true);
        config.set("world-list.count-while-in-paused-world", false);
        config.set("residence.count-while-inside", true);
        tick();
        assertEquals(0L, store.get(newbie.getUniqueId()).usedSeconds());
    }

    @Test void bossPauseStopsTimerEvenWhenWorldRuleWouldCount() throws Exception {
        config.set("world-list.count-while-in-paused-world", true);
        Wither boss = mock(Wither.class);
        when(boss.getType()).thenReturn(EntityType.WITHER);
        manager.getBossBattle().markBossHit(newbie, boss);
        tick();
        assertEquals(0L, store.get(newbie.getUniqueId()).usedSeconds());
        assertFalse(manager.isProtected(newbie));
    }

    @Test void matchingWorldAndResidenceUseRestrictiveRules() throws Exception {
        try (var hooks = mockConstruction(com.newbie.protect.hook.ResidenceHook.class,
                (hook, context) -> {
                    when(hook.isAvailable()).thenReturn(true);
                    when(hook.isInOwnResidence(newbie, false)).thenReturn(true);
                })) {
            when(plugin.isWorldListEnabled()).thenReturn(true);
            when(plugin.getWorldListMode()).thenReturn("allow");
            World world = mock(World.class);
            when(newbie.getWorld()).thenReturn(world);
            when(plugin.isWorldListed(world)).thenReturn(true);
            when(plugin.isResidenceEnabled()).thenReturn(true);
            config.set("world-list.count-while-in-paused-world", true);
            config.set("world-list.protect-in-paused-world", true);
            config.set("residence.count-while-inside", false);
            config.set("residence.protect-in-own-residence", false);
            manager = new ProtectionManager(plugin);
            when(plugin.getManager()).thenReturn(manager);
            assertFalse(manager.isProtected(newbie));
            tick();
            assertEquals(0L, store.get(newbie.getUniqueId()).usedSeconds());
        }
    }

    @Test void disabledBossRuleDoesNotEnterCombat() {
        config.set("boss-battle.enabled", false);
        Wither boss = mock(Wither.class);
        when(boss.getType()).thenReturn(EntityType.WITHER);
        listener.onBossHit(hit(newbie, boss));
        assertFalse(manager.getBossBattle().isInBattle(newbie));
        assertTrue(manager.isProtected(newbie));
    }

    @Test void reloadedCustomBossListTakesEffect() {
        config.set("boss-battle.builtin-bosses", List.of());
        config.set("boss-battle.custom-bosses", List.of("ZOMBIE", "invalid-type"));
        manager.getBossBattle().reload();
        Zombie zombie = mock(Zombie.class);
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        assertTrue(manager.getBossBattle().isBoss(zombie));
        config.set("boss-battle.custom-bosses", List.of());
        manager.getBossBattle().reload();
        assertFalse(manager.getBossBattle().isBoss(zombie));
    }

    @Test void administratorPauseStopsTimerAndProtectionDespitePermissiveAreaSettings() throws Exception {
        store.get(newbie.getUniqueId()).adminPaused(true);
        config.set("world-list.count-while-in-paused-world", true);
        config.set("residence.protect-in-own-residence", true);
        tick();
        assertEquals(0L, store.get(newbie.getUniqueId()).usedSeconds());
        assertFalse(manager.isProtected(newbie));
    }

    @Test void queuedTickDoesNotConsumeTimeAfterManagerShutdown() throws Exception {
        manager.shutdown();
        tick();
        assertEquals(0L, store.get(newbie.getUniqueId()).usedSeconds());
    }
}
