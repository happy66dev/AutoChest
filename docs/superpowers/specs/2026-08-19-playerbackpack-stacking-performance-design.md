# AutoChest PlayerBackpack 堆叠与性能优化设计

## 目标

修复 AutoChest 从 PlayerBackpack 整理到容器时同类物品被拆成多个小堆的问题，并降低大型整理、补货任务对 Bukkit 主线程的阻塞。

本设计覆盖：

- PlayerBackpack 来源的全局智能堆叠。
- 普通整理与 PlayerBackpack 整理的统一两阶段语义。
- PlayerBackpack 跨域 mutation 的异常补偿安全。
- 整理与补货的可恢复分 tick 执行。
- 纯数据规划的异步边界。
- 回归测试、性能指标与 Paper 验收。

## 问题根因

`DepositService.processPlayerBackpackPhase()` 按 logical slot、容器、容器槽位逐层处理 PlayerBackpack 来源。

`FILL_EXISTING` 可以填充命令开始时已有的相似未满堆叠，但 `USE_EMPTY` 当前遇到非空目标就跳过，随后只在空槽中创建新堆。因此：

```text
PlayerBackpack：黑曜石 x6、x6、x6
容器：已有黑曜石 x60，另有两个空槽
```

可能得到：

```text
x64、x2、x6、x6
```

而不是：

```text
x64、x8、空槽
```

原版来源已通过 `depositInUseEmptyPhase()` 先填相似堆、再使用空槽；PlayerBackpack 路径没有复用该语义，形成两套不一致的分配逻辑。

此外，PlayerBackpack 来源扣除与 Bukkit 容器写入属于两个存储域。若 PlayerBackpack `applyMutation()` 已成功，而后续 snapshot 推进失败，当前路径可能直接返回 `SKIPPED`，不执行条件补偿，存在来源已扣除但目标未写入的风险。

## 范围与非目标

### 本次范围

- 修复 PlayerBackpack deposit 的同类物品聚合和目标槽分配。
- 保持容器候选资格由任务开始快照决定，动态新增物品不能扩展候选容器集合。
- 将 PlayerBackpack deposit/restock 纳入可恢复预算和 cursor 状态机。
- 修复跨域 mutation 在部分成功后的保守补偿行为。
- 将排序、聚合、匹配等纯 DTO 运算迁移到异步线程。
- 增加单元测试、集成测试和 Paper 人工验收项。

### 非目标

- 不把 Bukkit `Inventory`、`ItemStack`、`Player`、`World` 或 PlayerBackpack provider 调用放入异步线程。
- 不直接访问 PlayerBackpack SQLite 文件、内部 repository 或 schema。
- 不改变空箱子不能接收新物品的既有候选资格规则。
- 不改变 restock 不创建新物品种类、不写入空目标槽的语义。
- 不在本次重写完整 GUI CAS mailbox 或无关容器扫描逻辑。

## 设计方案

### 1. 统一两阶段分配器

所有来源统一遵循：

1. `FILL_EXISTING`：按容器顺序遍历所有合格容器，填充当前已有的相似未满堆。
2. `USE_EMPTY`：来源仍有余量且当前合格容器仍存在该物品时，按容器顺序使用空槽。
3. 每次完整 mutation 成功后，立即更新内存中的来源数量和目标槽镜像，再继续寻找目标。

PlayerBackpack 不伪装成 Bukkit `Inventory`。分配器只负责计算目标和移动数量，实际来源扣除由 `CrossStorageMutationCoordinator.deposit(...)` 执行。

统一分配器必须支持两种来源操作：

- 原版来源：调用 `ContainerTransaction.commitDeposit(...)`。
- PlayerBackpack 来源：调用跨域 coordinator，以 logical slot、before-image 和 revision 执行 CAS mutation。

目标选择必须使用 `ItemStack.isSimilar(...)` 或等价的完整物品身份比较，包含 metadata、NBT、组件、附魔和自定义名称。只比较 `Material` 不得作为堆叠资格。

### 2. PlayerBackpack 来源聚合与分配

PlayerBackpack 阶段开始时，从 immutable snapshot 建立来源 worklist。worklist 记录：

- logical slot。
- 物品完整身份 key。
- 当前数量。
- 最大堆叠数。
- 任务开始时的来源 before-image。
- 当前 revision。

同一完整物品身份的多个 logical slot 可以在规划阶段聚合数量，但必须保留原始 logical slot 到扣减数量的映射。聚合只用于目标分配，不得绕过每个 logical slot 的 CAS 和 journal 保护。

示例：

```text
来源：x6、x6、x6
聚合：x18
目标：已有 x60 → x64；新槽 → x14
```

实际提交时按稳定顺序拆回 logical slot，单次 mutation 不能扣除超过该槽当前 before-image 的数量。

每次 mutation 返回新 snapshot 后：

- 更新任务上下文 revision。
- 更新来源 worklist 剩余数量。
- 更新目标容器镜像。
- 若 revision 或 before-image 不匹配，丢弃受影响计划，重新读取最新 snapshot 并重新规划。

### 3. 候选资格与动态目标

容器候选资格仍来自任务开始时的容器快照。`FILL_EXISTING` 可以使用快照中已存在的相似物品；`USE_EMPTY` 只能在该容器实时仍含该物品时使用空槽。

本轮刚写入的相似堆可以被后续来源继续填充，但不会使原本不合格的容器变成合格容器。

提交前必须重新验证：

- 容器身份和结构未变化。
- 保护 Hook 仍允许访问。
- 目标槽 before-image 未被外部修改。
- 来源 logical slot 仍属于当前 session 和 revision。
- 目标数量未超过最大堆叠数。

### 4. 跨域 mutation 失败安全

`CrossStorageMutationCoordinator.deposit(...)` 的状态机必须区分以下阶段：

1. `PREPARED`：journal 已记录双方 before-image。
2. `BACKPACK_APPLIED`：PlayerBackpack 来源 CAS 已成功。
3. `CONTAINER_APPLIED`：Bukkit 目标已写入并通过精确复核。
4. `COMPLETED`：双方状态和 journal 已完成确认。
5. `RECOVERED`：容器写入失败，PlayerBackpack 已按条件恢复。
6. `RECONCILIATION_REQUIRED`：无法确定或无法恢复，任务必须停止。

PlayerBackpack `applyMutation()` 成功后，任何以下情况都不能静默返回 `SKIPPED`：

- 返回 snapshot 为 null。
- `context.advance(...)` 失败。
- 新 revision 不符合预期。
- 任务 session 已失效但 mutation 结果未知。

此时必须：

1. 以 PlayerBackpack after-image 和新 revision 为条件尝试恢复 before-image。
2. 恢复成功则返回 `RECOVERED`，不计入移动数量。
3. 恢复失败或状态无法确认则写入 `RECONCILIATION_REQUIRED`，停止整个任务，保留 journal 和高严重度日志。
4. 禁止继续处理其他来源或容器，禁止无条件写回旧 snapshot。

### 5. 分 tick cursor 与预算

Deposit、restock 的普通路径和 PlayerBackpack 路径都使用可恢复 cursor。cursor 至少包含：

- 当前阶段：`FILL_EXISTING` 或 `USE_EMPTY`。
- 来源 worklist 索引或 logical slot。
- 容器索引。
- 容器槽位索引。
- 当前任务 revision/session token。
- 已处理 mutation 数和统计快照。

预算检查点放在每个完整 mutation 返回之后。单个 mutation 内部不能跨 tick，避免 source-first 写入后任务暂停造成半完成状态。

预算至少包括：

- 每 tick 最大容器数。
- 每 tick 最大 mutation 数。
- 每 tick 最大纳秒数。

达到任一预算时保存 cursor，下一 tick 从安全边界继续。`FAILED_UNRECOVERABLE`、`RECONCILIATION_REQUIRED`、session 失效或玩家离线时立即停止并释放会话。

PlayerBackpack 阶段不得绕过统一提交预算。

### 6. 异步规划边界

主线程负责：

- 世界、区块、方块、容器和 Bukkit Inventory 读取。
- 保护 Hook。
- PlayerBackpack API/provider 调用、CAS、journal 和补偿。
- Bukkit `ItemStack` clone、比较、序列化和写入。
- 提交前实时复验。

异步线程只处理不可变纯 DTO：

- 容器 canonical key 和槽位描述。
- 物品完整身份 key。
- 数量、最大堆叠数和来源映射。
- 容器排序、同类聚合和目标分配计划。

流程：

```text
主线程分批建立 immutable snapshot
→ 异步聚合、排序、生成 plan
→ 主线程复验 token、revision、before-image 和容器状态
→ 主线程执行完整单槽 mutation
→ 更新 cursor 或重新规划
```

任何复验失败都必须丢弃旧 plan，不得强行套用异步结果。

### 7. 性能优化

按优先级实施：

#### P0：预算与 cursor

- PB deposit/restock 纳入统一预算。
- 普通路径从容器级预算细化到完整 mutation 级预算。
- 每个容器快照分 tick，避免一次性读取全部容器。

#### P1：索引与重复扫描削减

- 任务级缓存容器非空槽、空槽和物品身份索引。
- 成功 mutation 后增量更新索引。
- 提交前只执行轻量失效复验，失效时重新快照。
- 避免每个来源槽位重新扫描完整容器。

#### P2：对象和反射开销

- 缓存 `PlayerBackpackAdapter` 的 Class、Method、Constructor。
- 复用任务级 immutable 物品身份 key。
- 评估替换 `Arrays.toString(serializeAsBytes())` 的字符串 key，使用碰撞安全的不可变二进制 key 或稳定封装。
- 检查 `BackpackSnapshot` 和 adapter 的重复深复制，确保不牺牲线程安全和 API 隔离。

性能优化不得降低提交前复验强度，不得复用可变 Bukkit 对象到异步线程。

## 错误处理与降级

- PlayerBackpack 未安装、未启用或 API 不兼容：跳过 PlayerBackpack 域，原版流程继续。
- PlayerBackpack 快照读取失败：拒绝建立双域任务，不触碰容器和来源。
- 候选容器失效：按现有规则跳过；若跨域 mutation 已成功，先完成条件补偿。
- CAS 冲突：丢弃受影响 plan，重新获取最新 snapshot；超过重试上限则停止任务并记录原因。
- Bukkit 写入或复核失败：执行条件恢复；恢复失败进入 `RECONCILIATION_REQUIRED`。
- 玩家离线、死亡、换世界、插件禁用：取消任务，停止后续 mutation，释放 session。
- 异步规划异常或线程池拒绝：保留来源和容器未提交状态，安全取消任务并释放资源。
- 输入为 null、空 worklist、非法 logical slot、负数量或超过容量：拒绝处理并记录明确诊断，不执行写入。

## 代码边界

### AutoChest

- `DepositService`：统一原版/PB 两阶段分配，接入 PB worklist、cursor 和预算。
- `RestockService`：PB 目标接入 cursor、预算和目标复验。
- `CrossStorageMutationCoordinator`：补全部分成功后的状态机、条件补偿和 journal 状态。
- `PlayerBackpackAdapter`：缓存反射元数据，保持可选依赖隔离。
- 新增纯 DTO planner，禁止携带 Bukkit 对象。
- `AutoChestConfig`：提供 PB 阶段 mutation/纳秒预算，使用保守默认值。

### PlayerBackpack

本 spec 不直接修改 PlayerBackpack 内部存储设计。若现有 API 无法表达“返回新 snapshot、revision 和明确 mutation 状态”，应先扩展稳定 API DTO，再由 AutoChest 适配，不得访问内部 service 或 SQLite。

## 测试计划

### 单元测试

1. PB `x6 + x6 + x6` 聚合后目标形成单个 `x18` 堆。
2. 容器已有 `x60`，PB `x6 + x6` 得到 `x64 + x8`。
3. FILL 到 USE_EMPTY 跨阶段时，后续来源优先填充已有和本轮新建的相似堆。
4. 不同 metadata、NBT、组件或附魔的物品不能合并。
5. PB 与原版来源混合写入同一容器时保持全局阶段顺序。
6. 候选快照不包含某物品的容器不能因任务中动态新增该物品而使用空槽。
7. PB `applyMutation` 成功但 snapshot 为 null 或 `context.advance` 失败时执行条件补偿。
8. 条件补偿失败进入 `RECONCILIATION_REQUIRED`，任务停止且不增加移动统计。
9. CAS 冲突丢弃旧 plan 并以最新 revision 重新规划。
10. 预算耗尽保存 cursor，下一 tick 从下一个安全 mutation 边界继续。
11. 空 worklist、null snapshot、非法 logical slot、负数量和超容量请求不执行写入。
12. restock 不使用空目标槽、不处理容量外槽位、不受 deposit 聚合影响。

### 性能测试

构造大量容器、重复物品和 PlayerBackpack logical slot，验证：

- 单 tick 不超过配置的纳秒和 mutation 预算。
- 快照分批后主线程单 tick 延迟受控。
- 规划线程不访问 Bukkit 对象。
- 索引增量更新结果与完整扫描结果一致。
- CAS 冲突、重规划、恢复和 journal 状态计数准确。

### Paper 人工验收

环境：Paper `1.21.4`、Java `21`、PlayerBackpack 已安装并启用喵~

1. PlayerBackpack 放入多个零散黑曜石，执行 `/autochest deposit`，确认容器优先合并，不出现每个来源独占小堆。
2. 容器已有未满黑曜石和多个空槽，确认先填满已有堆，再填充本轮新建堆，最后才使用其他空槽。
3. 不同命名、附魔或组件物品不发生错误合并。
4. 大量容器和来源执行 deposit/restock，确认服务器 tick 不出现长时间单 tick 卡顿，任务跨 tick 完成。
5. 模拟容器写入异常、CAS 冲突、PlayerBackpack snapshot 推进失败和恢复失败，确认无静默丢物、复制，失败任务停止且日志包含 journal 标识。
6. 移除 PlayerBackpack 后重启 AutoChest，确认原版 deposit/restock 仍正常。

## 验收标准

- PlayerBackpack 多个相同完整物品身份来源按全局两阶段规则合并。
- 任意跨域部分成功都进入明确完成、恢复或不可调和状态，不静默继续。
- PB deposit/restock 不再绕过分 tick 预算。
- 异步线程只处理 immutable DTO，不访问 Bukkit 或未声明线程安全的 PlayerBackpack API。
- 自动化测试和 Paper 人工验收全部通过。
- 现有 71 个 AutoChest 测试保持通过，且新增测试覆盖本 spec 核心行为。
