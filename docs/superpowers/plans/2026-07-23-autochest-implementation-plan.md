# AutoChest 实施计划

## 阶段 0：项目脚手架

### 目标
初始化 Maven 项目骨架，验证构建通过。

### 创建文件

```
AutoChest/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/io/github/autochest/
│   │   │   └── AutoChestPlugin.java
│   │   └── resources/
│   │       ├── plugin.yml
│   │       └── config.yml
│   └── test/
│       └── java/io/github/autochest/
└── .gitignore
```

### pom.xml 关键依赖版本

| 依赖 | 版本 | scope |
|------|------|-------|
| io.papermc.paper:paper-api | 1.21.4-R0.1-SNAPSHOT | provided |
| com.sk89q.worldguard:worldguard-bukkit | 7.0.x | provided |
| com.palmergames.bukkit.towny:towny | 最新稳定 | provided |
| org.maxgamer:QuickShop（或 ChestShop） | 目标版本 | provided |
| org.junit.jupiter:junit-jupiter | 5.11.x | test |
| org.mockito:mockito-core | 5.x | test |
| com.github.seeseemelk:MockBukkit-v1.21 | 最新兼容版 | test |

使用 paper-nms-maven-plugin 或 paperweight-userdev 自动获取混淆映射；Java 21 source/target。

### plugin.yml 关键字段

```yaml
name: AutoChest
version: 1.0.0
main: io.github.autochest.AutoChestPlugin
api-version: "1.21"
softdepend: [WorldGuard, Towny, ChestShop]
commands:
  autochest:
    aliases: [ac]
    permission: autochest.use
permissions:
  autochest.deposit:
    default: true
  autochest.restock:
    default: true
  autochest.reload:
    default: op
```

### 验证
`mvn clean package -DskipTests` 输出 `BUILD SUCCESS` 喵~

---

## 阶段 1：配置与消息层

### 目标
完成配置读取、消息发送与冷却服务，为后续所有模块提供基础喵~

### 文件

**`AutoChestConfig.java`**
- 字段：`scanRadiusX/Y/Z`（默认 8）、`scanBlocksPerTick`（默认 512）、`scanNanosPerTick`（默认 3ms）、`submitContainersPerTick`（默认 16）、`submitNanosPerTick`（默认 3ms）、`depositCooldownMs`（默认 5000）、`restockCooldownMs`（默认 3000）、`executorPoolSize`（默认 2）、`executorQueueSize`（默认 64）、各消息字符串、各音效枚举。
- 所有字段读取后校验：负数/零替换安全默认值；无效枚举记警告后使用默认音效；空字符串替换占位符。
- 提供 `reload(FileConfiguration cfg)` 方法，返回新实例，不修改旧对象。

**`MessageService.java`**
- 静态工厂，接收 `AutoChestConfig` 快照。
- 方法：`scanStarted(Player)`、`depositDone(Player, itemsMoved, containersUsed, containersSkipped)`、`restockDone(...)`、`noMatch(Player)`、`cooldown(Player, remainingMs)`、`taskConflict(Player)`、`serverBusy(Player)`、`hookUnavailable(Player, hookName)`、`cancelled(Player)`、`internalError(Player)`。
- 每个方法同时调用 `player.playSound(...)` 播放对应音效；音效在主线程调用。

**`CooldownService.java`**
- 两个 `ConcurrentHashMap<UUID, Long>`，分别存 deposit/restock 最后触发时间（`System.nanoTime()`）。
- `isOnCooldown(UUID, type)`、`record(UUID, type)`。
- 插件禁用时调用 `clear()` 释放引用。

### 测试
- `AutoChestConfigTest`：空值、负值、超大值均返回安全默认；reload 返回独立实例。
- `CooldownServiceTest`：首次不冷却；记录后立即检查触发冷却；冷却过期后可再次使用；两种操作互不干扰。

### 提交边界
`feat: 添加配置、消息与冷却服务喵~`

---

## 阶段 2：任务生命周期管理

### 目标
实现任务注册表、生命周期事件监听和 restock 槽位变化监听喵~

### 文件

**`PlayerTask.java`**（不可变记录）
- 字段：`UUID playerUuid`、`long token`（随机）、`int sessionEpoch`（快照时的 epoch）、`int pluginGeneration`、`OperationType type`（DEPOSIT/RESTOCK）、`AutoChestConfig configSnapshot`、`long startNanos`、`BlockPos center`、`UUID worldUuid`。
- 无 `Player` 引用；`isValid(PlayerTaskRegistry registry)` 委托给 registry 校验所有字段。

**`PlayerTaskRegistry.java`**
- `ConcurrentHashMap<UUID, PlayerTask>` 存运行中任务。
- `ConcurrentHashMap<UUID, AtomicInteger>` 存每玩家 session epoch。
- `int pluginGeneration`（插件禁用时递增至 `Integer.MAX_VALUE`）。
- `tryAcquire(UUID, type, config, center, worldUuid)`：CAS 插入；失败返回 Optional.empty()。
- `release(UUID, token)`：token 匹配才移除。
- `invalidate(UUID)`：递增对应玩家 epoch，使当前任务失效但不移除（延迟 release 由任务自身负责）。
- `isValid(PlayerTask task)`：检查 task.token、task.sessionEpoch、task.pluginGeneration 与注册表当前值一致。
- `disablePlugin()`：递增 generation，清空所有任务。

**`PlayerLifecycleListener.java`**（实现 `Listener`）
- 监听 `PlayerQuitEvent`（MONITOR 优先级）、`PlayerChangedWorldEvent`、`PlayerDeathEvent`（MONITOR 优先级）。
- 每个事件只调用 `registry.invalidate(player.getUniqueId())`，不持有 Player 引用超过事件处理方法。

**`RestockTargetSnapshot.java`**（不可变记录）
- 字段：`Map<Integer, ItemStack> slotToExpectedItem`（深拷贝，命令接受时生成）。
- `isEligible(int slot, ItemStack current)`：比较 `current` 是否仍与快照 `isSimilar`；否则标记永久失效。
- `Map<Integer, Boolean> invalidatedSlots`：并发安全标记（ConcurrentHashMap）。

**`RestockTargetListener.java`**（实现 `Listener`）
- 监听 `InventoryClickEvent`、`InventoryDragEvent`、`PlayerDropItemEvent`、`EntityPickupItemEvent`。
- 只对有运行中 restock 任务的玩家处理；从 registry 获取任务，读取对应 `RestockTargetSnapshot`，标记可能受影响的槽位为 `invalidated`。
- 提交时以实时 `isSimilar` 为最终判定，listener 标记为优化提示，不强制跳过。

### 测试
- `PlayerTaskRegistryTest`：tryAcquire 同玩家第二次返回 empty；invalidate 后 isValid 为 false；disablePlugin 后所有任务失效。
- `PlayerLifecycleListenerTest`（MockBukkit）：quit/changeWorld/death 事件触发后旧任务 isValid 为 false；新任务不受影响。

### 提交边界
`feat: 添加任务注册表与生命周期监听喵~`

---

## 阶段 3：容器识别与 Hook 策略

### 目标
实现 ContainerIdentity、访问策略接口及三个可选 Hook 适配器喵~

### 文件

**`BlockPos.java`**（不可变值对象）
- `int x, y, z; UUID worldUuid`。
- `distanceSquared(BlockPos other)`：返回 `long`，避免溢出。
- `toKey()`：`worldUuid + ":" + x + ":" + y + ":" + z`，用于去重和排序。

**`ContainerIdentity.java`**（不可变记录）
- 字段：`BlockPos primaryPos`（规范化主坐标）、`BlockPos secondaryPos`（双箱另一半，单箱为 null）、`double distanceSquared`。
- `isDoubleChest()`。
- `canonicalKey()`：双箱取两坐标字典序小者为前缀，保证去重。
- `geometricCenter()`：返回双箱中点或单箱中心，用于距离排序。
- `Comparator<ContainerIdentity> BY_DISTANCE_THEN_KEY`：按 distanceSquared 升序，相同按 canonicalKey 字典序。

**`ContainerAccessPolicy.java`**（接口）
- `boolean canAccess(Player player, ContainerIdentity identity, Block... blocks)`。
- `boolean isAvailable()`：Hook 是否可用。
- `String hookName()`。

**`CompositeAccessPolicy.java`**
- 包含多个 `ContainerAccessPolicy` 列表。
- `canAccess`：任一不可用且对应插件已安装，则直接 `throw HookUnavailableException`；全部允许才返回 true。
- `anyUnavailable()`：供命令层在任务创建前整体检查。

**`WorldGuardHook.java`**
- `onEnable()` 时检查 `Bukkit.getPluginManager().getPlugin("WorldGuard") != null`，尝试获取 `WorldGuard.getInstance().getPlatform().getRegionContainer()`。
- `canAccess`：对传入每个方块的 Location，调用 `RegionContainer.get(BukkitWorld)` 获取 `RegionManager`，遍历检查是否存在任何非 `__global__` 区域（不调用玩家权限判断）；双箱分别查询两半，任一非全局区域即返回 false。
- 初始化失败设置 `available = false`，`isAvailable()` 返回 false。

**`TownyHook.java`**
- 获取 `TownyAPI.getInstance()` 及 `TownyUniverse`。
- `canAccess`：使用 `PlayerCacheUtil.getCachePermission(player, location, Material, ActionType.SWITCH)` 判断；双箱分别查询两半，任一拒绝即返回 false。
- 初始化失败设置 `available = false`。

**`ChestShopHook.java`**
- 尝试通过 ChestShop API 的 `ChestShopSign` 或商店识别工具类判断箱子是否为商店容器；若 API 不稳定则用反射适配层限制访问面。
- `canAccess`：任一方块是商店箱返回 false（不查玩家权限，直接排除）；双箱分别检查两半。
- 初始化失败设置 `available = false`。

### 测试
- `ContainerIdentityTest`：双箱 canonicalKey 与坐标顺序无关；距离排序正确；同距按 key 稳定。
- `CompositeAccessPolicyTest`（假实现）：全允许返回 true；任一拒绝返回 false；任一不可用抛异常。
- WorldGuard/Towny/ChestShop Hook 使用假实现接口测试聚合逻辑；真实集成留人工验收。

### 提交边界
`feat: 添加容器身份与访问策略层喵~`

---

## 阶段 4：扫描与异步规划

### 目标
实现分 tick 扫描、快照工厂和 Bukkit-free 异步 DTO 喵~

### 文件

**`ScanTask.java`**
- 持有 `PlayerTask`、当前坐标游标、已发现 `Set<ContainerIdentity>`（用 canonicalKey 去重）。
- `tickStep(AutoChestPlugin plugin)`：在主线程调用；按 `configSnapshot.scanBlocksPerTick` 和 `scanNanosPerTick` 推进坐标游标；每个坐标先检查区块加载状态，再检查 `BlockState` 是否为 Chest/TrappedChest/Barrel；发现 Chest 时解析双箱，两半区块均需已加载。
- 每步开头调用 `registry.isValid(playerTask)` + 玩家在线 + isDead 检查；失败则取消并反馈消息。
- 扫描完成后触发 `onScanComplete(List<ContainerIdentity> sorted)`。

**`InventorySnapshotFactory.java`**
- `snapshotPlayer(Player, int[] slots)`：深拷贝指定槽位 ItemStack，返回 `Map<Integer, ItemStack>`（克隆）。
- `snapshotContainer(ContainerIdentity, Inventory)`：深拷贝所有槽位。
- 快照结果转换为 `ContainerDto`（Bukkit-free）：包含 `BlockPos`、`double distanceSquared`、`List<SlotDto>`。
- `SlotDto`：`int slot`、`int amount`、`int maxStackSize`、`boolean isEmpty`、`byte[] itemKey`（`ItemStack#serializeAsBytes()` 或等价序列化，仅用于异步候选索引，不用于最终匹配）。
- `PlayerInventoryDto`：同结构，按操作类型只包含 deposit 的 9..35 或 restock 的 0..35 槽位。

**`CandidatePlanner.java`**（在私有 Executor 中运行）
- 输入：`PlayerInventoryDto`、`List<ContainerDto>`。
- 输出：`PlanResult`（Bukkit-free）：`List<ContainerDto> sortedByDistance`、`Map<byte[], List<ContainerDto>> itemKeyToContainers`（候选索引）。
- 只做稳定排序和 HashMap 建立；不计算最终移动数量。
- 若线程池队列满则抛 `RejectedExecutionException`，调用者转换为"服务器繁忙"。

**`AsyncPlanningStage.java`**
- 主线程收集快照后提交 `CandidatePlanner` 到线程池。
- `CompletableFuture<PlanResult>` 完成后通过 `Bukkit.getScheduler().runTask(plugin, ...)` 回到主线程。
- 回到主线程前校验 `registry.isValid(task)`；失败则静默取消。

### 测试
- `ScanTaskTest`（MockBukkit）：扫描到箱子、双箱去重、跳过未加载区块、超时取消玩家离线。
- `InventorySnapshotFactoryTest`：克隆物品不影响原始库存；SlotDto 数量与原始一致。
- `CandidatePlannerTest`：纯 Java 逻辑，距离排序正确；itemKey 索引候选容器准确。

### 提交边界
`feat: 添加扫描、快照与异步规划喵~`

---

## 阶段 5：存入服务（DepositService）

### 目标
实现全局两阶段存入逻辑与主线程逐容器事务喵~

### 文件

**`ContainerTransaction.java`**
- `execute(Player player, ContainerIdentity identity, PlayerTask task, Registry registry, CompositeAccessPolicy policy, Consumer<TransactionResult> callback)`。
- 内部按第 7 节 10 步执行：验证玩家 → 区块 → Hook → clone 实时槽位 → 计算移动量 → 构造 after-image → 守恒验证 → source-first 写入 → 逐槽复核 → compare-and-verify 恢复（失败则记审计日志）。
- `TransactionResult`：`SUCCESS(movedAmount)`、`SKIPPED_PLAYER_INVALID`、`SKIPPED_CONTAINER_INVALID`、`SKIPPED_HOOK_DENIED`、`SKIPPED_NO_MATCH`、`FAILED_UNRECOVERABLE`。

**`DepositService.java`**
- 接收 `PlanResult`、`Player`、`PlayerTask`。
- **FILL_EXISTING 遍历**：按 `sortedByDistance` 顺序，对每种玩家物品（主背包 9..35）找候选容器，调用 `ContainerTransaction` 仅向已有非满相似堆叠写入；每次写入后更新本次任务的"该物品剩余量"账本。
- **USE_EMPTY 遍历**：再次按相同顺序遍历，对仍有剩余的物品使用候选容器空槽；第二阶段每次写入前重新验证该容器实时仍含同类物品（若已全部移走则跳过空槽阶段）。
- 两次遍历均受 `submitContainersPerTick` 和 `submitNanosPerTick` 约束，在容器事务之间检查预算让出喵~
- 遍历结束后汇总统计，调用 `MessageService.depositDone`。

### 测试
- `ContainerTransactionTest`：守恒验证失败阻止写入；恢复逻辑 compare-and-verify 仅在槽位等于 after-image 时覆写；恢复失败记审计日志。
- `DepositServiceTest`：圆石 5/64/8 存入 60 的验收示例；FILL_EXISTING 优先于 USE_EMPTY；第二阶段容器同类物品全移走时不使用空槽。

### 提交边界
`feat: 添加存入服务喵~`

---

## 阶段 6：补货服务（RestockService）

### 目标
实现不可变目标白名单与按玩家槽位优先的补货逻辑喵~

### 文件

**`RestockTargetWhitelist.java`**（不可变，命令接受时生成）
- 字段：`Map<Integer, ItemStack> eligibleSlots`（深拷贝，槽位 → 期望物品）；`Map<Integer, Boolean> invalidated`（ConcurrentHashMap）。
- `isEligible(int slot, ItemStack current)`：先检查 `invalidated`；再用 `current.isSimilar(expected)` 判断；若不相似则标记 `invalidated.put(slot, true)` 并返回 false；当前为 null/空也标记失效。
- `List<Integer> eligibleSlotsSorted()`：按槽位升序返回未失效槽位。

**`RestockService.java`**
- 接收 `PlanResult`、`Player`、`PlayerTask`、`RestockTargetWhitelist`。
- 外层循环：按 `whitelist.eligibleSlotsSorted()` 逐槽处理（玩家槽位优先）。
- 内层循环：按 `sortedByDistance` 逐容器取物；每次 `ContainerTransaction` 只处理"从容器取出，放入玩家该槽位"的单向事务；成功后更新该槽位剩余需求；达到 `maxStackSize` 则移出白名单继续下一槽。
- 同样按 `submitContainersPerTick` / `submitNanosPerTick` 预算让出。
- 完成后调用 `MessageService.restockDone`。

### 测试
- `RestockTargetWhitelistTest`：槽位变化后 isEligible 返回 false 且不可恢复；两个相似物品的槽位均追踪；满堆叠槽位不在 eligibleSlotsSorted 中。
- `RestockServiceTest`：稀缺来源按"玩家槽位 → 容器距离"分配；不使用空槽；目标白名单失效槽位跳过。

### 提交边界
`feat: 添加补货服务喵~`

---

## 阶段 7：命令集成与主类

### 目标
连接所有模块，完成完整命令流程喵~

### 文件

**`AutoChestCommand.java`**（实现 `CommandExecutor` 与 `TabCompleter`）
- `deposit` 子命令：
  1. 检查玩家（控制台拒绝）。
  2. 检查 `autochest.deposit` 权限。
  3. `registry.tryAcquire` 检查任务冲突。
  4. `cooldownService.isOnCooldown` 检查冷却；取得任务锁后立即 `cooldownService.record`。
  5. `compositePolicy.anyUnavailable()` 检查 Hook 初始化。
  6. 生成 restock 不需要、deposit 不需要的额外快照；发送"正在扫描"消息。
  7. 创建并调度 `ScanTask`；回调链接到 `AsyncPlanningStage` → `DepositService`。
- `restock` 子命令：
  1-6 同上；额外生成 `RestockTargetWhitelist`（快照主背包+快捷栏 0..35）。
  7. 创建并调度 `ScanTask`；回调链接到 `AsyncPlanningStage` → `RestockService`。
- `reload` 子命令：重载 `config.yml`，创建新 `AutoChestConfig` 实例；`MessageService` 和 `CooldownService` 使用新配置；运行中任务不受影响。
- Tab 补全：返回 `["deposit", "restock", "reload"]`，filtered by prefix。

**`AutoChestPlugin.java`**（主类）
- `onEnable()`：
  1. 保存默认 `config.yml`。
  2. 构造 `AutoChestConfig`。
  3. 构造私有 `ThreadPoolExecutor`（固定大小、有界 `ArrayBlockingQueue`、命名线程工厂）。
  4. 构造 `PlayerTaskRegistry`（初始 generation=1）。
  5. 构造 `CooldownService`。
  6. 初始化可选 Hook，构造 `CompositeAccessPolicy`。
  7. 注册 `PlayerLifecycleListener`、`RestockTargetListener`。
  8. 注册 `AutoChestCommand`。
- `onDisable()`：
  1. `registry.disablePlugin()`（递增 generation，所有迟到回调失效）。
  2. `executor.shutdown()`（等待最多 2 秒后 `shutdownNow()`）。
  3. `cooldownService.clear()`。

### 测试
- `AutoChestCommandTest`（MockBukkit）：无权限被拒绝；任务冲突被拒绝；冷却被拒绝；Hook 不可用被拒绝；正常流程触发扫描和消息。

### 提交边界
`feat: 完成命令集成与主类喵~`

---

## 阶段 8：集成测试与人工验收

### 自动化集成测试（MockBukkit）

重点场景（对应设计规格第 10 节）：

1. deposit 圆石 5/64/8 存入 60 验收示例。
2. deposit 全局 FILL_EXISTING 先于 USE_EMPTY；第二阶段资格重验。
3. 物品元数据不同不匹配（附魔书、命名物品、耐久道具）。
4. restock 不可变白名单；槽位换物后不恢复资格；稀缺来源槽位优先。
5. 双箱去重；跨区块两半加载检查；距离排序；同距稳定排序。
6. 玩家退出后重连、换世界后返回、死亡后重生不恢复旧任务。
7. 区块卸载、容器破坏只跳过失效容器。
8. 重复命令拒绝；deposit/restock 冷却独立；接受即消费不退还。
9. Hook 缺失（插件未装）不影响运行；初始化失败整体拒绝新任务；运行期查询异常排除单容器。
10. clone-only after-image；source-first 写入；事务不可跨 tick；compare-and-verify 恢复；守恒验证。
11. 插件禁用后迟到异步回调不修改库存、不释放新任务锁。

### 人工验收清单

在 Paper 1.21.4 测试服务器（分别安装/不安装 WorldGuard、Towny、ChestShop）执行：

- [ ] 单箱 deposit/restock 基础流程。
- [ ] 双箱 deposit/restock（包含跨区块双箱）。
- [ ] 木桶 deposit/restock。
- [ ] 两名玩家同时对同一箱子操作，物品数量守恒。
- [ ] 漏斗持续运行时执行 deposit/restock，物品不复制不丢失。
- [ ] 执行中强制退出再重连，旧任务不继续。
- [ ] 执行中切换维度，旧任务不继续。
- [ ] 执行中死亡，旧任务不继续。
- [ ] WorldGuard 区域内箱子跳过；区域外箱子正常。
- [ ] Towny 地块权限允许时正常；拒绝时跳过。
- [ ] ChestShop 商店箱跳过；普通箱正常。
- [ ] 三个插件同时安装，混合场景正常。
- [ ] 任一插件安装后 Hook 不可用时拒绝任务并提示。
- [ ] 大量容器（50+）执行期间 TPS 无明显下降。
- [ ] `/autochest reload` 后新冷却/半径/消息生效；旧任务不受影响。
- [ ] 服务端 `/stop` 时插件正常禁用，无报错。

---

## 推荐实施顺序

```
阶段 0 → 阶段 1 → 阶段 2 → 阶段 3 → 阶段 4 → 阶段 5 → 阶段 6 → 阶段 7 → 阶段 8
```

每个阶段完成对应测试后再进入下一阶段；阶段 3 的 Hook 可与阶段 4 并行编写，但集成测试留到阶段 8 喵~
