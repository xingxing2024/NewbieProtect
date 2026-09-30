/**
 * NewbieProtect - 新人保护插件
 */
package com.newbie.protect.config;

import com.newbie.protect.NewbieProtect;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 语言管理器。
 *
 * <p>语言文件放在 {@code plugins/NewbieProtect/lang/} 下，
 * 命名格式 {@code <语言码>.yml}（如 {@code zh_CN.yml} / {@code en_US.yml}）。</p>
 *
 * <p>在 config.yml 里用 {@code language: zh_CN} 切换语言。</p>
 *
 * <p>行为：</p>
 * <ul>
 *     <li>首次启动时自动释放 jar 内置的语言文件到 lang/ 目录</li>
 *     <li>用户可自由修改 lang/ 里的文本</li>
 *     <li>找不到目标语言时自动回退到 {@link #FALLBACK_LANG}</li>
 *     <li>某个 key 缺失时也会回退到 fallback 语言，再不行用代码里的默认值</li>
 * </ul>
 */
public final class LanguageManager {

    /** 兜底语言（找不到指定语言时用这个）。 */
    public static final String FALLBACK_LANG = "zh_CN";

    /** 内置支持的语言（会在启动时释放到 lang/ 目录）。 */
    public static final String[] BUNDLED_LANGS = {"zh_CN", "en_US"};

    private final NewbieProtect plugin;
    private final File langDir;

    /** 当前生效的语言配置。 */
    private YamlConfiguration current;
    /** 兜底语言配置。 */
    private YamlConfiguration fallback;
    /** 内置默认（从 jar 读取，永远可用）。 */
    private YamlConfiguration builtinDefault;

    private String currentCode = FALLBACK_LANG;

    public LanguageManager(NewbieProtect plugin) {
        this.plugin = plugin;
        this.langDir = new File(plugin.getDataFolder(), "lang");
    }

    /* ------------------------------------------------------------------ */
    /* 加载                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 释放内置语言文件（已存在的不覆盖，保留用户修改）。
     */
    public void releaseBundled() {
        if (!langDir.isDirectory() && !langDir.mkdirs()) {
            plugin.getLogger().warning("无法创建语言目录: " + langDir);
            return;
        }
        for (String code : BUNDLED_LANGS) {
            File target = new File(langDir, code + ".yml");
            if (target.isFile()) {
                continue;
            }
            try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
                if (in == null) {
                    continue;
                }
                java.nio.file.Files.copy(in, target.toPath());
                plugin.getLogger().info("已释放内置语言文件: lang/" + code + ".yml");
            } catch (Throwable t) {
                plugin.getLogger().warning("释放语言文件 " + code + " 失败: " + t.getMessage());
            }
        }
    }

    /**
     * 加载语言（按 config.yml 的 language 项）。
     *
     * @return 实际生效的语言码
     */
    public String load() {
        releaseBundled();

        // 内置默认（jar 内 zh_CN），永远可用作最后兜底
        builtinDefault = readBundled(FALLBACK_LANG);

        // 兜底语言：优先用 lang/ 目录里的，没有就用 jar 内置
        fallback = readFromDir(FALLBACK_LANG);
        if (fallback == null) {
            fallback = builtinDefault;
        }

        // 目标语言
        String want = plugin.getConfig().getString("language", FALLBACK_LANG);
        if (want == null || want.isBlank()) {
            want = FALLBACK_LANG;
        }
        want = want.trim();

        YamlConfiguration loaded = readFromDir(want);
        if (loaded == null) {
            if (!want.equalsIgnoreCase(FALLBACK_LANG)) {
                plugin.getLogger().warning("未找到语言文件 lang/" + want + ".yml，"
                        + "已回退到 " + FALLBACK_LANG + "。");
            }
            loaded = fallback;
            want = FALLBACK_LANG;
        }

        this.current = loaded;
        this.currentCode = want;
        plugin.getLogger().info("已加载语言: " + getLanguageName() + " (" + want + ")");
        return want;
    }

    private YamlConfiguration readFromDir(String code) {
        File f = new File(langDir, code + ".yml");
        if (!f.isFile()) {
            return null;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(f);
            return yaml;
        } catch (Throwable t) {
            plugin.getLogger().warning("读取语言文件 " + f.getName() + " 失败: " + t.getMessage());
            return null;
        }
    }

    private YamlConfiguration readBundled(String code) {
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in == null) {
                return null;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                yaml.load(reader);
            }
            return yaml;
        } catch (Throwable t) {
            plugin.getLogger().warning("读取内置语言 " + code + " 失败: " + t.getMessage());
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 取值                                                                */
    /* ------------------------------------------------------------------ */

    /** 当前语言码，如 zh_CN。 */
    public String getCurrentCode() {
        return currentCode;
    }

    /** 当前语言显示名，如 简体中文。 */
    public String getLanguageName() {
        String name = getRaw("language.name");
        return name == null || name.isBlank() ? currentCode : name;
    }

    /**
     * 取原始文本（未做颜色转换），按 当前语言 → 兜底语言 → 内置 → 默认值 的顺序。
     *
     * @param key          配置路径，如 {@code messages.query-self}
     * @param defaultValue 全都找不到时返回的值（可为 null）
     */
    public String getRaw(String key, String defaultValue) {
        String v = lookup(current, key);
        if (v == null) {
            v = lookup(fallback, key);
        }
        if (v == null) {
            v = lookup(builtinDefault, key);
        }
        return v == null ? defaultValue : v;
    }

    public String getRaw(String key) {
        return getRaw(key, null);
    }

    private static String lookup(YamlConfiguration yaml, String key) {
        if (yaml == null || key == null) {
            return null;
        }
        try {
            return yaml.getString(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 取文本并替换占位符 + 转换颜色代码。
     *
     * @param key          配置路径
     * @param defaultValue 找不到时的默认值
     * @param placeholders 成对传入：占位符, 值
     */
    public String get(String key, String defaultValue, String... placeholders) {
        String text = getRaw(key, defaultValue);
        if (text == null) {
            return "";
        }
        String[] vars = placeholders == null ? new String[0] : placeholders;
        for (int i = 0; i + 1 < vars.length; i += 2) {
            if (vars[i] != null && vars[i + 1] != null) {
                text = text.replace(vars[i], vars[i + 1]);
            }
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public String get(String key, String defaultValue) {
        return get(key, defaultValue, new String[0]);
    }

    /**
     * 取文本但**不转换颜色**（用于需要自己做占位符替换的场景，如 Boss 条标题）。
     */
    public String getRawWith(String key, String defaultValue, String... placeholders) {
        String text = getRaw(key, defaultValue);
        if (text == null) {
            return "";
        }
        String[] vars = placeholders == null ? new String[0] : placeholders;
        for (int i = 0; i + 1 < vars.length; i += 2) {
            if (vars[i] != null && vars[i + 1] != null) {
                text = text.replace(vars[i], vars[i + 1]);
            }
        }
        return text;
    }

    /** 语言文件目录。 */
    public File getLangDir() {
        return langDir;
    }

    /** 列出已安装的语言（lang/ 目录里的 .yml）。 */
    public java.util.List<String> listInstalled() {
        java.util.List<String> out = new java.util.ArrayList<>();
        File[] files = langDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.toLowerCase(Locale.ROOT).endsWith(".yml")) {
                    out.add(n.substring(0, n.length() - 4));
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }
}
