package com.newbie.protect.data;

import com.newbie.protect.NewbieProtect;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

        public synchronized String name() {
            return name;
        }

        public synchronized void name(String name) {
            this.name = name;
        }

        /**
         * 已消耗的保护秒数。
         *
         * <p><b>允许为负值</b>：负值表示「管理员额外赠送的时间」。
         * 例如总时长 6 小时、玩家被 {@code /newbie add 6h} 加了 6 小时，
         * 这里就是 -21600，剩余 = 21600 - (-21600) = 43200 秒（12 小时）。</p>
         */
        public synchronized long usedSeconds() {
            return usedSeconds;
        }

        /** 增加已消耗秒数（可为负，表示送时间）。 */
        public synchronized void addUsedSeconds(long seconds) {
            this.usedSeconds = this.usedSeconds + seconds;
        }

        /**
         * 直接设置已消耗秒数（允许负值 = 额外赠送的时间）。
         *
         * <p>注意：<b>不再钳到 0</b>，否则管理员加的时长会丢失。</p>
         */
        public synchronized void usedSeconds(long seconds) {
            this.usedSeconds = seconds;
        }

        /** 首次进服时间戳。 */
        public synchronized long firstJoin() {
            return firstJoin;
        }

        public synchronized void firstJoin(long millis) {
            this.firstJoin = millis;
        }

        /** 是否已用尽保护（保护结束）。 */
        public synchronized boolean finished() {
            return finished;
        }

        public synchronized void finished(boolean finished) {
            this.finished = finished;
        }

        /** 玩家是否自己关闭了保护（关闭期间不免伤，但计时按配置可能继续）。 */
        public synchronized boolean selfDisabled() {
            return selfDisabled;
        }

        public synchronized void selfDisabled(boolean selfDisabled) {
            this.selfDisabled = selfDisabled;
        }

        /** 管理员是否冻结了该玩家的计时（冻结期间不免伤也不扣时间）。 */
        public synchronized boolean adminPaused() {
            return adminPaused;
        }

        public synchronized void adminPaused(boolean adminPaused) {
            this.adminPaused = adminPaused;
        }

        /** 玩家是否自己关掉了 Boss 条显示。 */
        public synchronized boolean bossBarHidden() {
            return bossBarHidden;
        }

        public synchronized void bossBarHidden(boolean bossBarHidden) {
            this.bossBarHidden = bossBarHidden;
        }
        private synchronized Entry snapshot() {
            return new Entry(uuid, name, usedSeconds, firstJoin, finished,
                    selfDisabled, adminPaused, bossBarHidden);
        }
    }

    private final NewbieProtect plugin;
    private final File file;
    private final Map<UUID, Entry> cache = new HashMap<>();
    private final Object cacheLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicLong revision = new AtomicLong();
    private volatile long savedRevision;
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private final AtomicBoolean saveRequested = new AtomicBoolean();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "NewbieProtect-DataWriter");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closing;
    private volatile boolean loadHealthy = true;
    private final AtomicYamlFile yamlFile;

    public PlayerDataStore(NewbieProtect plugin) {
        this(plugin, new AtomicYamlFile(new File(plugin.getDataFolder(), "data.yml").toPath()));
    }

    PlayerDataStore(NewbieProtect plugin, AtomicYamlFile yamlFile) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.yamlFile = yamlFile;
    }

    /* ------------------------------------------------------------------ */
    /* 读取 / 保存                                                          */
    /* ------------------------------------------------------------------ */

    /** A failed read disables every disk write until an explicit successful load. */
    public boolean isLoadHealthy() {
        return loadHealthy;
    }

    public void load() {
        long startingRevision = revision.get();
        // Same lock order as save: a queued snapshot cannot overwrite a newly loaded file.
        synchronized (writeLock) {
            synchronized (cacheLock) {
                try {
                    Map<UUID, Entry> loaded = new HashMap<>();
                    if (!Files.notExists(file.toPath())) {
                        String contents = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                        // Unlike loadConfiguration, this API propagates parse errors.
                        new YamlConfiguration().loadFromString(contents);
                        // Inspect raw values too: Bukkit normalizes nulls away and coerces getters.
                        // Reject duplicate keys rather than silently discarding an earlier record.
                        LoaderOptions options = new LoaderOptions();
                        options.setAllowDuplicateKeys(false);
                        Object document = new Yaml(new SafeConstructor(options)).load(contents);
                        if (document != null) {
                            if (!(document instanceof Map<?, ?> root)) {
                                throw new IllegalArgumentException("data.yml root must be a mapping");
                            }
                            Object players = root.get("players");
                            if (players == null && !root.isEmpty()) {
                                throw new IllegalArgumentException("players must be a mapping");
                            }
                            if (players != null) {
                                if (!(players instanceof Map<?, ?> records)) {
                                    throw new IllegalArgumentException("players must be a mapping");
                                }
                                for (var record : records.entrySet()) {
                                    String key = String.valueOf(record.getKey());
                                    UUID uuid = UUID.fromString(key);
                                    if (!uuid.toString().equalsIgnoreCase(key) || loaded.containsKey(uuid)) {
                                        throw new IllegalArgumentException("Invalid or duplicate player UUID: " + key);
                                    }
                                    if (!(record.getValue() instanceof Map<?, ?> fields)) {
                                        throw new IllegalArgumentException("Player entry must be a mapping: " + key);
                                    }
                                    long used = readLong(fields, "used-seconds");
                                    long firstJoin = readLong(fields, "first-join");
                                    if (firstJoin < 0) throw new IllegalArgumentException("Negative first-join: " + key);
                                    Object name = fields.get("name");
                                    if (fields.containsKey("name") && !(name instanceof String)) {
                                        throw new IllegalArgumentException("Player name must be a string: " + key);
                                    }
                                    loaded.put(uuid, new Entry(uuid, name == null ? "" : (String) name,
                                            used, firstJoin, readBoolean(fields, "finished"),
                                            readBoolean(fields, "self-disabled"), readBoolean(fields, "admin-paused"),
                                            readBoolean(fields, "bossbar-hidden")));
                                }
                            }
                        }
                    } else if (!cache.isEmpty()) {
                        throw new IOException("data.yml disappeared while player records remain in memory");
                    }
                    // Entry callers may mark a mutation while I/O is in progress. Never discard it.
                    if (revision.get() != startingRevision) {
                        throw new IllegalStateException("Player data changed during load; retry while quiescent");
                    }
                    cache.clear();
                    cache.putAll(loaded);
                    savedRevision = startingRevision;
                    loadHealthy = true;
                    long finished = loaded.values().stream().filter(Entry::finished).count();
                    String loadedMessage = plugin.langText("console.data-loaded", "Loaded %count% protection records (%finished% finished, %active% active).",
                            "%count%", String.valueOf(loaded.size()), "%finished%", String.valueOf(finished),
                            "%active%", String.valueOf(loaded.size() - finished));
                    plugin.getLogger().info(loadedMessage == null ? "Loaded " + loaded.size() + " protection records." : loadedMessage);
                } catch (Exception e) {
                    loadHealthy = false;
                    plugin.getLogger().severe("读取 data.yml 失败，保留原缓存并禁止保存；修复文件后需成功重新加载: " + e.getMessage());
                }
            }
        }
    }

    private static long readLong(Map<?, ?> fields, String key) {
        if (!fields.containsKey(key)) return 0L;
        Object value = fields.get(key);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException(key + " must be a signed 64-bit integer");
        }
        return ((Number) value).longValue();
    }

    private static boolean readBoolean(Map<?, ?> fields, String key) {
        if (!fields.containsKey(key)) return false;
        Object value = fields.get(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " must be boolean");
        return (Boolean) value;
    }

    /** 同步保存，供关服和需要明确落盘的调用方使用。 */
    public void save() {
        synchronized (writeLock) {
            if (!loadHealthy) return;
            // 在快照前读取版本；之后发生的改动不会被本次保存错误清除。
            long snapshotRevision = revision.get();
            List<Entry> snapshot;
            synchronized (cacheLock) {
                snapshot = cache.values().stream().map(Entry::snapshot).toList();
            }
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                for (Entry entry : snapshot) {
                    String base = "players." + entry.uuid();
                    yaml.set(base + ".name", entry.name());
                    yaml.set(base + ".used-seconds", entry.usedSeconds());
                    yaml.set(base + ".first-join", entry.firstJoin());
                    yaml.set(base + ".finished", entry.finished());
                    yaml.set(base + ".self-disabled", entry.selfDisabled());
                    yaml.set(base + ".admin-paused", entry.adminPaused());
                    yaml.set(base + ".bossbar-hidden", entry.bossBarHidden());
                }
                yamlFile.write(yaml);
                savedRevision = snapshotRevision;
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("保存 data.yml 失败（保留待保存状态）: " + e.getMessage());
            }
        }
    }

    /** 合并后台保存请求；序列化和磁盘 I/O 不持有玩家缓存锁。 */
    public void requestSave() {
        markDirty();
        if (!loadHealthy) return;
        saveRequested.set(true);
        scheduleWriter();
    }

    private void scheduleWriter() {
        if (closing || !saveQueued.compareAndSet(false, true)) return;
        try {
            writer.execute(() -> {
                try {
                    while (saveRequested.getAndSet(false)) {
                        save();
                    }
                } finally {
                    saveQueued.set(false);
                    // 补上最后一次检查与清理排队标记之间到达的请求。
                    if (saveRequested.get()) scheduleWriter();
                }
            });
        } catch (RejectedExecutionException e) {
            saveQueued.set(false);
            if (!closing) plugin.getLogger().warning("无法安排数据保存: " + e.getMessage());
        }
    }

    /** 停止接受后台请求，等待现有写入，再同步保存最新状态。可重复调用。 */
    public void close() {
        closing = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("数据写入尚未结束，等待最终保存。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        save();
    }

    public void markDirty() {
        revision.incrementAndGet();
    }

    public boolean isDirty() {
        return revision.get() != savedRevision;
    }

    /* ------------------------------------------------------------------ */
    /* 查询 / 创建                                                          */
    /* ------------------------------------------------------------------ */

    /** 取记录，不存在返回 null。 */
    public Entry get(UUID uuid) {
        synchronized (cacheLock) {
            return cache.get(uuid);
        }
    }

    /** 取记录，不存在则创建（首次进服）。 */
    public Entry getOrCreate(UUID uuid, String name) {
        synchronized (cacheLock) {
            Entry entry = cache.get(uuid);
            if (entry == null) {
                entry = new Entry(uuid, name, 0L, System.currentTimeMillis(), false);
                cache.put(uuid, entry);
                markDirty();
            } else if (name != null && !name.equals(entry.name())) {
                entry.name(name);
                markDirty();
            }
            return entry;
        }
    }

    /** 全部记录的快照（管理员 list 用）。 */
    public java.util.List<Entry> all() {
        synchronized (cacheLock) {
            return new java.util.ArrayList<>(cache.values());
        }
    }

    /** 按玩家名模糊查找（管理员命令用）。 */    public Entry findByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        synchronized (cacheLock) {
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
        synchronized (cacheLock) {
            Entry entry = new Entry(uuid, name == null ? "" : name, 0L, System.currentTimeMillis(), false);
            cache.put(uuid, entry);
            markDirty();
            return entry;
        }
    }

    /** 删除某玩家的记录（下一个进服时重新计算）。 */
    public boolean remove(UUID uuid) {
        synchronized (cacheLock) {
            boolean removed = cache.remove(uuid) != null;
            if (removed) {
                markDirty();
            }
            return removed;
        }
    }

    public int size() {
        synchronized (cacheLock) {
            return cache.size();
        }
    }
}
