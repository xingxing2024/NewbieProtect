package com.newbie.protect;

import com.newbie.protect.data.PlayerDataStore;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StartupSafetyRegressionTest {
    @TempDir Path folder;
    @Test void corruptDataDisablesPluginBeforeRegisteringListenersAndNeverOverwrites() throws Exception {
        var configPath=Path.of("src/main/resources/config.yml");
        Files.copy(configPath,folder.resolve("config.yml"));
        String broken="players: ["; Files.writeString(folder.resolve("data.yml"),broken);
        var plugin=mock(NewbieProtect.class); var server=mock(Server.class); var plugins=mock(PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("startup-safety"));
        when(plugin.getServer()).thenReturn(server); when(server.getPluginManager()).thenReturn(plugins);
        when(plugin.getConfig()).thenReturn(YamlConfiguration.loadConfiguration(configPath.toFile()));
        when(plugin.getResource(anyString())).thenAnswer(call->{
            Path resource=Path.of("src/main/resources").resolve((String)call.getArgument(0));
            return Files.exists(resource)?Files.newInputStream(resource):null;
        });
        doCallRealMethod().when(plugin).onEnable();
        plugin.onEnable();
        verify(plugins).disablePlugin(plugin);
        verify(plugins,never()).registerEvents(any(),any());
        var field=NewbieProtect.class.getDeclaredField("dataStore"); field.setAccessible(true);
        var store=(PlayerDataStore)field.get(plugin); assertNotNull(store); assertFalse(store.isLoadHealthy());
        store.close(); assertEquals(broken,Files.readString(folder.resolve("data.yml")));
    }
}
