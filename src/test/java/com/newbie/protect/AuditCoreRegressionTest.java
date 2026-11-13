package com.newbie.protect;

import com.newbie.protect.command.NewbieCommand;
import com.newbie.protect.data.PlayerDataStore;
import com.newbie.protect.listener.ProtectionListener;
import com.newbie.protect.manager.ProtectionManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.lang.reflect.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditCoreRegressionTest {
    @TempDir Path folder;
    NewbieProtect plugin; YamlConfiguration config; PlayerDataStore store;
    ProtectionManager manager; ProtectionListener listener; Player player;
    @BeforeEach void setup() {
        plugin=mock(NewbieProtect.class); config=new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("audit-core"));
        when(plugin.isProtectEnabled()).thenReturn(true);
        when(plugin.isSelfToggleEnabled()).thenReturn(true);
        when(plugin.isCountWhileDisabled()).thenReturn(true);
        when(plugin.getProtectDurationSeconds()).thenReturn(7200L);
        when(plugin.getAdminMaxSeconds()).thenReturn(-1L);
        when(plugin.clampAdminSeconds(anyLong())).thenAnswer(i->i.getArgument(0));
        when(plugin.getRemindSeconds()).thenReturn(List.of(60L));
        config.set("boss-bar.enabled",false);
        store=new PlayerDataStore(plugin); when(plugin.getDataStore()).thenReturn(store);
        manager=new ProtectionManager(plugin); when(plugin.getManager()).thenReturn(manager);
        listener=new ProtectionListener(plugin);
        player=mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Target"); when(player.isOnline()).thenReturn(true);
        store.getOrCreate(player.getUniqueId(),"Target");
    }
    @AfterEach void close() { store.close(); }
    EntityDamageByEntityEvent hit(Entity direct,Entity victim,Entity causing) {
        var source=mock(DamageSource.class); when(source.getCausingEntity()).thenReturn(causing);
        when(source.getDirectEntity()).thenReturn(direct);
        return new EntityDamageByEntityEvent(direct,victim,DamageCause.ENTITY_EXPLOSION,source,5);
    }
    void tick() throws Exception {
        var method=ProtectionManager.class.getDeclaredMethod("tickOne",Player.class,long.class);
        method.setAccessible(true); method.invoke(manager,player,7200L);
    }
    void command(String... args) {
        var admin=mock(CommandSender.class); when(admin.hasPermission("newbieprotect.admin")).thenReturn(true);
        try(var b=mockStatic(Bukkit.class)) {
            b.when(()->Bukkit.getPlayerExact("Target")).thenReturn(player);
            b.when(()->Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            new NewbieCommand(plugin).onCommand(admin,null,"newbie",args);
        }
    }
    long notices(String key) { return mockingDetails(plugin).getInvocations().stream()
            .filter(i->i.getMethod().getName().equals("sendConfigMessage")&&key.equals(i.getArgument(1))).count(); }
    @Test void evokerFangsAreBlockedAsMobDamage() {
        var event=hit(mock(EvokerFangs.class),player,mock(Evoker.class)); listener.onDamage(event);
        assertTrue(event.isCancelled());
    }
    @Test void playerTntIsBlockedAsPlayerDamage() {
        var event=hit(mock(TNTPrimed.class),player,mock(Player.class)); listener.onDamage(event);
        assertTrue(event.isCancelled());
    }
    @Test void playerTntTriggersBossCombat() {
        var boss=mock(Wither.class); when(boss.getType()).thenReturn(EntityType.WITHER);
        listener.onBossHit(hit(mock(TNTPrimed.class),boss,player));
        assertTrue(manager.getBossBattle().isInBattle(player));
    }
    @Test void playerTntCannotBypassOutgoingRestriction() {
        config.set("extra.block-attack-others",true);
        var event=hit(mock(TNTPrimed.class),mock(Player.class),player); listener.onAttackOthers(event);
        assertTrue(event.isCancelled());
    }
    @Test void disabledAutoStartDoesNotCreateOnJoinOrTick() throws Exception {
        config.set("protection.start-on-first-join",false); store.remove(player.getUniqueId());
        manager.onJoin(player); tick();
        assertNull(store.get(player.getUniqueId())); assertFalse(manager.isProtected(player));
    }
    @Test void unknownPlayerDoesNotGetImplicitProtection() {
        store.remove(player.getUniqueId()); assertEquals(0,manager.remainingSeconds(player));
    }
    @Test void clearWaitsForNextJoin() throws Exception {
        command("clear","Target"); tick();
        assertNull(store.get(player.getUniqueId())); assertFalse(manager.isProtected(player));
        manager.onJoin(player); assertNotNull(store.get(player.getUniqueId()));
    }
    @Test void disablingSelfCommandsDoesNotStopDisabledTimeConsumption() throws Exception {
        when(plugin.isSelfToggleEnabled()).thenReturn(false);
        store.get(player.getUniqueId()).selfDisabled(true); tick();
        assertEquals(1,store.get(player.getUniqueId()).usedSeconds());
    }
    @Test void relogDoesNotEraseBossTimeout() {
        manager.getBossBattle().markBossHit(player,mock(Wither.class));
        manager.onQuit(player); manager.onJoin(player);
        assertFalse(manager.isProtected(player));
    }
    @Test void reenteringExpiredBossCombatSendsWarning() throws Exception {
        var battle=manager.getBossBattle(); battle.markBossHit(player,mock(Wither.class));
        var field=battle.getClass().getDeclaredField("lastBossHit"); field.setAccessible(true);
        ((Map<UUID,Long>)field.get(battle)).put(player.getUniqueId(),System.currentTimeMillis()-60000);
        clearInvocations(plugin); battle.markBossHit(player,mock(Wither.class));
        assertEquals(1,notices("boss-battle-enter"));
    }
    @Test void publicApiAllowsBonusTime() {
        manager.setRemainingSeconds(player,14400); assertEquals(14400,manager.remainingSeconds(player));
    }
    @Test void grantResetsReminderThresholds() throws Exception {
        var check=ProtectionManager.class.getDeclaredMethod("checkRemind",Player.class,UUID.class,long.class);
        check.setAccessible(true); check.invoke(manager,player,player.getUniqueId(),60L);
        command("grant","Target"); check.invoke(manager,player,player.getUniqueId(),60L);
        assertEquals(2,notices("ending-soon"));
    }
    @Test void takeIsNotLimitedByMaximumRemainingTime() {
        when(plugin.getAdminMaxSeconds()).thenReturn(3600L); manager.setRemainingSeconds(player,3600);
        command("take","Target","7200"); assertEquals(0,manager.remainingSeconds(player));
    }
    @Test void rejectsOverflowingTimeUnits() {
        assertNull(NewbieCommand.parseTime("9223372036854775807h"));
        assertNull(NewbieCommand.parseTime("9223372036854775807s1s"));
    }
    @Test void overflowingAddLeavesRecordUnchanged() {
        command("add","Target",Long.toString(Long.MAX_VALUE));
        assertEquals(7200,manager.remainingSeconds(player)); assertEquals(0,store.get(player.getUniqueId()).usedSeconds());
    }
    @Test void frozenPlayerIsNotWelcomedAsProtected() {
        store.get(player.getUniqueId()).adminPaused(true); manager.onJoin(player);
        assertEquals(0,notices("join-protected"));
    }

    @Test void bossBarPreferenceCannotGrantProtectionToUntrackedPlayer() throws Exception {
        config.set("protection.start-on-first-join",false);
        store.remove(player.getUniqueId());
        var field=NewbieProtect.class.getDeclaredField("dataStore"); field.setAccessible(true); field.set(plugin,store);
        doCallRealMethod().when(plugin).setBossBarHidden(player,true);
        assertFalse(plugin.setBossBarHidden(player,true));
        assertNull(store.get(player.getUniqueId()));
    }

    void assertBarTitle(String expected) throws Exception {
        config.set("boss-bar.enabled",true);
        var bar=mock(org.bukkit.boss.BossBar.class); when(bar.getPlayers()).thenReturn(List.of(player));
        when(plugin.langRaw(anyString(),anyString())).thenAnswer(call->switch ((String)call.getArgument(0)) {
            case "boss-bar.title-boss-battle" -> "battle %timing%";
            case "boss-bar.title-paused" -> "admin %reason%|%timing%";
            case "boss-bar.timing-note-counting", "boss-bar.pause-note-counting" -> "COUNTING";
            case "boss-bar.timing-note-paused", "boss-bar.pause-note-paused" -> "PAUSED";
            default -> call.getArgument(1);
        });
        when(plugin.formatDuration(anyLong())).thenAnswer(call->ProtectionManager.format(call.getArgument(0)));
        doCallRealMethod().when(plugin).parsePlaceholders(anyString(),eq(player),any(String[].class));
        try(var b=mockStatic(Bukkit.class)) {
            b.when(()->Bukkit.createBossBar(anyString(),any(org.bukkit.boss.BarColor.class),any(org.bukkit.boss.BarStyle.class))).thenReturn(bar);
            tick();
        }
        verify(bar).setTitle(expected);
    }
    @Test void countingBossBattleBarDoesNotClaimPausedBecauseSelfToggleWouldPause() throws Exception {
        when(plugin.isCountWhileDisabled()).thenReturn(false);
        config.set("boss-battle.pause-timer-in-battle",false);
        manager.getBossBattle().markBossHit(player,mock(Wither.class));
        assertBarTitle("battle COUNTING"); assertEquals(1,store.get(player.getUniqueId()).usedSeconds());
    }
    @Test void frozenBossBarDisplaysActualPauseReason() throws Exception {
        store.get(player.getUniqueId()).adminPaused(true);
        when(plugin.langText("messages.pause-reason-admin","管理员冻结")).thenReturn("ADMIN_PAUSE");
        assertBarTitle("admin ADMIN_PAUSE|PAUSED");
    }

    void enableRealEnglishMessages() throws Exception {
        when(plugin.getResource(anyString())).thenAnswer(call->{
            var resource=Path.of("src/main/resources").resolve((String)call.getArgument(0));
            return java.nio.file.Files.exists(resource)?java.nio.file.Files.newInputStream(resource):null;
        });
        config.set("language","en_US");
        var language=new com.newbie.protect.config.LanguageManager(plugin); language.load();
        var field=NewbieProtect.class.getDeclaredField("lang"); field.setAccessible(true); field.set(plugin,language);
        doCallRealMethod().when(plugin).langText(anyString(),nullable(String.class),any(String[].class));
        doCallRealMethod().when(plugin).langRaw(anyString(),nullable(String.class));
        doCallRealMethod().when(plugin).parsePlaceholders(anyString(),nullable(Player.class),any(String[].class));
        doCallRealMethod().when(plugin).sendConfigMessage(any(),anyString(),any(String[].class));
        doCallRealMethod().when(plugin).formatDuration(anyLong());
    }
    @Test void frozenJoinMessageReplacesTimingPlaceholder() throws Exception {
        enableRealEnglishMessages(); store.get(player.getUniqueId()).adminPaused(true);
        manager.onJoin(player);
        var text=org.mockito.ArgumentCaptor.forClass(String.class); verify(player).sendMessage(text.capture());
        assertFalse(text.getValue().contains("%timing%")); assertTrue(text.getValue().contains("Timer paused"));
    }
    @Test void frozenSelfQueryReportsActualPausedTiming() throws Exception {
        enableRealEnglishMessages(); store.get(player.getUniqueId()).adminPaused(true);
        new NewbieCommand(plugin).onCommand(player,null,"newbie",new String[]{});
        var text=org.mockito.ArgumentCaptor.forClass(String.class); verify(player).sendMessage(text.capture());
        assertTrue(text.getValue().contains("Timer paused")); assertFalse(text.getValue().contains("Time still counts"));
    }

    @Test void shutdownUsesSelectedConsoleLanguage() throws Exception {
        enableRealEnglishMessages(); var logger=mock(Logger.class); when(plugin.getLogger()).thenReturn(logger);
        doCallRealMethod().when(plugin).performShutdown("test");
        plugin.performShutdown("test");
        verify(logger).info("Shutting down NewbieProtect (test)...");
        verify(logger).info("NewbieProtect shut down safely.");
    }

    @Test void inactiveReasonUsesSelectedLanguage() throws Exception {
        enableRealEnglishMessages(); when(plugin.isProtectEnabled()).thenReturn(false);
        assertEquals("Plugin is disabled",manager.state(player).pauseReason());
    }

    @Test void frozenSelfToggleReportsActualPausedTiming() throws Exception {
        enableRealEnglishMessages(); store.get(player.getUniqueId()).adminPaused(true);
        new NewbieCommand(plugin).onCommand(player,null,"newbie",new String[]{"off"});
        var text=org.mockito.ArgumentCaptor.forClass(String.class); verify(player).sendMessage(text.capture());
        assertTrue(text.getValue().contains("Timer paused")); assertFalse(text.getValue().contains("Time still counts"));
    }
}
