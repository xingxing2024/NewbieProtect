package com.newbie.protect;

import com.newbie.protect.config.ConfigUpdater;
import com.newbie.protect.config.LanguageManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigLanguageRegressionTest {
    @TempDir Path folder;
    NewbieProtect plugin;
    YamlConfiguration config;

    @BeforeEach void setup() {
        plugin = mock(NewbieProtect.class);
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("config-language-regression"));
        when(plugin.getResource(anyString())).thenAnswer(inv ->
                getClass().getClassLoader().getResourceAsStream(inv.getArgument(0, String.class)));
    }

    private int update(String user) throws Exception {
        String defaults = "protection:\n  duration: 120\n  enabled: true\nother: default\n";
        when(plugin.getResource("config.yml")).thenAnswer(inv ->
                new ByteArrayInputStream(defaults.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(folder.resolve("config.yml"), user);
        return ConfigUpdater.update(plugin);
    }

    private YamlConfiguration updated() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(folder.resolve("config.yml").toFile());
        return yaml;
    }

    @Test void insertsUnderMappingParentWithInlineCommentPreservingCustomText() throws Exception {
        assertEquals(1, update("# custom header\r\nprotection: # custom settings\r\n  duration: 60 # keep me\r\nother: 'custom # value'\r\n"));
        assertTrue(updated().getBoolean("protection.enabled"));
        assertEquals(60, updated().getInt("protection.duration"));
        assertEquals("custom # value", updated().getString("other"));
        String text = Files.readString(folder.resolve("config.yml"));
        assertTrue(text.contains("protection: # custom settings\r\n"));
        assertTrue(text.contains("  duration: 60 # keep me\r\n"));
        assertTrue(text.startsWith("# custom header\r\n"));
        assertEquals(0, ConfigUpdater.update(plugin));
    }

    @Test void emptyMappingParentAcceptsChildren() throws Exception {
        assertEquals(2, update("protection: # waiting for settings\nother: custom\n"));
        assertEquals(120, updated().getInt("protection.duration"));
        assertTrue(updated().getBoolean("protection.enabled"));
    }

    @Test void scalarAndFlowParentsArePreservedWithoutInvalidBlockInsertion() throws Exception {
        for (String value : new String[]{"'# custom'", "\"# custom\"", "plain#value", "{}", "{duration: 60}", "[]"}) {
            String text = "protection: " + value + " # user value\nother: custom\n";
            assertEquals(0, update(text), value);
            assertEquals(text, Files.readString(folder.resolve("config.yml")), value);
            updated(); // The preserved result must remain valid YAML.
        }
    }

    private LanguageManager language(String code) {
        config.set("language", code);
        LanguageManager language = new LanguageManager(plugin);
        language.load();
        return language;
    }

    // Reflection keeps the red phase executable before the new public method exists.
    private String duration(LanguageManager language, long seconds) throws Exception {
        var method = assertDoesNotThrow(() -> LanguageManager.class.getMethod("formatDuration", long.class));
        return (String) method.invoke(language, seconds);
    }

    @Test void englishDurationUsesEnglishUnitsAndHandlesZeroAndLargeValues() throws Exception {
        LanguageManager language = language("en_US");
        assertEquals("1h1m1s", duration(language, 3661));
        assertEquals("1m0s", duration(language, 60));
        assertEquals("0s", duration(language, 0));
        assertEquals("0s", duration(language, -1));
        assertEquals("2562047788015215h30m7s", duration(language, Long.MAX_VALUE));
    }

    @Test void chineseAndMissingLanguageRetainChineseDuration() throws Exception {
        assertEquals("1时1分1秒", duration(language("zh_CN"), 3661));
        assertEquals("0秒", duration(language("does_not_exist"), 0));
    }

    @Test void englishVariantUsesEnglishDurationAndCustomFilesKeepOverrides() throws Exception {
        Files.createDirectories(folder.resolve("lang"));
        Path custom = folder.resolve("lang/en_GB.yml");
        String text = "messages:\n  query-self: 'Custom %time%'\n";
        Files.writeString(custom, text);
        LanguageManager language = language("en_GB");
        assertEquals("1h1m1s", duration(language, 3661));
        assertEquals("Custom 1h1m1s", language.getRawWith("messages.query-self", "fallback", "%time%", duration(language, 3661)));
        assertNotNull(language.getRaw("status.protected"));
        assertEquals(text, Files.readString(custom));
    }

    @Test void bundledMessagesRenderWithoutBrokenHelpOrMisleadingCountingNote() {
        for (String code : new String[]{"zh_CN", "en_US"}) {
            LanguageManager language = language(code);
            String title = language.getRawWith("help.title", "", "%prefix%", "[NP]", "%version%", "1.1.2");
            assertFalse(title.contains("%b"), title);
            String note = language.getRaw("boss-bar.pause-note-counting");
            assertFalse(note.contains("不计时") || note.contains("No counting"), note);
            for (String status : new String[]{"unknown", "finished", "inactive", "admin-paused", "disabled", "boss-battle", "paused", "unprotected", "protected", "protected-offline"}) {
                assertNotNull(language.getRaw("status." + status), code + ": " + status);
            }
            for (String reason : new String[]{"admin", "self", "offline", "boss-battle"}) {
                assertNotNull(language.getRaw("messages.pause-reason-" + reason), code + ": " + reason);
            }
            assertNotNull(language.getRaw("messages.boss-battle-timing-paused"));
            assertNotNull(language.getRaw("messages.boss-battle-timing-continue"));
        }
    }
    private Path writeLanguage(String code, String text) throws Exception {
        Files.createDirectories(folder.resolve("lang"));
        Path file = folder.resolve("lang/" + code + ".yml");
        Files.writeString(file, text);
        return file;
    }

    @Test void olderEnglishFileFallsBackToBundledEnglishForNewKeysWithoutChangingFile() throws Exception {
        String text = "# existing server customization\r\nmessages:\r\n  query-self: 'Server says %time%'\r\n";
        Path file = writeLanguage("en_US", text);
        byte[] original = Files.readAllBytes(file);
        LanguageManager language = language("en_US");
        assertEquals("&7Plugin disabled", language.getRaw("status.inactive"));
        assertEquals("Frozen by an administrator", language.getRaw("messages.pause-reason-admin"));
        assertEquals("Server says 1h1m1s", language.getRawWith("messages.query-self", "", "%time%", "1h1m1s"));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void exactEnglishStockMessagesAreUpdatedAtLookupWithoutRewritingFile() throws Exception {
        String text = """
                # Original 1.1.1 bundled strings, retained on disk for safe upgrades.
                help:
                  title: "  %prefix%%b&lNewbieProtect &7v%version%"
                messages:
                  pause-reason-world: "Not counting time in world %world%"
                  pause-reason-residence: "Not counting time in your own residence"
                boss-bar:
                  pause-note-counting: "No counting here (time kept)"
                  title-boss-battle: "&4Boss fight &7protection off &8| &e%time% left &8| &7restores in %battle%s"
                """;
        Path file = writeLanguage("en_US", text);
        byte[] original = Files.readAllBytes(file);
        LanguageManager language = language("en_US");
        assertEquals("  [NP]&b&lNewbieProtect &7v1.1.2", language.getRawWith("help.title", "", "%prefix%", "[NP]", "%version%", "1.1.2"));
        assertEquals("Time continues to count here", language.getRaw("boss-bar.pause-note-counting"));
        assertEquals("World overworld rules", language.getRawWith("messages.pause-reason-world", "", "%world%", "overworld"));
        assertEquals("Residence rules", language.getRaw("messages.pause-reason-residence"));
        assertEquals("&4Boss fight &7Protected &8| &e1m0s left &8| &7restores in 15s", language.getRawWith("boss-bar.title-boss-battle", "", "%state%", "Protected", "%time%", "1m0s", "%battle%", "15"));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void exactChineseStockMessagesAreUpdatedAtLookupWithoutRewritingFile() throws Exception {
        String text = """
                # 1.1.1 原始默认文本
                help:
                  title: "  %prefix%%b&l新人保护 &7v%version%"
                messages:
                  pause-reason-world: "世界 %world% 内不计时"
                  pause-reason-residence: "自己领地内不计时"
                boss-bar:
                  pause-note-counting: "此区域不计时（剩余时间保留）"
                  title-boss-battle: "&4Boss 战斗中 &7保护失效 &8| &e剩余 %time% &8| &7脱战 %battle% 秒后恢复"
                """;
        Path file = writeLanguage("zh_CN", text);
        byte[] original = Files.readAllBytes(file);
        LanguageManager language = language("zh_CN");
        assertFalse(language.getRaw("help.title").contains("%b"));
        assertEquals("此区域保护时间照常计算", language.getRaw("boss-bar.pause-note-counting"));
        assertFalse(language.getRaw("messages.pause-reason-world").contains("不计时"));
        assertFalse(language.getRaw("messages.pause-reason-residence").contains("不计时"));
        assertTrue(language.getRaw("boss-bar.title-boss-battle").contains("%state%"));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void customizedLegacyMessageKeysArePreservedExactly() throws Exception {
        for (String code : new String[]{"en_US", "zh_CN"}) {
            String text = """
                    # These strings are intentionally customized.
                    help:
                      title: "My title: %prefix%%b&lSpecial %version%"
                    messages:
                      pause-reason-world: "My world %world%"
                      pause-reason-residence: "My residence"
                    boss-bar:
                      pause-note-counting: "My counting policy"
                      title-boss-battle: "My Boss title %time%"
                    """;
            Path file = writeLanguage(code, text);
            byte[] original = Files.readAllBytes(file);
            LanguageManager language = language(code);
            assertEquals("My title: %prefix%%b&lSpecial %version%", language.getRaw("help.title"));
            assertEquals("My world %world%", language.getRaw("messages.pause-reason-world"));
            assertEquals("My residence", language.getRaw("messages.pause-reason-residence"));
            assertEquals("My counting policy", language.getRaw("boss-bar.pause-note-counting"));
            assertEquals("My Boss title %time%", language.getRaw("boss-bar.title-boss-battle"));
            assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    @Test void unknownCustomLanguageKeepsChineseFallbackAndDoesNotMigrateMatchingForeignStockText() throws Exception {
        String text = "help:\n  title: '  %prefix%%b&lNewbieProtect &7v%version%'\n";
        Path file = writeLanguage("custom", text);
        LanguageManager language = language("custom");
        assertEquals("&7插件已停用", language.getRaw("status.inactive"));
        assertEquals("  %prefix%%b&lNewbieProtect &7v%version%", language.getRaw("help.title"));
        assertEquals(text, Files.readString(file));
    }

}
