/**
 * NewbieProtect - 新人保护插件
 */
package com.newbie.protect.config;

import com.newbie.protect.NewbieProtect;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * config.yml 自动查漏补缺。
 *
 * <p>插件更新后新增的配置项（例如 {@code boss-battle} / {@code residence} /
 * {@code world-list}），如果玩家旧的 config.yml 里没有，
 * 会在启动 / {@code /newbie reload} 时自动补进文件，
 * <b>不覆盖已有值，也保留原有中文注释</b>。</p>
 *
 * <p>实现方式：纯文本、按缩进解析 YAML 结构，只把默认配置里缺失的键
 * （连同其注释与子项）<b>插入到正确位置</b>。
 * 不依赖服务端自带的 SnakeYAML，因此不受其版本影响。</p>
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    /** 一个键节点：路径、缩进、块起止行、该行冒号后的值。 */
    private static final class Entry {
        final String path;
        final int indent;
        final int start;
        final String value;
        int end;

        Entry(String path, int indent, int start, String value) {
            this.path = path;
            this.indent = indent;
            this.start = start;
            this.value = value;
        }
    }

    private static final class Parsed {
        final List<Entry> list = new ArrayList<>();
        final Map<String, Entry> map = new LinkedHashMap<>();
    }

    /**
     * 补全 config.yml 中缺失的默认项。已存在的键不会被修改。
     *
     * @return 补写的键数量（0 表示无需补全）
     */
    public static int update(NewbieProtect plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.isFile()) {
            return 0;
        }
        String defaultText = readResource(plugin, "config.yml");
        if (defaultText == null || defaultText.isEmpty()) {
            plugin.getLogger().warning("无法读取 jar 内的默认 config.yml，跳过配置补全。");
            return 0;
        }
        try {
            String userText = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (userText.trim().isEmpty()) {
                Files.write(file.toPath(), defaultText.getBytes(StandardCharsets.UTF_8));
                plugin.getLogger().info("config.yml 为空，已生成完整默认配置。");
                return -1;
            }

            String eol = userText.contains("\r\n") ? "\r\n" : "\n";
            List<String> defLines = split(defaultText);
            Set<String> skipped = new HashSet<>();
            List<String> addedPaths = new ArrayList<>();
            boolean changed = false;

            for (int guard = 0; guard < 500; guard++) {
                Parsed user = parse(split(userText));
                Parsed defaults = parse(defLines);

                Entry chosen = null;
                for (Entry d : defaults.list) {
                    if (user.map.containsKey(d.path) || skipped.contains(d.path)) {
                        continue;
                    }
                    String parentPath = parent(d.path);
                    if (!parentPath.isEmpty()) {
                        Entry parentEntry = user.map.get(parentPath);
                        if (parentEntry == null) {
                            // 父节点本身也缺，会在更早的位置被处理，这里先跳过
                            continue;
                        }
                        if (!parentEntry.value.isEmpty()) {
                            // 父节点是标量 / 行内值，无法往里插子项
                            skipped.add(d.path);
                            continue;
                        }
                    }
                    chosen = d;
                    break;
                }
                if (chosen == null) {
                    break;
                }

                String parentPath = parent(chosen.path);
                Entry parentEntry = parentPath.isEmpty() ? null : user.map.get(parentPath);
                int targetIndent = targetIndent(user, parentEntry);
                int delta = targetIndent - chosen.indent;

                List<String> userLines = split(userText);
                int insertAt = parentEntry == null
                        ? userLines.size()
                        : Math.min(parentEntry.end, userLines.size());

                List<String> block = new ArrayList<>();
                for (int i = chosen.start; i < chosen.end && i < defLines.size(); i++) {
                    block.add(reindent(defLines.get(i), delta));
                }
                // 顶级新增项之间留一个空行，排版好看
                if (parentEntry == null && !userLines.isEmpty()
                        && !userLines.get(userLines.size() - 1).trim().isEmpty()) {
                    block.add(0, "");
                }
                userLines.addAll(insertAt, block);
                userText = join(userLines, eol);
                changed = true;
                addedPaths.add(chosen.path);
            }

            if (changed) {
                // 写回前校验 YAML 合法性，避免写坏配置
                if (!isValidYaml(userText)) {
                    plugin.getLogger().severe("补全后的 config.yml 无法解析，已放弃写入（文件保持原样）。"
                            + "请删除 config.yml 后执行 /newbie reload 重新生成。");
                    return 0;
                }
                Files.write(file.toPath(), userText.getBytes(StandardCharsets.UTF_8));
                plugin.getLogger().info("已自动补全 config.yml 中缺失的 "
                        + addedPaths.size() + " 项配置（保留原有内容与注释）：");
                for (String p : addedPaths) {
                    plugin.getLogger().info("   + " + p);
                }
            }
            return addedPaths.size();
        } catch (Throwable t) {
            plugin.getLogger().warning("自动补全 config.yml 失败，已忽略：" + t);
            return 0;
        }
    }

    /** 用 Bukkit 的解析器校验 YAML 是否合法。 */
    private static boolean isValidYaml(String text) {
        try {
            org.bukkit.configuration.file.YamlConfiguration yaml =
                    new org.bukkit.configuration.file.YamlConfiguration();
            yaml.loadFromString(text);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int targetIndent(Parsed user, Entry parentEntry) {
        if (parentEntry == null) {
            for (Entry e : user.list) {
                if (parent(e.path).isEmpty()) {
                    return e.indent;
                }
            }
            return 0;
        }
        String prefix = parentEntry.path + ".";
        int min = Integer.MAX_VALUE;
        for (Entry e : user.list) {
            if (e.path.startsWith(prefix)) {
                String rest = e.path.substring(prefix.length());
                if (!rest.contains(".") && e.indent < min) {
                    min = e.indent;
                }
            }
        }
        return min != Integer.MAX_VALUE ? min : parentEntry.indent + 2;
    }

    private static String reindent(String line, int delta) {
        if (line == null) {
            return "";
        }
        if (line.trim().isEmpty()) {
            return "";
        }
        int leading = 0;
        while (leading < line.length() && line.charAt(leading) == ' ') {
            leading++;
        }
        int newLeading = Math.max(0, leading + delta);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < newLeading; i++) {
            sb.append(' ');
        }
        sb.append(line.substring(leading));
        return sb.toString();
    }

    private static Parsed parse(List<String> lines) {
        Parsed parsed = new Parsed();
        List<int[]> keyLines = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        Deque<Integer> indentStack = new ArrayDeque<>();
        Deque<String> keyStack = new ArrayDeque<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            if (indent >= line.length()) {
                continue;
            }
            int colon = line.indexOf(':', indent);
            if (colon < 0) {
                continue;
            }
            String key = line.substring(indent, colon).trim();
            if (key.isEmpty() || key.startsWith("#")) {
                continue;
            }

            while (!indentStack.isEmpty() && indentStack.peek() >= indent) {
                indentStack.pop();
                keyStack.pop();
            }
            indentStack.push(indent);
            keyStack.push(key);

            StringBuilder path = new StringBuilder();
            Object[] stackArray = keyStack.toArray();
            for (int a = stackArray.length - 1; a >= 0; a--) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(stackArray[a]);
            }
            keyLines.add(new int[]{i, indent});
            paths.add(path.toString());
        }

        int total = lines.size();
        for (int k = 0; k < keyLines.size(); k++) {
            int index = keyLines.get(k)[0];
            int indent = keyLines.get(k)[1];
            int start = headerStart(lines, index);
            int end = total;
            for (int j = k + 1; j < keyLines.size(); j++) {
                if (keyLines.get(j)[1] <= indent) {
                    end = headerStart(lines, keyLines.get(j)[0]);
                    break;
                }
            }
            String line = lines.get(index);
            int colon = line.indexOf(':', indent);
            String value = colon < 0 ? "" : line.substring(colon + 1).trim();
            Entry entry = new Entry(paths.get(k), indent, start, value);
            entry.end = end;
            parsed.list.add(entry);
            parsed.map.put(entry.path, entry);
        }
        return parsed;
    }

    private static int headerStart(List<String> lines, int keyIndex) {
        int i = keyIndex;
        while (i - 1 >= 0) {
            String previous = lines.get(i - 1).trim();
            if (previous.isEmpty() || previous.startsWith("#")) {
                i--;
            } else {
                break;
            }
        }
        return i;
    }

    private static List<String> split(String text) {
        List<String> list = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                int end = i;
                if (end > start && text.charAt(end - 1) == '\r') {
                    end--;
                }
                list.add(text.substring(start, end));
                start = i + 1;
            }
        }
        list.add(text.substring(start));
        return list;
    }

    private static String join(List<String> lines, String eol) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(eol);
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static String parent(String path) {
        int index = path.lastIndexOf('.');
        return index < 0 ? "" : path.substring(0, index);
    }

    private static String readResource(NewbieProtect plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
