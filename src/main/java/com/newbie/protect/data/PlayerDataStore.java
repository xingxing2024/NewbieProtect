package com.newbie.protect.data;

import com.newbie.protect.NewbieProtect;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 新人保护数据存储。
 *
 * <p>每个玩家一条记录，记录「已消耗的保护秒数」：
 * 玩家在线时按秒累加，下线后停止累加（保护时间暂停）。
 * 累加值达到配置的总时长后，该玩家永久失去新人保护。</p>
 *
 * <p>数据结构（data.yml）：</p>
 * <pre>
 * players:
 *   &lt;uuid&gt;:
 *     name: Steve
 *     used-seconds: 1234
 *     first-join: 1730000000000
 *     finished: false
 *     self-disabled: false
 * </pre>
 *
 * <p>写入采用「防抖 + 异步」策略，避免频繁 IO。</p>
 */
public class PlayerDataStore {

    /** 一条玩家记录。 */
    public static final class Entry {
        private final UUID uuid;
        private String name;
        private long usedSeconds;
        private long firstJoin;
        private boolean finished;
        /** 玩家是否自己用 /newbie off 关闭了保护（关闭后不再免伤）。 */
        private boolean selfDisabled;
        /** 管理员是否用 /newbie pause 冻结了该玩家的计时。 */
        private boolean adminPaused;
        /** 玩家是否自己关掉了 Boss 条显示（防止一直挡屏幕）。 */
        private boolean bossBarHidden;

        Entry(UUID uuid, String name, long usedSeconds, long firstJoin, boolean finished) {
            this(uuid, name, usedSeconds, firstJoin, finished, false, false, false);
        }

        Entry(UUID uuid, String name, long usedSeconds, long firstJoin,
              boolean finished, boolean selfDisabled) {
            this(uuid, name, usedSeconds, firstJoin, finished, selfDisabled, false, false);
        }

        Entry(UUID uuid, String name, long usedSeconds, long firstJoin,
              boolean finished, boolean selfDisabled, boolean adminPaused) {
            this(uuid, name, usedSeconds, firstJoin, finished, selfDisabled, adminPaused, false);
        }

        Entry(UUID uuid, String name, long usedSeconds, long firstJoin,
              boolean finished, boolean selfDisabled, boolean adminPaused,
              boolean bossBarHidden) {
            this.uuid = uuid;
            this.name = name;
            this.usedSeconds = usedSeconds;
            this.firstJoin = firstJoin;
            this.finished = finished;
            this.selfDisabled = selfDisabled;
            this.adminPaused = adminPaused;
            this.bossBarHidden = bossBarHidden;
        }

        public UUID uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }

        public void name(String name) {
            this.name = name;
        }

        /**
         * 已消耗的保护秒数。
         *
         * <p><b>允许为负值</b>：负值表示「管理员额外赠送的时间」。
         * 例如总时长 6 小时、玩家被 {@code /newbie add 6h} 加了 6 小时，
         * 这里就是 -21600，剩余 = 21600 - (-21600) = 43200 秒（12 小时）。</p>
         */
        public long usedSeconds() {
            return usedSeconds;
        }

        /** 增加已消耗秒数（可为负，表示送时间）。 */
        public void addUsedSeconds(long seconds) {
            this.usedSeconds = this.usedSeconds + seconds;
        }

        /**
         * 直接设置已消耗秒数（允许负值 = 额外赠送的时间）。
         *
         * <p>注意：<b>不再钳到 0</b>，否则管理员加的时长会丢失。</p>
         */
        public void usedSeconds(long seconds) {
            this.usedSeconds = seconds;
        }

        /** 首次进服时间戳。 */
        public long firstJoin() {
            return firstJoin;
        }

        public void firstJoin(long millis) {
            this.firstJoin = millis;
        }

        /** 是否已用尽保护（保护结束）。 */
        public boolean finished() {
            return finished;
        }

        public void finished(boolean finished) {
            this.finished = finished;
        }

        /** 玩家是否自己关闭了保护（关闭期间不免伤，但计时按配置可能继续）。 */
        public boolean selfDisabled() {
            return selfDisabled;
        }

        public void selfDisabled(boolean selfDisabled) {
            this.selfDisabled = selfDisabled;
        }

        /** 管理员是否冻结了该玩家的计时（冻结期间不免伤也不扣时间）。 */
        public boolean adminPaused() {
            return adminPaused;
        }

        public void adminPaused(boolean adminPaused) {
            this.adminPaused = adminPaused;
        }

        /** 玩家是否自己关掉了 Boss 条显示。 */
        public boolean bossBarHidden() {
            return bossBarHidden;
        }

        public void bossBarHidden(boolean bossBarHidden) {
            this.bossBarHidden = bossBarHidden;
        }
    }

    private final NewbieProtect plugin;
    private final File file;
    private final Map<UUID, Entry> cache = new HashMap<>();
    private final Object ioLock = new Object();
    private volatile boolean dirty = false;

    public PlayerDataStore(NewbieProtect plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    /* ------------------------------------------------------------------ */
    /* 读取 / 保存                                                          */
    /* ------------------------------------------------------------------ */

    public void load() {
        synchronized (ioLock) {
            cache.clear();
            if (!file.isFile()) {
                return;
            }
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                var section = yaml.getConfigurationSection("players");
                if (section == null) {
                    return;
                }
                for (String key : section.getKeys(false)) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(key);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    // 数据校验：防止手工改坏 / 异常写入导致脏数据。
                    // 注意：used-seconds 允许为负（= 管理员额外赠送的时间），不能钳到 0，
                    // 否则重启后赠送的时长会丢失。
                    long used = section.getLong(key + ".used-seconds", 0L);
                    long firstJoin = section.getLong(key + ".first-join", 0L);
                    if (firstJoin < 0L) {
                        firstJoin = 0L;
                    }
                    cache.put(uuid, new Entry(
                            uuid,
                            section.getString(key + ".name", ""),
                            used,
                            firstJoin,
                            section.getBoolean(key + ".finished", false),
                            section.getBoolean(key + ".self-disabled", false),
                            section.getBoolean(key + ".admin-paused", false),
                            section.getBoolean(key + ".bossbar-hidden", false)
                    ));
                }
                long finished = cache.values().stream().filter(Entry::finished).count();
                plugin.getLogger().info("已载入 " + cache.size() + " 条新人保护数据"
                        + "（其中 " + finished + " 人保护已结束，"
                        + (cache.size() - finished) + " 人仍在保护/未用尽）。");
            } catch (Throwable t) {
                plugin.getLogger().warning("读取 data.yml 失败: " + t.getMessage());
            }
        }
    }

    public void save() {
        synchronized (ioLock) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                for (Entry entry : cache.values()) {
                    String base = "players." + entry.uuid();
                    yaml.set(base + ".name", entry.name());
                    yaml.set(base + ".used-seconds", entry.usedSeconds());
                    yaml.set(base + ".first-join", entry.firstJoin());
                    yaml.set(base + ".finished", entry.finished());
                    yaml.set(base + ".self-disabled", entry.selfDisabled());
                    yaml.set(base + ".admin-paused", entry.adminPaused());
                    yaml.set(base + ".bossbar-hidden", entry.bossBarHidden());
                }
                if (!plugin.getDataFolder().isDirectory() && !plugin.getDataFolder().mkdirs()) {
                    plugin.getLogger().warning("无法创建数据目录: " + plugin.getDataFolder());
                }
                yaml.save(file);
                dirty = false;
            } catch (IOException e) {
                plugin.getLogger().warning("保存 data.yml 失败: " + e.getMessage());
            } catch (Throwable t) {
                plugin.getLogger().warning("保存 data.yml 异常: " + t);
            }
        }
    }

    /** 标记有改动，等待定时任务落盘（防抖）。 */
    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /* ------------------------------------------------------------------ */
    /* 查询 / 创建                                                          */
    /* ------------------------------------------------------------------ */

    /** 取记录，不存在返回 null。 */
    public Entry get(UUID uuid) {
        synchronized (ioLock) {
            return cache.get(uuid);
        }
    }

    /** 取记录，不存在则创建（首次进服）。 */
    public Entry getOrCreate(UUID uuid, String name) {
        synchronized (ioLock) {
            Entry entry = cache.get(uuid);
            if (entry == null) {
                entry = new Entry(uuid, name, 0L, System.currentTimeMillis(), false);
                cache.put(uuid, entry);
                dirty = true;
            } else if (name != null && !name.equals(entry.name())) {
                entry.name(name);
                dirty = true;
            }
            return entry;
        }
    }

    /** 全部记录的快照（管理员 list 用）。 */
    public java.util.List<Entry> all() {
        synchronized (ioLock) {
            return new java.util.ArrayList<>(cache.values());
        }
    }

    /** 按玩家名模糊查找（管理员命令用）。 */    public Entry findByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        synchronized (ioLock) {
            for (Entry entry : cache.values()) {
                if (name.equalsIgnoreCase(entry.name())) {
                    return entry;
                }
            }
        }
        return null;
    }

    /** 清除某玩家的保护记录，使其重新变成新人。 */
    public Entry reset(UUID uuid, String name) {
        synchronized (ioLock) {
            Entry entry = new Entry(uuid, name == null ? "" : name, 0L, System.currentTimeMillis(), false);
            cache.put(uuid, entry);
            dirty = true;
            return entry;
        }
    }

    /** 删除某玩家的记录（下一个进服时重新计算）。 */
    public boolean remove(UUID uuid) {
        synchronized (ioLock) {
            boolean removed = cache.remove(uuid) != null;
            if (removed) {
                dirty = true;
            }
            return removed;
        }
    }

    public int size() {
        synchronized (ioLock) {
            return cache.size();
        }
    }
}
