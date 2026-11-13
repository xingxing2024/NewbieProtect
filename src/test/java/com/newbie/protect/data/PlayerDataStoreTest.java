package com.newbie.protect.data;

import com.newbie.protect.NewbieProtect;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerDataStoreTest {
    @TempDir Path folder;
    NewbieProtect plugin() {
        var plugin = mock(NewbieProtect.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("storage-test"));
        return plugin;
    }

    @Test void shutdownFlushesLatestDataAndKeepsGrantedTimeAndFlags() {
        var plugin = plugin();
        var store = new PlayerDataStore(plugin);
        UUID id = UUID.randomUUID();
        var entry = store.getOrCreate(id, "Newbie");
        entry.usedSeconds(-21600L);
        entry.selfDisabled(true);
        entry.adminPaused(true);
        entry.bossBarHidden(true);
        store.requestSave();
        for (int i = 0; i < 100; i++) {
            entry.addUsedSeconds(1);
            store.markDirty();
            store.requestSave();
        }
        store.close();
        var restored = new PlayerDataStore(plugin);
        restored.load();
        assertEquals(-21500L, restored.get(id).usedSeconds());
        assertTrue(restored.get(id).selfDisabled());
        assertTrue(restored.get(id).adminPaused());
        assertTrue(restored.get(id).bossBarHidden());
        restored.close();
    }

    @Test void failedReplacementRetainsDirtyStateAndCanRetry() throws Exception {
        var store = new PlayerDataStore(plugin());
        UUID id = UUID.randomUUID();
        store.getOrCreate(id, "Newbie");
        Path target = folder.resolve("data.yml");
        Files.createDirectories(target);
        Files.writeString(target.resolve("keep.txt"), "original");
        store.save();
        assertTrue(store.isDirty());
        assertEquals("original", Files.readString(target.resolve("keep.txt")));
        Files.delete(target.resolve("keep.txt"));
        Files.delete(target);
        store.save();
        assertFalse(store.isDirty());
        assertEquals("Newbie", YamlConfiguration.loadConfiguration(target.toFile())
                .getString("players." + id + ".name"));
        store.close();
    }

    @Test void mutationDuringWriteIsNotMarkedAsSavedAndDoesNotBlockPlayerUpdates() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var writer = new AtomicYamlFile(folder.resolve("data.yml")) {
            @Override void write(YamlConfiguration yaml) throws IOException {
                if (first.getAndSet(false)) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("write timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                }
                super.write(yaml);
            }
        };
        var store = new PlayerDataStore(plugin(), writer);
        UUID id = UUID.randomUUID();
        var entry = store.getOrCreate(id, "Newbie");
        var saves = Executors.newSingleThreadExecutor();
        var pending = saves.submit(store::save);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var updates = Executors.newSingleThreadExecutor();
            try {
                updates.submit(() -> {
                    store.get(id).addUsedSeconds(7);
                    store.markDirty();
                }).get(2, TimeUnit.SECONDS);
            } finally {
                updates.shutdownNow();
            }
        } finally {
            release.countDown();
            saves.shutdown();
        }
        pending.get(5, TimeUnit.SECONDS);
        assertTrue(store.isDirty(), "A mutation after the snapshot must remain dirty");
        assertEquals(0L, YamlConfiguration.loadConfiguration(folder.resolve("data.yml").toFile())
                .getLong("players." + id + ".used-seconds"));
        store.close();
        assertEquals(7L, YamlConfiguration.loadConfiguration(folder.resolve("data.yml").toFile())
                .getLong("players." + id + ".used-seconds"));
        assertFalse(store.isDirty());
    }

    @Test void requestDuringWriteIsSavedWithoutWaitingForNextPeriodicFlush() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondWrite = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var writer = new AtomicYamlFile(folder.resolve("data.yml")) {
            @Override void write(YamlConfiguration yaml) throws IOException {
                boolean initial = first.getAndSet(false);
                if (initial) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("write timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                }
                super.write(yaml);
                if (!initial) secondWrite.countDown();
            }
        };
        var store = new PlayerDataStore(plugin(), writer);
        UUID id = UUID.randomUUID();
        var entry = store.getOrCreate(id, "Newbie");
        store.requestSave();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            entry.addUsedSeconds(9);
            store.requestSave();
            release.countDown();
            assertTrue(secondWrite.await(3, TimeUnit.SECONDS), "Queued changes must trigger another write");
            assertEquals(9L, YamlConfiguration.loadConfiguration(folder.resolve("data.yml").toFile())
                    .getLong("players." + id + ".used-seconds"));
        } finally {
            release.countDown();
            store.close();
        }
    }
}
