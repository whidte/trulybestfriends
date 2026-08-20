# 真正永恒的伙伴（Truly Best Friends Forever）

> 让你的宠物永远陪伴在身边 —— 一个面向 Minecraft 1.20.1（Forge）的宠物管理模组，立志做最好的 Minecraft 宠物管理模组。

**版本**：0.2.0.1 · **作者**：whidte · **协议**：GPL-3.0

[MC 百科](https://www.mcmod.cn/class/28331.html) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/truly-best-friends-forever) · [GitHub](https://github.com/whidte/trulybestfriends)

驯服过的宠物会被自动追踪并持久化保存。即使宠物所在的区块未加载或宠物已经死亡，你也可以通过独立的宠物管理面板随时把它们召回身边、复活，或在创造模式下传送到它们的位置。

## 核心特性

- **自动追踪与持久化**：驯服任意可归属实体（狼、猫、马、鹦鹉、模组宠物等）后自动登记，NBT 数据保存在 `<存档>/trulybestfriends/<玩家UUID>/<宠物UUID>.nbt`，并维护 `pets_index.nbt` 索引。
- **性能模式**：在配置文件中开启 `performanceMode` 后，模组将停用自动追踪等高开销扫描。此时需要用羽毛右键实体手动登记；登记物品及是否消耗均可配置。
- **宠物管理面板**：在物品栏中新增「宠物伙伴」标签页（基于 [L2 Library](https://www.mcmod.cn/class/7075.html)），也可通过自定义快捷键打开。面板内可查看宠物名称、生命、所在维度与坐标。
- **实时数据同步**：服务器定期把选中宠物的生命值、坐标和维度等状态推送到客户端，GUI 会自动刷新。
- **收回 / 放出**：单击将宠物收回存储（带距离限制与冷却），再次单击放出。
- **范围收回**：按住 **Shift** 单击收回按钮，可一次收回范围内的多只宠物；使用**滚轮**调节范围（1~16 格，默认 8 格）。
- **召唤**：可从任意维度把宠物召回玩家身边。坐下的宠物会自动站起；宠物所在区块未加载时，模组会等待区块加载后完成传送，并处理区块强制加载和实体去重。
- **换乘**：骑乘已登记宠物时，另一只曾被骑乘宠物的「召唤」按钮会变为「换乘」。换乘后原坐骑会被收回；按住 **Shift** 可改为普通召唤。
- **传送**：创造模式下，点击面板中的宠物坐标可传送到宠物身边。
- **生命恢复**：消耗 3 点饥饿值可为选中宠物启动 15 秒治疗；按住 **Shift** 消耗 9 点饥饿值可获得更快治疗，剩余时长最多叠加至 60 秒。
- **死亡复活**：被追踪的宠物死亡时不会掉落物品，随后可在面板中消耗配置的复活物品（默认 1 个不死图腾）将其复活，并获得不死图腾的生命恢复、伤害吸收和火焰抗性效果。特定实体可通过白名单保留正常掉落且禁止复活。
- **两步删除追踪确认**：正常删除追踪时，先左键点击删除按钮进入「待删除」状态，再按住 **Shift** 左键确认。删除已收回的宠物时会先把实体释放回世界；删除死亡宠物后会继续执行其正常死亡流程。开启 `deleteStoredPetsDirectly` 后则会直接永久删除存储数据。
- **肩上宠物跟踪**：自动跟踪站在玩家肩上的鹦鹉等实体，下肩后重新关联 UUID，避免丢失追踪。
- **优先级**：按住 **Shift** 左键点击宠物图标，可设置 1~6 级优先级并用于面板排序。
- **搜索框与过滤器**：可按宠物种族过滤列表，也可通过放大镜按钮按名称搜索宠物。
- **队伍编辑页面**：最多可建立 8 支队伍。每队默认最多 6 只宠物，可通过 `maxPendingSummons` 调整至最多 8 只；支持拖动宠物到槽位，或选中宠物后点击槽位内的「+」。
- **队伍召唤轮盘**：长按自定义快捷键打开当前队伍的召唤轮盘。指向宠物后松开快捷键或点击左键即可召唤，支持换乘；点击瓶子图标可召唤整支队伍。
- **背包备份**：对带容器的实体（如装有箱子的马）独立备份其物品栏 NBT，并兼容任意槽位数量的模组实体。
- **多语言维度名**：内置 20+ 常见维度的中英文显示名，包括原版维度、暮色森林、天境、彼岸、永昼/永曦之地、地下花园、蜜蜂领域、热带世界、盖亚维度、午夜维度以及 Ad Astra 的星球和轨道，并支持自定义添加。
- **NBT 离线编辑**：服务器关闭时，可直接修改已收回宠物的 `.nbt` 文件；下次启动后修改会保留并应用到实体。

## 模组相关指令

以下指令需要 2 级权限；使用配置的手动登记物品登记自己的宠物不需要 OP 权限。

| 指令 | 说明 |
|---|---|
| `/tbf load` | 将准星指向的实体读取为宠物；执行正常的主人、黑名单和 `maxPets` 上限检查，并可恢复曾被删除追踪的宠物 |
| `/tbf load master` | 强制把准星指向的非玩家生物登记到执行者的宠物面板，绕过常规主人检查 |
| `/tbf autoRegisterBlacklist` | 将准星指向的生物类型加入 `autoRegisterBlacklist`，此后不再自动追踪该类型 |
| `/tbf noReviveWhitelist` | 将准星指向的生物类型加入 `noReviveWhitelist`，使其不可通过复活功能复活 |
| `/tbf clearOnDeathWhitelist` | 将准星指向的生物类型加入 `clearOnDeathWhitelist`，该类型死亡后会清除追踪数据 |
| `/tbf clear` | 清空自己的宠物列表；需在 30 秒内点击聊天确认消息或执行 `/tbf clear confirm` |

## 安装

1. 安装 **Minecraft 1.20.1** + **Forge 47.4.20** 或以上版本。
2. 将以下模组 jar 放入 `.minecraft/mods/`：
   - Truly Best Friends Forever（本模组）
   - 可选：与 Minecraft 1.20.1 兼容的 L2 Library / L2Tabs（提供物品栏标签页；未安装时使用快捷键）
3. 启动游戏并进入存档。驯服宠物后，模组便会开始追踪。

> 服务端与客户端均需安装本模组及其依赖。本模组包含网络同步逻辑，不能仅安装在客户端。

## 配置

配置文件位于 `config/trulybestfriends-common.toml`：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `performanceMode` | `false` | 禁用自动登记和高开销扫描，改用指令或手动登记物品登记宠物 |
| `performanceModeSyncIntervalTicks` | `5` | 性能模式下按已追踪 UUID 更新实体的间隔（tick） |
| `ownerNbtFields` | `Owner`, `OwnerUUID` 等 | 为非 OwnableEntity 实体解析主人 UUID 的 NBT 路径，支持嵌套路径 |
| `syncIntervalTicks` | `103` | 全量扫描已加载可归属实体并同步到磁盘的间隔（tick，`0` 为关闭） |
| `localSyncIntervalTicks` | `5` | 扫描附近实体的间隔（tick） |
| `savePetDataCooldownTicks` | `100` | 将缓存宠物数据写入磁盘的间隔（tick）；玩家登出和服务器关闭时立即写入 |
| `recallRange` | `16.0` | 收回宠物的最大距离（格），`-1` 为无限 |
| `recallCooldownMs` | `3000` | 收回、召唤操作的冷却时间（毫秒，最低 250） |
| `maxPets` | `64` | 单名玩家最多追踪的宠物数 |
| `deleteStoredPetsDirectly` | `false` | 删除已收回或死亡宠物时，是否直接删除存储数据而不释放实体 |
| `areaRecallDefaultRange` | `8` | 范围收回的默认半径（1~16 格） |
| `maxPendingSummons` | `6` | 每名玩家的待处理召唤上限，同时决定每支队伍的槽位数（1~8） |
| `reviveItem` | `minecraft:totem_of_undying` | 复活宠物所需物品 ID，留空则不消耗物品 |
| `reviveItemCount` | `1` | 复活所需物品数量 |
| `reviveCooldownSeconds` | `120` | 复活冷却时间（秒） |
| `manualRegisterItem` | `minecraft:feather` | 手动登记宠物使用的物品 |
| `consumeManualRegisterItem` | `false` | 手动登记成功后是否消耗物品 |
| `healHungerCost` | `3` | 普通治疗消耗的饥饿值 |
| `advancedHealHungerCost` | `9` | Shift 高级治疗消耗的饥饿值 |
| `autoRegisterBlacklist` | 见配置 | 不自动登记的实体类型，支持 `namespace:*` 通配符 |
| `noReviveWhitelist` | 见配置 | 保留掉落物且不可复活的实体类型 |
| `clearOnDeathWhitelist` | 见配置 | 正常死亡并彻底清除追踪数据的实体类型 |

## 数据存储位置

所有数据保存在存档目录下。删除存档会一并删除宠物数据；卸载本模组不会损坏存档。

```text
<存档>/trulybestfriends/
├── pets_index.nbt              # 宠物索引及手动取消追踪的 UUID 黑名单
└── <玩家UUID>/
    └── <宠物UUID>.nbt          # 每只宠物的完整 NBT
```

> 服务器关闭时，可编辑 `<宠物UUID>.nbt` 来修改已收回宠物的生命、名称、最大生命值等属性。下次启动时修改会应用到实体；坐标和维度会在召唤时被定位逻辑覆盖。

## 兼容性

- **理论兼容**任何遵循可归属实体接口的模组宠物。未被识别的实体可通过 `ownerNbtFields` 配置其主人 UUID 的 NBT 路径，支持嵌套 NBT；默认已加入[驭役斗战·万物皆驯！](https://www.mcmod.cn/class/25583.html)的路径作为示例。
- 为所有登记在册的宠物提供 [FTB 团队](https://www.mcmod.cn/class/3179.html)兼容。
- 安装兼容版本的 L2 Library / L2Tabs 后，宠物面板会注册到 L2Tabs 物品栏标签组。
- 若宠物死亡时应保留正常掉落，请将实体 ID 加入 `noReviveWhitelist`。
- 若可归属实体不应被自动追踪（例如临时召唤物），请将实体 ID 或命名空间通配符加入 `autoRegisterBlacklist`。
- 与[边拿边走](https://www.mcmod.cn/class/2809.html)不兼容，同时安装会导致打开宠物伙伴标签页时崩溃。

## 已知限制

- 被追踪的宠物在死亡前若尚未经过一次同步保存（例如刚驯服便立刻死亡），可能无法复活。
- 多人服务器上的跨维度召唤受 `maxPendingSummons` 限制。
- 直接把 NBT 中的 `Health` 改为 `0` 后召唤，实体会进入「死亡但未收回」状态，需要通过复活功能恢复。
- 传送操作一定会尝试完成，请先确认目标附近空间安全，避免窒息或摔落。
- 单个实体 NBT 过大可能因数据包超过限制而断开连接；遇到此问题可安装 [Packet Fixer](https://www.mcmod.cn/class/12625.html)提高数据包大小上限。

## 界面展示

| 宠物列表 | 宠物列表 | 宠物列表 |
|---|---|---|
| 高优先级犬型金属傀儡（傀儡装配） | 已收回的火龙（冰火传说社区版） | 已死亡的骸龙斗士（诡厄巫法） |
| ![高优先级宠物](https://i.mcmod.cn/editor/upload/20260816/1786895904_25434_nQqd.webp) | ![已收回宠物](https://i.mcmod.cn/editor/upload/20260816/1786895922_25434_yoSb.webp) | ![已死亡宠物](https://i.mcmod.cn/editor/upload/20260816/1786895972_25434_SDjo.webp) |

| 队伍编辑 | 队伍轮盘 | 队伍轮盘 |
|---|---|---|
| 白队成员 | 白队全队 | 选中的骸龙斗士 |
| ![队伍编辑页面](https://i.mcmod.cn/editor/upload/20260817/1786896136_25434_OXUE.webp) | ![队伍召唤轮盘](https://i.mcmod.cn/editor/upload/20260817/1786896393_25434_KIqi.webp) | ![轮盘选中宠物](https://i.mcmod.cn/editor/upload/20260817/1786896499_25434_TJgF.webp) |

## 未来计划

- 增加更大的 GUI，并允许玩家切换界面尺寸。

## 开发

```bash
# 构建 jar
./gradlew build
# 产物位于 build/libs/
```

- **Minecraft**：1.20.1
- **Forge**：47.4.20
- **Java**：17

## 协议

本模组采用 **GPL-3.0** 协议。引用、整合或二次分发时请遵守协议条款，并注意兼容模组各自的协议约束。

## 反馈

遇到 bug 或有功能建议，请在 [GitHub Issues](https://github.com/whidte/trulybestfriends/issues) 提交，并附上：

- Minecraft、Forge 和本模组的版本；
- 完整的 `latest.log` 或 `crash-report`；
- 可复现问题的操作步骤。
