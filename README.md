# NewbieProtect —— 新人保护插件

首次进入服务器的玩家，在**累计在线时长**用满之前，免受**怪物伤害**与**玩家 PVP**。

适用 Paper / Purpur / Folia / Leaf 26.x（`api-version: 1.13`，Java 21+）。

---

## 功能一览

| 功能 | 说明 |
| --- | --- |
| 🛡 防怪物伤害 | 僵尸 / 骷髅 / 苦力怕 / 蜘蛛等所有 `LivingEntity` 的伤害 |
| 🏹 防投射物 | 箭 / 三叉戟 / 雪碧球等（含怪物射出的） |
| ⚔ 防玩家 PVP | 其他玩家造成的伤害一律拦截 |
| 👀 怪物不主动攻击 | 保护期内怪物完全不锁定新人（可关） |
| ⏱ 只算在线时长 | 玩家下线后**保护时间暂停**，不消耗 |
| 🔀 玩家自助开关 | `/newbie off` 自己关掉保护、`/newbie on` 重新开启 |
| 🌍 世界暂停计时 | 指定世界里暂停计时（主城/大厅/资源世界），支持按世界名或世界类型 |
| 🏠 领地暂停计时 | 站在自己的 **Residence** 领地里暂停计时（软依赖，没装自动跳过） |
| ⏸ 暂停时免伤可选 | 暂停区域里「是否仍免伤」可配置（默认暂停计时且取消免伤） |
| 📊 Boss 条倒计时 | 屏幕上方实时显示剩余保护时间，**归零自动消失** |
| 💬 Boss 条带提示 | 标题里直接写明「输入 /newbie off 可关闭」，暂停时显示暂停原因 |
| 🔔 到期提醒 | 可配置档位（默认剩 5 分钟 / 1 分钟 / 10 秒时各提醒一次） |
| 💾 数据持久化 | `data.yml` 保存，重启不丢；玩家退服立刻落盘 |
| 🔧 配置自动生成 | `config.yml` 误删后 `/newbie reload` 自动恢复默认 |
| 🧩 配置自动补全 | 升级插件后 `/newbie reload` 会**自动补齐缺失的新配置项**，保留你原有设置 |
| ⌨ `/newbie` 命令 | 查询自己的保护剩余时间 |
| 🧹 安全关闭 | 关服/强关/卸载插件时自动清理 Boss 条并落盘，**不会出现「重启后无敌」** |
| 🧯 双重兜底 | 计时任务异常不会中断；超过 `max-absolute-days` 强制失效 |

---

## 快速开始

1. 把 `NewbieProtect-1.0.4.jar` 放进 `plugins/`
2. 启动服务器 —— 会自动生成 `plugins/NewbieProtect/config.yml` 与 `data.yml`
3. 按需修改 `config.yml`，执行 `/newbie reload`
4. 升级插件时：直接换 jar 后 `/newbie reload`，新配置项会**自动补全**到你的 `config.yml`

---

## 命令

### 玩家命令（所有人可用）

| 命令 | 说明 |
| --- | --- |
| `/newbie` | 查看自己的剩余保护时间 |
| `/newbie off` | **自己关闭**新人保护（不再免伤，时间照常计算） |
| `/newbie on` | **自己重新开启**新人保护 |
| `/newbie toggle` | 在开 / 关之间来回切换 |
| `/newbie help` | 查看用法（彩色界面） |

### 管理员命令（`newbieprotect.admin`）

| 命令 | 说明 |
| --- | --- |
| `/newbie info <玩家>` | 查看详情（剩余/总时长/已消耗/状态/进度条） |
| `/newbie set <玩家> <时间>` | **设置剩余时间**（如 `30m`、`1h30m`、`0s` = 立即结束） |
| `/newbie add <玩家> <时间>` | **增加**剩余时间 |
| `/newbie take <玩家> <时间>` | **减少**剩余时间 |
| `/newbie pause <玩家>` | **冻结计时**（停止倒计时，同时不免伤） |
| `/newbie resume <玩家>` | 恢复计时 |
| `/newbie open <玩家>` | 强制**开启**保护（过期也能重新给） |
| `/newbie close <玩家>` | 强制**关闭**保护（不再免伤） |
| `/newbie clear <玩家>` | **清空记录**（下次进服重新算新人） |
| `/newbie grant <玩家>` | 重置为满时长保护 |
| `/newbie list [页码]` | 列出所有记录（分页，含状态） |
| `/newbie cleanup` | 应急：清理全部 Boss 条并保存 |
| `/newbie reload` | 重载配置（自动补全缺失项） |

### 时间写法

`set` / `add` / `take` 的时间参数支持多种写法：

| 写法 | 含义 | 等于 |
| --- | --- | --- |
| `90s` | 90 秒 | 90 秒 |
| `45m` | 45 分钟 | 2700 秒 |
| `2h` | 2 小时 | 7200 秒 |
| `1h30m` | 1 小时 30 分 | 5400 秒 |
| `1d` | 1 天 | 86400 秒 |
| `1d2h3m4s` | 组合写法 | 93784 秒 |
| `300` | 纯数字 = 秒 | 300 秒 |
| `1时30分` | 中文单位也认 | 5400 秒 |

写法错了会提示正确格式，不会静默失败。

### ⭐ 时长不是上限，可以任意加

`protection.duration-minutes` 是**新人默认时长**，**不是硬上限**。

给某个玩家单独加时间（例如默认 6 小时 → 给 VIP 12 小时）：

```
/newbie set VIP玩家 12h        # 直接设成 12 小时
/newbie add VIP玩家 6h         # 在现有基础上 +6 小时
/newbie add VIP玩家 1d         # 再 +1 天
```

**实测**（默认 6 小时环境）：

| 操作 | 剩余时间 |
| --- | --- |
| 初始 | 6时0分0秒 |
| `set 12h` | **12时0分0秒** ✓ |
| `add 6h` | **18时0分0秒** ✓ |
| 再 `add 6h` | **24时0分0秒** ✓ |
| `take 12h` | 12时0分0秒 ✓ |

无限累加，不会被卡住。

`/newbie info` 会显示额外赠送的部分：

```
[新人保护] 玩家 Steve
  剩余时间   12时0分0秒 (100.0%)
  默认时长   6时0分0秒
  额外赠送   +6时0分0秒      ← 管理员加的
  已消耗     0秒
  状态       保护中
```

### 🔒 管理员操作上限（可选）

防止管理员手滑把关打到 999 天。**默认关闭，不限时长**。

```yaml
protection:
  admin-limit:
    # 是否启用上限
    enabled: false        # false = 不限制

    # 允许的最大剩余时长（秒）
    #   -1 = 不限制（等同于 enabled: false）
    max-seconds: -1
```

**常用换算**：

| 想要的上限 | 填 |
| --- | --- |
| 1 小时 | `3600` |
| 6 小时 | `21600` |
| 1 天 | `86400` |
| 7 天 | `604800` |
| 30 天 | `2592000` |
| 不限 | `-1` |

**启用后的行为**：

| 操作 | 结果 |
| --- | --- |
| `set 玩家 24h`（上限 24h） | ✅ 允许，剩余 24 小时 |
| `set 玩家 48h`（上限 24h） | ❌ 拒绝，提示「超过上限！单次最多只能设置 24时0分0秒」 |
| `add 玩家 30m`（结果在上限内） | ✅ 允许 |
| `add 玩家 10h`（结果超上限） | ❌ 拒绝，时间不变 |
| `grant 玩家`（默认时长超上限） | 自动裁剪到上限 |

越权尝试会**记录到控制台**：

```
[NewbieProtect] 管理员 Steve 尝试给 Alex 设置 48时0分0秒，超过上限 24时0分0秒，已拒绝。
```

超过上限时的提示文案可在 `messages.admin-limit-exceeded` 自定义。

### help 界面预览

```
&m                                                  &m
  新人保护 v1.0.4
  新人入服后一段时间内免受怪物与玩家伤害

  玩家命令
  /newbie » 查看自己的剩余保护时间
  /newbie off » 关闭保护 (不再免伤，时间照常计算)
  /newbie on » 重新开启保护
  /newbie toggle » 在开 / 关之间切换

  管理员命令
  /newbie info <玩家> » 查看详情
  /newbie set <玩家> <时间> » 设置剩余时间
  /newbie add <玩家> <时间> » 增加时间
  /newbie take <玩家> <时间> » 减少时间
  /newbie pause <玩家> » 冻结计时 (停表)
  /newbie resume <玩家> » 恢复计时
  /newbie open <玩家> » 强制开启保护
  /newbie close <玩家> » 强制关闭保护
  /newbie clear <玩家> » 清空记录 (重新变新人)
  /newbie grant <玩家> » 重置为满时长
  /newbie list [页码] » 列出所有记录
  /newbie cleanup » 应急清理 Boss 条
  /newbie reload » 重载配置

  时间写法：1h30m / 45m / 90s / 2d / 1时30分
  别名：/nb / /newb
&m                                                  &m
```

### `/newbie info` 效果

```
                                                  (分隔线)
[新人保护] 玩家 Steve
  剩余时间   1时23分45秒 (69.8%)
  保护总时长 2时0分0秒
  已消耗     36分15秒
  状态       保护中
  首次进服   2026-09-29 19:20:36
  进度       |||||||||&7|||||
                                                  (分隔线)
```

### Tab 补全

- 子命令、在线玩家名都能补全
- `set`/`add`/`take` 的第 3 个参数会提示 `30m` / `1h` / `2h` / `1h30m` / `10s`

别名：`/nb`、`/newb`

---

## 暂停计时（世界 / 领地）

在特定区域里**暂停保护计时**，让新人挂机不被扣时间。

### 两种暂停来源

| 来源 | 触发条件 | 需要什么 |
| --- | --- | --- |
| 🌍 世界列表 | 玩家所在世界命中配置 | 无（内置功能） |
| 🏠 自己的领地 | 玩家站在**自己名下**的 Residence 领地内 | 服务器安装 Residence |

### 世界列表配置

```yaml
world-list:
  enabled: true
  mode: allow              # allow=只有列表内世界暂停 / deny=列表内世界除外
  worlds: [lobby, spawn]   # 按世界名（不区分大小写）
  world-types: [NETHER]    # 按世界类型：NORMAL/NETHER/THE_END/CUSTOM
  count-while-in-paused-world: false   # false=暂停不扣时间（推荐）
  protect-in-paused-world: false       # false=暂停时也取消免伤
```

**两种写法怎么选：**

| 需求 | 写法 |
| --- | --- |
| 只在主城/大厅暂停 | `worlds: [hub, lobby]` |
| 所有下界世界都暂停 | `world-types: [NETHER]` |
| **除**资源世界外都暂停 | `mode: deny` + `worlds: [resource_world]` |

> 世界名以 `Bukkit.getWorlds()` 为准。默认世界通常叫 `world`，
> 下界是 `world_nether`，末地是 `world_the_end`。

### 领地配置（Residence）

```yaml
residence:
  enabled: true
  owner-only: true                   # true=只有房主自己享受暂停（推荐）
  count-while-inside: false          # false=在自己家不扣时间（推荐）
  protect-in-own-residence: false    # false=暂停时也取消免伤
```

**软依赖**：没装 Residence 时插件**自动跳过**领地判断，不会报错；
装了才生效。接入用反射完成，Residence 升级后方法签名变化也只是退化为不生效。

### 暂停时还保护吗？

由 `protect-in-*` 控制：

| 取值 | 效果 | 适用场景 |
| --- | --- | --- |
| `false`（默认） | 暂停计时 **+ 取消免伤** | 严格：只有真正在玩（计时中）的世界才受保护，主城挂机不算 |
| `true` | 暂停计时 **但保留免伤** | 宽松：在大厅里挂机也不会被怪打 |

### Boss 条会显示暂停原因

处于暂停区域时，Boss 条自动变成暂停样式（黄色）：

```
(世界 world_the_end 内不计时) | 剩余 1时59分30秒 | (此处保护计时已暂停)
```

暂停原因文案可在 `messages.pause-reason-world` / `messages.pause-reason-residence` 自定义。

---

## 配置自动补全（升级无忧）

插件升级后新增的配置项，会在 `/newbie reload`（或重启）时**自动补写到你的 `config.yml`**。

**关键：补写采用「末尾追加」方式，你原有的配置和注释 100% 保留**
（早期版本用 `YamlConfiguration.save()` 整体重写，会把注释全抹掉 —— 已修复）。

补写的内容带清晰的分隔标记：

```yaml
# ... 你原来的配置和注释，完全不动 ...

# ===================================================
# 以下配置项由插件自动补全（本次升级新增）
# 想查看带完整注释的版本，可删除本文件后 /newbie reload 重新生成
# ===================================================
config-version: 2
protection.max-absolute-days: 30
boss-battle:
  enabled: true
  pause-timer-in-battle: true
  protect-in-battle: false
  battle-timeout-seconds: 15
  builtin-bosses:
    - ENDER_DRAGON
    - WITHER
    - WARDEN
```

控制台会打印补了多少项：

```
[NewbieProtect] 已自动补全 12 项配置（追加在 config.yml 末尾，你原有的设置和注释都保留了）。
```

> 如果 `config.yml` 被整个删掉，插件会自动重新生成完整的默认配置（带全部注释）。

### 关于 `boss-battle.builtin-bosses`

这项**缺失时不会写空列表** —— 插件内置了默认名单（末影龙/凋灵/监守者等），
即使你的配置里完全没有这一段，「打 Boss 无效化」也照样生效。
只有你**显式写成 `[]`** 才会真的不使用内置名单。

---

## PlaceholderAPI 变量

装了 [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 后，
插件会自动注册以下变量（**软依赖**，没装则静默跳过）：

> 💡 **双向支持**：插件注册了自己的变量，同时也**能读任意 PAPI 变量** ——
> 你可以在 `config.yml` 的**任何文本**里混用，例如：
> ```yaml
> boss-bar:
>   title: "&e%time% &7| 金币 &6%vault_eco_balance% &7| 等级 &b%player_level%"
> messages:
>   join-protected: "&a欢迎 %player_name%！保护剩余 %newbieprotect_time%"
> ```
> `/newbie info`、`/newbie list` 的输出也支持 PAPI 变量。
>
> **完整变量清单已写进 `config.yml` 开头**，管理员可直接查阅。

### 时间类

| 变量 | 说明 | 示例 |
| --- | --- | --- |
| `%newbieprotect_time%` | 剩余时间（中文格式） | `1时23分45秒` |
| `%newbieprotect_seconds%` | 剩余秒数 | `5025` |
| `%newbieprotect_minutes%` | 剩余分钟（向上取整） | `84` |
| `%newbieprotect_hours%` | 剩余小时（取整） | `1` |
| `%newbieprotect_used%` | 已消耗时间 | `36分15秒` |
| `%newbieprotect_used_seconds%` | 已消耗秒数 | `2175` |
| `%newbieprotect_total%` | 保护总时长 | `2时0分0秒` |
| `%newbieprotect_total_seconds%` | 总时长秒数 | `7200` |

### 状态类

| 变量 | 说明 | 取值 |
| --- | --- | --- |
| `%newbieprotect_protected%` | 此刻是否受保护 | `true` / `false` |
| `%newbieprotect_paused%` | 此刻是否暂停计时 | `true` / `false` |
| `%newbieprotect_pause_reason%` | 暂停原因文案 | `自己领地内不计时` |
| `%newbieprotect_self_disabled%` | 是否被玩家自己关闭 | `true` / `false` |
| `%newbieprotect_admin_paused%` | 是否被管理员冻结 | `true` / `false` |
| `%newbieprotect_finished%` | 是否已用尽保护 | `true` / `false` |
| `%newbieprotect_status%` | 状态中文 | `保护中` / `已关闭` / `已冻结` / `暂停中` / `已结束` / `保护中(离线)` |
| `%newbieprotect_bossbar%` | Boss 条是否开启 | `true` / `false` |
| `%newbieprotect_enabled%` | 插件总开关 | `true` / `false` |

### 进度 / 时间戳

| 变量 | 说明 | 示例 |
| --- | --- | --- |
| `%newbieprotect_percent%` | 剩余百分比（1 位小数） | `69.8` |
| `%newbieprotect_percent_int%` | 剩余百分比（整数） | `70` |
| `%newbieprotect_bar%` | 带颜色进度条（12 格） | `&a\|\|\|\|\|\|\|\|\|\|\|\|` |
| `%newbieprotect_bar_symbol%` | 纯符号进度条 | `\|\|\|\|\|\|\|\|\|\|\|\|` |
| `%newbieprotect_first_join%` | 首次进服时间 | `2026-09-29 19:20:36` |
| `%newbieprotect_first_join_ago%` | 距首次进服天数 | `0` |

### 用法示例

**TAB 的 tablist 里显示剩余时间：**
```
&e新人保护: &f%newbieprotect_time%
```

**计分板 / 全息：**
```
&7状态: &f%newbieprotect_status%
&7剩余: &f%newbieprotect_time% &8(%newbieprotect_percent%%)
&7进度: %newbieprotect_bar%
```

**按状态做条件判断**（如用 TAB 的条件显示）：
```
%newbieprotect_protected% = true   → 显示保护提示
%newbieprotect_paused%  = true     → 显示「暂停计时中」
```

---

## 打 Boss 无效化

**目的**：防止新人靠保护摸 Boss 偷奖励。

**规则**：新人一旦**自己攻击**了 Boss：

1. ⚔ 进入「Boss 战斗」状态 —— 本次战斗中保护失效
2. ⏸ 战斗中**暂停计时**（不浪费玩家时间，脱战后继续倒数）
3. 💬 **立刻发消息**告诉玩家（防止他以为插件坏了）
4. ✅ 脱战后**自动恢复**保护

### 配置

```yaml
boss-battle:
  enabled: true                      # 是否启用
  pause-timer-in-battle: true        # 战斗中暂停计时（推荐）
  protect-in-battle: false           # 战斗中是否仍免伤（false=真无效化）
  battle-timeout-seconds: 15         # 多少秒没再打 Boss 算脱战
  count-bossbar-entities: true       # 是否把带 Boss 血条的实体也算 Boss
  count-named-bosses: true           # 名字含 boss 的实体也算
  builtin-bosses:                    # 内置名单
    - ENDER_DRAGON
    - WITHER
    - WARDEN
    - ELDER_GUARDIAN
    - RAVAGER
    - PIGLIN_BRUTE
  custom-bosses: []                  # 自定义（填 EntityType 名）
```

### 哪些算 Boss？

四层判定，任一命中即可（可在配置里逐项关闭）：

| 层 | 说明 |
| --- | --- |
| 内置名单 | 末影龙 / 凋灵 / 监守者 / 远古守卫者 / 劫掠兽 / 猪灵蛮兵 |
| 自定义名单 | `custom-bosses` 里自己加 |
| Boss 血条 | 被服务端当作 Boss 显示血条的实体（可关） |
| 名字含 boss | 自定义名里含 `boss` / `BOSS` / `首领`（可关） |

### 战斗中 Boss 条长这样

```
Boss 战斗中 保护失效 | 剩余 1时59分30秒 | (此处保护计时已暂停) | 脱战 12 秒后恢复
```
（深红色，一眼能看出当前没有保护）

---

## Boss 条开关（防骚扰）

Boss 条一直挂在屏幕上方可能烦人。玩家可以自己关掉：

```
/newbie bar      在「显示 / 隐藏」之间切换
```

**特性：**

- 隐藏状态会**持久化**（重启后依然隐藏，不会又冒出来）
- 隐藏后**不会**在下一次 tick 被重新创建（真正去掉，不是只清一次）
- 只能在「显示 / 隐藏」间切换，不改变保护本身
- 服务器可关闭这个功能：`boss-bar.allow-player-toggle: false`
- PAPI 可用 `%newbieprotect_bossbar%` 查询当前是否开启

---

## 玩家自助开关（重点功能）
玩家可以自己决定要不要这份保护：

```
/newbie off     关闭保护 → 立即不再免伤
/newbie on      重新开启 → 立刻恢复免伤
/newbie toggle  一键切换
```

**Boss 条会直接告诉玩家怎么操作**，不用记命令：

| 状态 | Boss 条显示 |
| --- | --- |
| 保护中 | `新人保护中 \| 剩余 1时59分22秒 \| 输入 /newbie off 可关闭`（蓝色） |
| 已关闭 | `保护已关闭 \| 剩余 1时58分10秒 \| (关闭期间保护时间照常计算) \| 输入 /newbie on 可开启`（红色） |

### ⚠️ 关闭期间时间怎么算？

由 `self-toggle.count-while-disabled` 决定，**默认 `true`（照常计算）**：

| 取值 | 效果 | 建议 |
| --- | --- | --- |
| `true`（默认） | 关闭保护后，剩余时间**照样一秒一秒扣** | 推荐。关掉保护不能省时间，防止玩家长期关着保护、把时长囤到以后用；也避免「新人后期才开保护」的不公平 |
| `false` | 关闭期间**计时冻结**，剩余时间保留 | 适合「把保护当成可兑换资源」的玩法，但 TEAM 可能会有人一直关着不用 |

> 无论哪种取值，关闭期间的规则都会**写在 Boss 条和提示消息里告诉玩家**（`%timing%`）。

关闭状态会写进 `data.yml`（`self-disabled: true`），**重启后依然有效**：
玩家重登时会看到「你上次关闭了新人保护……」的提醒和重新开启的方法。

---

## 配置说明（config.yml）

```yaml
config-version: 2

enabled: true

protection:
  # 保护时长（分钟）。默认 120 = 2 小时
  # 只计算在线时间，下线暂停
  duration-minutes: 120

  # 【安全兜底】绝对上限（天）。从首次进服起算，
  # 超过这个天数强制结束保护（即使计时器卡住也不会永久无敌）。
  # 0 = 不启用（不推荐）
  max-absolute-days: 30

  block-mob-damage: true          # 防怪物
  block-player-damage: true       # 防玩家 PVP
  block-projectile-damage: true   # 防投射物
  mob-ignore-protected-player: true  # 怪物完全不锁定新人
  start-on-first-join: true

# 玩家自助开关（/newbie on | off | toggle）
self-toggle:
  enabled: true                  # 是否允许玩家自己开关
  count-while-disabled: true     # 关闭期间是否照常计时（推荐 true）

boss-bar:
  enabled: true
  # %time% 剩余时间 / %seconds% 剩余秒数 / %player% 玩家名
  # %state% 状态文案 / %timing% 计时说明 / %toggle-cmd% on 或 off
  title: "&b新人保护中 &7| &e剩余 %time% &8| &7输入 &f/newbie off &7可关闭"
  title-disabled: "&c保护已关闭 &8| &e剩余 %time% &8| &7(%timing%) &8| &7输入 &f/newbie on &7可开启"
  state-on: "保护中"
  state-disabled: "已关闭"
  timing-note-counting: "关闭期间保护时间照常计算"
  timing-note-paused: "关闭期间计时已暂停"
  color: BLUE                 # PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE
  color-disabled: RED         # 玩家关闭保护时的颜色
  style: SEGMENTED_10         # SOLID/SEGMENTED_6/10/12/20
  progress-mode: total        # total=按总时长递减 / fixed=固定满格

messages:
  prefix: "&8[&b新人保护&8] &r"
  join-protected: "&a你正处于新人保护期，还有 &e%time% &a。期间不会被怪物和玩家伤害。"
  query-self: "&a你的新人保护剩余：&e%time%"
  expired: "&7你的新人保护已结束，祝游戏愉快！"
  ending-soon: "&e注意：你的新人保护将在 &c%time% &e后结束。"
  # 自助开关相关
  self-off: "&e你已关闭新人保护。&7%timing%&7。当前剩余 &e%time%&7。输入 &f/newbie on &7可重新开启。"
  self-on: "&a你已重新开启新人保护，剩余 &e%time%&a。"
  # ... 其余消息见文件内注释

# 到期提醒（秒）。留空 [] 表示不提醒
remind-seconds:
  - 300
  - 60
  - 10

extra:
  block-attack-others: false   # 保护期内禁止主动打人
  block-break: false           # 保护期内禁止破坏方块
  block-place: false           # 保护期内禁止放置方块
```

### 常用改法

**只保护 1 小时：**
```yaml
protection:
  duration-minutes: 60
```

**不想让怪物无视玩家**（只想免伤，但仍会被追打）：
```yaml
protection:
  mob-ignore-protected-player: false
```

**只想防怪物，允许老玩家打新人（不推荐）：**
```yaml
protection:
  block-player-damage: false
```

**关闭期间不要扣时间**（剩余时间冻结）：
```yaml
self-toggle:
  count-while-disabled: false
```

**完全禁止玩家自助关闭**（只能管理员用 `/newbie clear|grant`）：
```yaml
self-toggle:
  enabled: false
```

**不想在 Boss 条上出现指令提示**（把标题改短即可）：
```yaml
boss-bar:
  title: "&b新人保护中 &7| &e剩余 %time%"
  title-disabled: "&c保护已关闭 &7| &e剩余 %time%"
```

---

## 数据文件（data.yml）

```yaml
players:
  <玩家UUID>:
    name: Steve
    used-seconds: 1234      # 已消耗的保护秒数（累计在线时间）
    first-join: 1790675455800
    finished: false         # 是否已用尽保护
    self-disabled: false    # 是否被玩家自己用 /newbie off 关闭了保护
```

- **不要手动改 `used-seconds` 为负数**，插件会归零处理
- 想重来一次，用 `/newbie grant <玩家>` 或直接删掉该玩家条目
- 想帮某个玩家重新开启保护，把 `self-disabled` 改成 `false` 即可

---

## 机制说明

**只算在线时长**：每秒为在线玩家 `used-seconds + 1`；
玩家下线后停止累加，所以保护时间不会在离线时流逝。

**自助关闭时的计时**：默认 `count-while-disabled: true`，
玩家关闭保护后 `used-seconds` **仍然每秒 +1** —— 也就是「关掉保护并不能省时间」。
若设为 `false`，关闭期间 `used-seconds` 不增长（时间被冻结）。

**什么时候算结束**：`used-seconds >= duration-minutes * 60` 时，
标记 `finished: true`，移除 Boss 条，发送结束消息。
（注意：玩家自助关闭保护**不会**导致 `finished`，只是暂时不免伤，
时间走完才会真正结束。）

**保护范围**：
- ✅ 拦截：怪物近战、怪物投射物、玩家攻击、玩家投射物
- ❌ 不拦截：摔落、火焰、溺水、虚空、中毒、饥饿等环境伤害
（这样新人不会因为不会玩而摔死，但也不会因为保护而滥用岩浆等危险地形）
- ❌ 玩家自己 `/newbie off` 之后：以上全部不再拦截（等于自愿放弃保护）

**Folia 支持**：所有对玩家的操作都在实体所属区域线程执行，
Boss 条与计时任务通过兼容层调度，Folia / Leaf / Paper / Purpur 均可运行。

---

## 关于「重启后会不会无敌」——不会

这个插件**从不修改任何玩家的持久状态**（不调用 `setInvulnerable`、
不调用 `setAllowFlight`、不改 `noDamageTicks`），它只在运行期间**拦截伤害事件**。
所以：

- 插件被卸载 → 监听器失效 → 保护自动消失，**不可能残留无敌**
- 服务器重启 → `data.yml` 里的 `used-seconds` 原样恢复，保护按剩余时间继续倒数
- 服务器强杀（kill -9 / 面板强杀）→ 最多丢失 **10 秒**的计时（定时落盘间隔），
  结果是玩家多得到几秒保护，绝不会变成无敌

关闭流程（关服 / 重载 / 卸载 / JVM 关闭钩子）统一走 `performShutdown()`：
取消任务 → 移除全部 Boss 条 → 强制落盘，幂等可重复调用。

**双重兜底**，防止「计时器卡住 → 所有人永远无敌」：
1. 计时循环外层包了 `try/catch` —— 即使某次 tick 抛异常也不会被 Bukkit 取消任务
2. `max-absolute-days`（默认 30 天）—— 从首次进服起算，超期强制失效

---

## 构建

```bash
mvn clean package
# 产物 target/newbieprotect-1.0.4.jar
```

---

## 实测记录（Purpur 26.3）

用测试插件直接构造伤害事件验证：

| 测试项 | 结果 |
| --- | --- |
| 怪物近战伤害被拦截 | ✅ |
| 怪物投射物伤害被拦截 | ✅ |
| 玩家 PVP 伤害被拦截 | ✅ |
| 无保护目标不被拦 | ✅ |
| 摔落/火焰/溺水/虚空 不拦 | ✅ |
| 保护用尽后不再拦 | ✅ |
| 到期后 Boss 条自动消失 | ✅ |
| 到期后 `finished=true` | ✅ |
| 下线后保护时间暂停 | ✅ |
| `/newbie reload` 不报错 | ✅ |

### 玩家自助开关实测
| 测试项 | 结果 |
| --- | --- |
| 关闭后 `isProtected` 变 false | ✅ |
| 关闭后怪物伤害不再被拦 | ✅ |
| 关闭后 Boss 条仍在显示（提示时间还在走） | ✅ |
| 关闭后剩余时间仍可查询 | ✅ |
| 重新开启后 `isProtected` 恢复 true | ✅ |
| 重新开启后怪物伤害重新被拦 | ✅ |
| 重复关闭不产生副作用 | ✅ |
| 关闭状态写入 `data.yml`（`self-disabled: true`） | ✅ |
| **重启后关闭状态保留**（`/newbie info` 显示「自助关闭: 是」） | ✅ |
| Boss 条标题含切换提示（`输入 /newbie off 可关闭` / `on 可开启`） | ✅ |
| Boss 条标题含计时说明（`关闭期间保护时间照常计算`） | ✅ |
| 关闭时 Boss 条颜色变 RED | ✅ |
| `count-while-disabled: true` → 关闭 6 秒，已消耗 +6 秒（照常计算） | ✅ |
| `count-while-disabled: false` → 关闭 6 秒，已消耗 +0 秒（暂停） | ✅ |
| 控制台执行 `/newbie off` 正确拒绝（仅玩家） | ✅ |
| `/newbie help` 显示 on/off 用法 | ✅ |

自助开关自动化共 **11 / 11 通过**；关闭期间计时两种模式各 **1 / 1 通过**。

### 世界暂停实测

| 测试项 | 结果 |
| --- | --- |
| 普通世界 `isPaused=false`、`isProtected=true` | ✅ |
| 普通世界 6 秒 → 已消耗 **+6 秒**（照常计时） | ✅ |
| 暂停世界（`THE_END` 类型命中）`isPaused=true` | ✅ |
| 暂停世界 `isProtected=false`（默认不保留保护） | ✅ |
| 暂停世界 6 秒 → 已消耗 **+0 秒**（暂停不扣时间） | ✅ |
| 暂停原因文案正确（`世界 world_the_end 内不计时`） | ✅ |

世界暂停自动化共 **6 / 6 通过**（含按世界类型 `NETHER`/`THE_END` 匹配）。

### 管理员指令实测

| 测试项 | 结果 |
| --- | --- |
| `set <玩家> 30m` → 剩余 1800 秒 | ✅ |
| `add <玩家> 10m` → 剩余 2400 秒 | ✅ |
| `take <玩家> 20m` → 剩余 1200 秒 | ✅ |
| `set <玩家> 0s` → `finished=true`、不再免伤 | ✅ |
| `open <玩家>` → 重新给满时长、恢复免伤 | ✅ |
| `pause <玩家>` → `admin-paused=true`、不免伤 | ✅ |
| 冻结期间 5 秒 → 已消耗 **+0 秒**（停表生效） | ✅ |
| `resume <玩家>` → 恢复计时与免伤 | ✅ |
| `close <玩家>` → `self-disabled=true`、不再免伤 | ✅ |
| `clear <玩家>` → 记录删除 | ✅ |
| `list` 分页显示正常 | ✅ |

管理员指令自动化共 **17 / 17 通过**。

### 时间解析实测

| 输入 | 期望 | 结果 |
| --- | --- | --- |
| `90s` | 90 | ✅ |
| `45m` | 2700 | ✅ |
| `2h` | 7200 | ✅ |
| `1h30m` | 5400 | ✅ |
| `1d` | 86400 | ✅ |
| `300`（纯数字=秒） | 300 | ✅ |
| `1时30分`（中文单位） | 5400 | ✅ |
| `2分` | 120 | ✅ |
| `1d2h3m4s`（组合） | 93784 | ✅ |
| `abc` / 空串 | 返回 null 并提示格式 | ✅ |

时间解析自动化共 **11 / 11 通过**。

### 领地（Residence）实测
| 测试项 | 结果 |
| --- | --- |
| 检测到 Residence 并接入 API | ✅ `isAvailable -> true` |
| 未装 Residence 时插件加载零报错 | ✅ |
| 未装时领地判断自动跳过（不影响其他功能） | ✅ |
| 用 API 创建领地成功（`addResidence -> true`） | ✅ |
| 站在**自己领地**里 `isPaused=true` | ✅ |
| 领地内 `isProtected=false`（默认不保留保护） | ✅ |
| 领地内 6 秒 → 已消耗 **+0 秒**（暂停不扣时间） | ✅ |
| 暂停原因文案正确（`自己领地内不计时`） | ✅ |

领地暂停自动化共 **4 / 4 通过**。

> 未装 Residence 的服务器上，插件会自动跳过领地判断 —— 可用
> `/newbie info <玩家>` 看状态确认。

### 打 Boss 无效化实测

| 测试项 | 结果 |
| --- | --- |
| 凋灵被识别为 Boss | ✅ |
| 普通僵尸不算 Boss | ✅ |
| 攻击 Boss 后进入战斗状态 | ✅ |
| 战斗中 `isProtected=false`（无效化生效） | ✅ |
| 战斗中 5 秒 → 已消耗 **+0 秒**（暂停计时） | ✅ |
| 超时后自动脱离战斗 | ✅ |
| 脱战后 `isProtected` 恢复 `true` | ✅ |

### Boss 条开关实测

| 测试项 | 结果 |
| --- | --- |
| 默认 Boss 条可见 | ✅ |
| `/newbie bar` 隐藏后无 Boss 条 | ✅ |
| 隐藏后 `refresh` 不会重新创建 | ✅ |
| PAPI `%newbieprotect_bossbar%` 反映状态 | ✅ |
| 再次切换恢复显示 | ✅ |

### PlaceholderAPI 实测

| 测试项 | 结果 |
| --- | --- |
| PAPI 变量扩展自动注册 | ✅ |
| `%newbieprotect_seconds%` = 剩余秒数 | ✅ |
| `%newbieprotect_status%` = `保护中` | ✅ |
| `%newbieprotect_protected%` = `true` | ✅ |
| `%newbieprotect_bar_symbol%` 长度 = 12 | ✅ |
| 未装 PAPI 时插件加载零报错 | ✅ |

### 关闭 / 重启 安全性实测
| 测试项 | 操作 | 结果 |
| --- | --- | --- |
| 正常关服清理 | RCON `stop` | `onDisable` 移除全部 Boss 条 + 落盘，日志「已安全关闭」✅ |
| 重启后进度保持 | 剩 8 秒 → 关服 → 重启 | `used-seconds` 从 7192 继续到 7200 → `finished: true`，**没有重置成 2 小时**（无无敌）✅ |
| 强制关闭（进程强杀） | 直接 `TerminateProcess` | 无任何清理日志，但数据靠 10 秒定时落盘保住；重启后从 49 秒续上，**最多丢 10 秒**，不会无敌 ✅ |
| 绝对到期兜底 | 把 `first-join` 改成 100 天前 | 剩余 `0秒`、`finished: true`（即使计时器卡住也不会永久无敌）✅ |
| 关闭钩子注册 | 启动日志 | 「已注册 JVM 关闭钩子（应对面板停止 / SIGTERM）」✅ |
| `/newbie cleanup` | 应急清理 Boss 条 | 清理 1 条并落盘；下一 tick 对仍在保护中的玩家自动恢复显示（正常）✅ |

> 说明：Windows 上 `taskkill`（不带 `/F`）无法给无窗口的控制台进程发优雅信号，
> 所以关闭钩子的**执行**只能在 Linux（`kill <pid>` 发 SIGTERM）或 Ctrl+C 场景下观察到；
> 钩子是否触发不影响正确性 —— 定时落盘 + 退服落盘 + `onDisable` 已覆盖全部实际关机路径。

---

## 常见问题

**Q：新人下线再上线，保护时间会重置吗？**
不会。已消耗的秒数保存在 `data.yml`，上线后继续从上次的位置倒数。

**Q：怎么让老玩家也获得保护？**
`/newbie grant <玩家>` 会把他的已消耗清零并重新计时。

**Q：怎么彻底取消某人的保护？**
`/newbie clear <玩家>` 删除记录 —— 注意这会让他在**下次进服时重新变成新人**。
如果要立刻取消且不再给，用 `/newbie info` 查看后手动把 `data.yml` 里
该玩家的 `used-seconds` 改成超过总时长的值，再 `/newbie reload`。

**Q：玩家自己关了保护，能反悔吗？**
可以。`/newbie on` 立刻恢复免伤，剩余时间从当前值继续。
关闭状态在重启后会保留，重登时也会提醒他「你上次关闭了新人保护」。

**Q：玩家关了保护是不是就能把时间攒下来？**
默认不能。`self-toggle.count-while-disabled: true` 时，关闭期间剩余时间**照样在走**，
所以「关掉 = 白白浪费」—— 这样玩家没有动机长期关着保护。
如果你确实想允许攒时间，把它改成 `false`。

**Q：如果服务器被强行关闭（kill -9 / 面板强杀），数据会丢吗？**
最多丢 **10 秒**的计时（定时落盘间隔），也就是玩家稍微多得到几秒保护。
绝不会出现「重置成满时长」或「永久无敌」。玩家正常退服时还会额外立刻落盘一次。

**Q：我用了 PlugMan 之类热卸载插件，会有残留吗？**
不会。卸载会触发 `onDisable`，插件在那里移除全部 Boss 条并落盘。
如果万一有残留，用 `/newbie cleanup` 一键清掉。

**Q：Boss 条会不会挡住别的插件的 Boss 条？**
不会。Boss 条是每个玩家独立的，一个玩家可以同时显示多个 Boss 条
（垂直排列）。如果你觉得太挤，把 `boss-bar.enabled` 设为 `false` 即可。
