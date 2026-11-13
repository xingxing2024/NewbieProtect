package com.newbie.protect.data;

import com.newbie.protect.NewbieProtect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerDataLoadSafetyTest {
    @TempDir Path folder;
    final UUID id = UUID.fromString("e35058dd-ccf8-4290-a571-5d3f960def34");
    PlayerDataStore store() {
        var plugin = mock(NewbieProtect.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("load-safety-test"));
        return new PlayerDataStore(plugin);
    }
    Path file() { return folder.resolve("data.yml"); }
    String valid() { return "players:\n  " + id + ":\n    name: Veteran\n    used-seconds: -7200\n"; }

    @Test void malformedYamlBlocksEverySaveIncludingShutdown() throws Exception {
        String original = "players: [\n# recoverable original\n";
        Files.writeString(file(), original);
        var store = store();
        store.load();
        assertFalse(store.isLoadHealthy());
        store.getOrCreate(UUID.randomUUID(), "NewPlayer");
        store.save();
        store.requestSave();
        store.close();
        assertEquals(original, Files.readString(file()));
    }

    @Test void failedReloadKeepsPreviousCacheAndSuccessfulExplicitLoadRecovers() throws Exception {
        Files.writeString(file(), valid());
        var store = store();
        try {
            store.load();
            var original = store.get(id);
            Files.writeString(file(), "players: [\n");
            store.load();
            assertFalse(store.isLoadHealthy());
            assertSame(original, store.get(id), "Failed reload must preserve existing entries");
            Files.writeString(file(), valid().replace("-7200", "-3600"));
            store.load();
            assertTrue(store.isLoadHealthy());
            assertEquals(-3600, store.get(id).usedSeconds());
            store.get(id).usedSeconds(-1800);
            store.markDirty();
            store.save();
            assertTrue(Files.readString(file()).contains("-1800"), "Recovery must enable saves");
        } finally { store.close(); }
    }

    @Test void malformedStructureOrFieldsCannotBeSilentlyDroppedAndOverwritten() throws Exception {
        String prefix = "players:\n  " + id + ":\n";
        for (String invalid : new String[] {
                "players: []\n", "players: wrong\n", "unexpected: true\n",
                "players: null\n", "players:\n  " + id + ": null\n",
                "players:\n  " + id + ": {}\n  " + id + ": {}\n",
                "players:\n  not-a-uuid: {}\n", "players:\n  " + id + ": scalar\n",
                prefix + "    used-seconds: nonsense\n", prefix + "    used-seconds: 1.5\n",
                prefix + "    used-seconds: 9223372036854775808\n",
                prefix + "    first-join: -1\n", prefix + "    finished: maybe\n",
                prefix + "    name: []\n"
        }) {
            Files.writeString(file(), invalid);
            var store = store();
            store.load();
            store.close();
            assertEquals(invalid, Files.readString(file()), "Must retain invalid input: " + invalid);
        }
    }

    @Test void missingFreshFileAndHistoricalOptionalFieldsRemainSupported() throws Exception {
        var store = store();
        store.load();
        assertTrue(store.isLoadHealthy());
        store.getOrCreate(id, "Newbie");
        store.close();
        assertTrue(Files.isRegularFile(file()));
        Files.writeString(file(), valid());
        var restored = store();
        try {
            restored.load();
            assertEquals(-7200, restored.get(id).usedSeconds());
            assertEquals(0, restored.get(id).firstJoin());
            assertFalse(restored.get(id).finished());
        } finally { restored.close(); }
    }
    @Test void reloadWaitingForWriterDoesNotEraseNewerMutation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reloader = new Thread[1];
        var writer = new AtomicYamlFile(file()) {
            @Override void write(YamlConfiguration yaml) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
                } catch (InterruptedException e) { throw new IOException(e); }
                super.write(yaml);
            }
        };
        var plugin = mock(NewbieProtect.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("load-race-test"));
        var store = new PlayerDataStore(plugin, writer);
        var entry = store.getOrCreate(id, "Before");
        var saver = new Thread(store::save);
        saver.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            reloader[0] = new Thread(store::load);
            reloader[0].start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (reloader[0].getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.BLOCKED, reloader[0].getState());
            entry.usedSeconds(12);
            store.markDirty();
            release.countDown();
            saver.join(5000);
            reloader[0].join(5000);
            assertEquals(12, store.get(id).usedSeconds(), "A load queued behind a write must retain newer memory data");
        } finally {
            release.countDown();
            saver.join(5000);
            if (reloader[0] != null) reloader[0].join(5000);
            store.close();
        }
    }

    @Test void disappearedFileCannotClearPreviouslyLoadedRecords() throws Exception {
        Files.writeString(file(), valid());
        var store = store();
        try {
            store.load();
            var original = store.get(id);
            Files.delete(file());
            store.load();
            assertSame(original, store.get(id));
            assertFalse(store.isLoadHealthy());
        } finally { store.close(); }
        assertFalse(Files.exists(file()), "Shutdown must not recreate a file after a failed reload");
    }

}
