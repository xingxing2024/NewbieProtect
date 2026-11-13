package com.newbie.protect;

import com.newbie.protect.data.PlayerDataStore;
import com.newbie.protect.hook.PlaceholderBridgeExtension;
import com.newbie.protect.manager.ProtectionManager;
import com.newbie.protect.metrics.MetricsManager;
import org.bstats.bukkit.Metrics;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Wither;
import org.bukkit.entity.EntityType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationRegressionTest {
    @TempDir Path folder;
    NewbieProtect plugin;
    YamlConfiguration config;
    PlayerDataStore store;
    ProtectionManager manager;
    PlaceholderBridgeExtension expansion;
    Player player;

    @BeforeEach void setup() {
        plugin = mock(NewbieProtect.class);
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("integrations"));
        when(plugin.getName()).thenReturn("NewbieProtect");
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.isProtectEnabled()).thenReturn(true);
        when(plugin.getProtectDurationSeconds()).thenReturn(7200L);
        store = new PlayerDataStore(plugin);
        when(plugin.getDataStore()).thenReturn(store);
        manager = new ProtectionManager(plugin);
        when(plugin.getManager()).thenReturn(manager);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Newbie");
        when(player.getPlayer()).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        when(plugin.langText(anyString(), anyString(), any(String[].class)))
                .thenAnswer(call -> call.getArgument(0));
        when(plugin.formatDuration(anyLong())).thenAnswer(call -> "duration:" + call.getArgument(0));
        expansion = new PlaceholderBridgeExtension(plugin);
    }

    @Test void absentRecordHasNoRemainingTime() {
        assertEquals("0", expansion.onRequest(player, "seconds"));
        assertNull(store.get(player.getUniqueId()));
    }

    @Test void numericPercentUsesRootLocale() {
        store.getOrCreate(player.getUniqueId(), "Newbie").usedSeconds(3600L);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("50.0", expansion.onRequest(player, "percent"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test void ceilMinutesDoesNotOverflow() {
        store.getOrCreate(player.getUniqueId(), "Newbie");
        when(plugin.getProtectDurationSeconds()).thenReturn(Long.MAX_VALUE);
        assertEquals("153722867280912931", expansion.onRequest(player, "minutes"));
    }

    @Test void expansionIdentifiesOwningPluginAndRejectsShutdownRegistration() {
        assertEquals("NewbieProtect", expansion.getRequiredPlugin());
        assertTrue(expansion.canRegister());
        when(plugin.isShuttingDown()).thenReturn(true);
        assertFalse(expansion.canRegister());
    }

    @Test void offlineRecordIsPausedAndLocalized() {
        store.getOrCreate(player.getUniqueId(), "Newbie");
        OfflinePlayer offline = mock(OfflinePlayer.class);
        UUID uuid = player.getUniqueId();
        when(offline.getUniqueId()).thenReturn(uuid);
        assertEquals("true", expansion.onRequest(offline, "paused"));
        assertEquals("false", expansion.onRequest(offline, "protected"));
        assertEquals("status.protected-offline", expansion.onRequest(offline, "status"));
    }

    @Test void bossCombatStateMatchesTimerAndImmunity() {
        store.getOrCreate(player.getUniqueId(), "Newbie");
        Wither boss = mock(Wither.class);
        when(boss.getType()).thenReturn(EntityType.WITHER);
        manager.getBossBattle().markBossHit(player, boss);
        assertEquals("true", expansion.onRequest(player, "paused"));
        assertEquals("false", expansion.onRequest(player, "protected"));
        assertEquals("status.boss-battle", expansion.onRequest(player, "status"));
        assertEquals(manager.state(player).pauseReason(), expansion.onRequest(player, "pause_reason"));
    }

    @Test void areaThatContinuesCountingDoesNotClaimPaused() {
        store.getOrCreate(player.getUniqueId(), "Newbie");
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        when(plugin.isWorldListEnabled()).thenReturn(true);
        when(plugin.getWorldListMode()).thenReturn("allow");
        when(plugin.isWorldListed(world)).thenReturn(true);
        config.set("world-list.count-while-in-paused-world", true);
        assertEquals("false", expansion.onRequest(player, "paused"));
        assertEquals("false", expansion.onRequest(player, "protected"));
        assertEquals("status.unprotected", expansion.onRequest(player, "status"));
    }

    @Test void expiredRecordCannotExposeRemainingProtection() {
        var entry = store.getOrCreate(player.getUniqueId(), "Newbie");
        entry.firstJoin(System.currentTimeMillis() - 31L * 86_400_000L);
        config.set("protection.max-absolute-days", 30L);
        assertEquals("0", expansion.onRequest(player, "seconds"));
        assertEquals("true", expansion.onRequest(player, "finished"));
        assertEquals("status.finished", expansion.onRequest(player, "status"));
    }

    @Test void durationPlaceholdersUsePluginLanguageFormatter() {
        store.getOrCreate(player.getUniqueId(), "Newbie").usedSeconds(60L);
        assertEquals("duration:7140", expansion.onRequest(player, "time"));
        assertEquals("duration:60", expansion.onRequest(player, "used"));
        assertEquals("duration:7200", expansion.onRequest(player, "total"));
    }

    @Test void shutdownDoesNotExposeProtectionEvenWithStaleManagerState() {
        store.getOrCreate(player.getUniqueId(), "Newbie");
        when(plugin.isShuttingDown()).thenReturn(true);
        assertEquals("false", expansion.onRequest(player, "protected"));
        assertEquals("false", expansion.onRequest(player, "enabled"));
        assertEquals("status.inactive", expansion.onRequest(player, "status"));
    }

    @Test void globalProtectionSwitchDisablesImmunityAndCounting() {
        store.getOrCreate(player.getUniqueId(), "Newbie");
        when(plugin.isProtectEnabled()).thenReturn(false);
        assertFalse(manager.state(player).protectedNow());
        assertFalse(manager.state(player).counting());
        assertEquals("false", expansion.onRequest(player, "protected"));
        assertEquals("true", expansion.onRequest(player, "paused"));
        assertEquals("status.inactive", expansion.onRequest(player, "status"));
    }

    @Test void selfDisabledWithStoppedTimerReportsPaused() {
        store.getOrCreate(player.getUniqueId(), "Newbie").selfDisabled(true);
        config.set("self-toggle.count-while-disabled", false);
        assertEquals("true", expansion.onRequest(player, "paused"));
        assertEquals("false", expansion.onRequest(player, "protected"));
        assertEquals("status.disabled", expansion.onRequest(player, "status"));
    }

    @Test void pluginShutdownUnregistersExpansionAndStopsMetrics() throws Exception {
        Runnable unregister = mock(Runnable.class);
        MetricsManager metrics = mock(MetricsManager.class);
        var unregisterField = NewbieProtect.class.getDeclaredField("unregisterPlaceholders");
        unregisterField.setAccessible(true);
        unregisterField.set(plugin, unregister);
        var metricsField = NewbieProtect.class.getDeclaredField("metrics");
        metricsField.setAccessible(true);
        metricsField.set(plugin, metrics);
        doCallRealMethod().when(plugin).performShutdown(anyString());
        plugin.performShutdown("test");
        plugin.performShutdown("repeat");
        verify(unregister).run();
        verify(metrics).shutdown();
    }

    @Test void metricsHasReloadableInstanceLifecycle() {
        try (var construction = mockConstruction(Metrics.class)) {
            MetricsManager controller = new MetricsManager(plugin);
            config.set("metrics.enabled", false);
            assertFalse(controller.reload());
            assertEquals(0, construction.constructed().size());
            config.set("metrics.enabled", true);
            assertTrue(controller.reload());
            assertTrue(controller.reload());
            assertEquals(1, construction.constructed().size());
            Metrics first = construction.constructed().getFirst();
            config.set("metrics.enabled", false);
            assertFalse(controller.reload());
            assertFalse(controller.reload());
            verify(first).shutdown();
            config.set("metrics.enabled", true);
            assertTrue(controller.reload());
            assertEquals(2, construction.constructed().size());
            Metrics second = construction.constructed().getLast();
            controller.shutdown();
            controller.shutdown();
            assertFalse(controller.reload());
            verify(second).shutdown();
            assertEquals(2, construction.constructed().size());
        }
    }

    @Test void failedMetricsInitializationClosesPartialReporterAndAllowsRetry() {
        try (var construction = mockConstruction(Metrics.class, (reporter, context) -> {
            if (context.getCount() == 1) {
                doThrow(new IllegalStateException("chart failure")).when(reporter).addCustomChart(any());
            }
        })) {
            MetricsManager controller = new MetricsManager(plugin);
            assertFalse(controller.reload());
            verify(construction.constructed().getFirst()).shutdown();
            assertTrue(controller.reload());
            assertTrue(controller.reload());
            assertEquals(2, construction.constructed().size());
            controller.shutdown();
            verify(construction.constructed().getLast()).shutdown();
        }
    }

    @Test void metricsShutdownFailureClearsReferenceAndAllowsFreshReporter() {
        try (var construction = mockConstruction(Metrics.class, (reporter, context) -> {
            doThrow(new IllegalStateException("stop failure")).when(reporter).shutdown();
        })) {
            MetricsManager controller = new MetricsManager(plugin);
            assertTrue(controller.reload());
            config.set("metrics.enabled", false);
            assertFalse(controller.reload());
            assertFalse(controller.reload());
            verify(construction.constructed().getFirst()).shutdown();
            config.set("metrics.enabled", true);
            assertTrue(controller.reload());
            assertEquals(2, construction.constructed().size());
            controller.shutdown();
            controller.shutdown();
            verify(construction.constructed().getLast()).shutdown();
        }
    }

    @Test void shuttingDownPluginCannotStartMetrics() {
        try (var construction = mockConstruction(Metrics.class)) {
            when(plugin.isShuttingDown()).thenReturn(true);
            MetricsManager controller = new MetricsManager(plugin);
            assertFalse(controller.reload());
            assertTrue(construction.constructed().isEmpty());
        }
    }
}
