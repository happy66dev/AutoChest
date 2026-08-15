# AutoChest 与 PlayerBackpack 集成设计

## 目标

扩展现有 `/autochest deposit` 与 `/autochest restock`：

- `deposit` 同时整理原版主背包槽位 `9..35` 与 PlayerBackpack 的可写物品槽位到附近容器。
- `restock` 同时补满原版快捷栏/主背包槽位 `0..35` 与 PlayerBackpack 中命令开始时已有的未满堆叠。
- 当执行玩家的 PlayerBackpack 被本人或管理员打开时，先安全保存并关闭全部相关 GUI，再开始任务。
- 不出现物品复制、消失、旧 GUI 回写覆盖或跨库存半提交。
- PlayerBackpack 缺失、未启用或 API 版本不兼容时，AutoChest 保持现有原版背包功能，并跳过 PlayerBackpack 域。

本设计同时修复 PlayerBackpack 已存在的同一目标背包被多个查看者并发编辑时的陈旧 GUI 覆盖问题。仅为 AutoChest 增加调用而不处理该问题，会使 AutoChest 写入与旧页面关闭产生复制或丢失更新风险。

## 范围和非目标

本次范围：

- 两插件之间的版本化、受控运行时 API。
- PlayerBackpack 目标背包的会话冻结、保存、关闭、revision 校验和外部写入协调。
- AutoChest 的 PlayerBackpack deposit/restock 来源与目标适配。
- 原版背包、PlayerBackpack、附近容器之间的数量守恒、补偿和失败处理。
- 自动化测试与 Paper 人工验收。

本次不做：

- 直接读取或写入 `plugins/PlayerBackpack/backpack.db`。
- 新增独立 AutoChest 命令或玩家开关。
- 将 PlayerBackpack 改造成 Bukkit `PlayerInventory`。
- 改变现有容器扫描、保护 Hook、偏好排序、冷却或原版库存既有语义。

## 依赖与兼容策略

AutoChest 在 `plugin.yml` 中增加 `softdepend: [PlayerBackpack]`。这保证 PlayerBackpack 已安装时先启用，以便 AutoChest 获取兼容 API；未安装时 AutoChest 仍可启用。

AutoChest 编译时依赖 PlayerBackpack 的 API artifact，运行时依赖范围为 `provided`。不得通过反射调用内部 `BackpackService`，也不得依赖 `SQLiteBackpackRepository`、SQLite schema 或 BLOB 编码细节。

PlayerBackpack 提供稳定包名下的版本化 API，例如 `com.playerbackpack.api.PlayerBackpackApi`。API 至少声明：

- API 版本号与兼容范围。
- 查询 PlayerBackpack 是否可用于给定玩家。
- 读取带 revision 的不可变背包快照。
- 获取目标玩家的独占操作会话。
- 保存并关闭该目标的全部可编辑 GUI。
- 在会话内对指定逻辑槽位执行带 revision 的比较并替换。
- 在会话结束后解锁并刷新或重新打开被协调的 GUI。

AutoChest 启动时验证插件实例、启用状态和 API 主版本。验证失败时记录一次明确警告，禁用兼容适配器；不能让错误传播为 AutoChest 启动失败。

## PlayerBackpack 会话一致性

### 单写者与查看者处理

PlayerBackpack 以 `targetId` 为粒度维护会话协调器。每个目标背包同一时刻只允许一个可编辑 GUI 查看者。

- 玩家或管理员打开一个已被编辑的目标背包时，拒绝打开并提示目标正在编辑。
- 只读查看不在本次范围内；当前管理 GUI 仍按可编辑会话处理。
- Viewer 关闭、翻页、插件禁用、玩家退出时必须释放对应会话。
- 自动拾取、死亡清空、API 外部写入和 GUI 保存均先取得同一 `targetId` 协调锁。

这避免两个旧 GUI 页分别携带同一背包内容，并在不同时间回写相互覆盖。

### Revision 与 Compare-And-Swap

每个 PlayerBackpack 快照携带单调递增 `revision`。持久化层在玩家记录中保存该 revision，并在成功完整快照写入后递增。

`BackpackInventoryHolder` 记录打开 GUI 时的 `baseRevision`。GUI 关闭或翻页保存时：

1. 锁定目标背包。
2. 读取当前持久化 revision。
3. 若 revision 不等于 `baseRevision`，拒绝盲写旧页面。
4. 关闭当前 GUI、提示页面已过期，并按当前快照重新打开或要求用户重新打开。
5. 若 revision 一致，保存页面变更并返回新 revision。

`saveGuiPage` 不再接受任意 holder 和 inventory 的无条件页面覆盖。它必须验证 holder、目标、查看者、库存 holder、可编辑会话和 `baseRevision` 全部匹配。

### AutoChest 启动前冻结 GUI

AutoChest 取得 PlayerBackpack 操作会话后，在主线程执行：

1. 枚举所有在线查看者的顶部库存。
2. 找到 `targetId` 相同的 PlayerBackpack GUI。
3. 对每页在当前 revision 下保存最终状态。
4. 任一保存失败或 revision 冲突时，关闭已取得的操作会话，不启动 AutoChest 任务。
5. 所有页面保存成功后，关闭全部匹配 GUI。
6. 下一 tick 确认没有匹配 GUI 仍处于打开状态。
7. 读取最新带 revision 快照，开始扫描或提交。

执行期间目标背包处于外部操作会话中。PlayerBackpack 的 `/backpack` 打开、GUI 点击/拖拽/翻页、自动拾取和其他外部写入请求必须拒绝或安全等待；本设计采用拒绝策略，避免任务跨 tick 时持有可编辑旧页面。

AutoChest 任务完成、取消或出现不可恢复错误时，必须在 `finally` 风格统一出口释放会话。不会自动重新打开此前关闭的 GUI。

## PlayerBackpack API 模型

API 以不可变 DTO 和显式会话表达，避免暴露仓储、SQLite 或可变 `ItemStack` 引用。

建议模型：

- `BackpackSnapshotView`：`playerId`、`capacity`、`revision`、按逻辑槽位排序的物品副本。
- `BackpackOperationSession`：`playerId`、会话 token、初始 revision、关闭状态。
- `BackpackMutationResult`：成功、新 snapshot/revision、冲突、无匹配、存储失败、会话失效等状态。

API 所有 Bukkit `ItemStack` 输入和输出必须 clone。逻辑槽位从 `1` 开始；容量外超额物品是只取出区域，AutoChest 不得将物品写入该区域，但 deposit 可以从其中取出物品，前提是完整快照比较与更新成功。

API 对每个单槽改动执行 compare-and-swap：调用方同时提交预期 revision、预期槽位 before-image 和候选 after-image。任一不匹配时返回冲突，绝不覆盖。

对于 AutoChest 的多槽任务，API 提供在同一操作会话内读取最新 snapshot、提交完整候选 snapshot 的原子方法。提交前验证 base revision，成功后 revision 增加。AutoChest 不保留跨 tick 的可写旧快照；每个提交步骤均以 API 返回的新 revision 继续。

## Deposit 数据流

### 来源顺序

现有原版主背包 `9..35` 保持原有遍历与锁定格规则。PlayerBackpack 来源在原版来源之后处理，逻辑槽位按升序遍历：

- 容量内非空槽位。
- 容量外真实超额物品槽位。

这样不会改变原版背包中同类物品的既有优先顺序，并确保超额物品可被整理出去。

### 容器语义

继续使用现有全局两阶段规则：

1. `FILL_EXISTING`：先填满所有合格容器已有的相似未满堆叠。
2. `USE_EMPTY`：仅当容器实时仍有该相似物品时，才使用空槽创建新堆叠。

PlayerBackpack 物品也必须满足命令快照时的容器候选资格。不能因为任务中其他来源刚写入同类物品，就允许新容器接收该类物品。

### 单次跨域提交

PlayerBackpack 与 Bukkit 容器不共享数据库事务，故单次移动采用受控补偿协议：

1. 在主线程验证任务、容器、保护 Hook、操作会话和来源槽位 before-image。
2. 构造 PlayerBackpack 来源 after-image 与容器目标 after-image，验证相似性、容量和数量守恒。
3. 通过 PlayerBackpack API 以 revision CAS 保存来源扣除后的 snapshot。
4. 立即写入容器目标槽位，并精确复核 after-image。
5. 容器写入失败或复核失败时，使用 API 的新 revision 与来源 after-image 作为 CAS 条件恢复 PlayerBackpack before-image。
6. 恢复成功：跳过当前容器或槽位；恢复失败：记录高严重度审计日志、停止整个任务并释放会话。

不能先写容器再扣 PlayerBackpack，因为 SQLite 保存失败会导致物品复制；也不能在恢复时无条件写回旧 snapshot，因为可能覆盖新变更。

## Restock 数据流

### 双域白名单

命令接受时建立两个不可变白名单：

- 原版库存：沿用 `0..35` 中非空、未满堆叠的物品身份和最大堆叠数。
- PlayerBackpack：冻结当前带 revision snapshot 中所有容量内、非空、未满堆叠的逻辑槽位及其物品身份和最大堆叠数。

不包含原版空槽、满堆叠、PlayerBackpack 空槽或容量外超额槽位。补货不会新建物品种类或写入空格。

PlayerBackpack 目标在原版库存目标之后按逻辑槽位升序处理。每次任务让出 tick 后，必须使用当前 session revision 与初始期望物品重新验证该目标仍相似、数量未被外部修改且未满。

### 单次跨域提交

每次从容器向 PlayerBackpack 目标移动物品：

1. 在主线程验证任务、容器、Hook、目标白名单、操作会话与当前 snapshot revision。
2. 读取容器来源 before-image，读取 PlayerBackpack 目标 before-image；确认二者相似、来源数量足够、目标未满。
3. 构造两端 after-image，并验证数量守恒。
4. 通过 API CAS 保存 PlayerBackpack 目标增加后的 snapshot。
5. 写入容器来源扣减后的槽位，并精确复核。
6. 容器扣减失败或复核失败时，以 API 新 revision 和 PlayerBackpack after-image 为条件恢复目标 before-image。
7. 恢复失败时立即中止任务并输出审计日志。

原版库存与容器仍保持现有 `ContainerTransaction` 的 source-first、精确复核与 compare-and-verify 恢复逻辑。

## 与原版背包 GUI 交错

PlayerBackpack GUI 的混合页可能修改玩家原版库存。AutoChest 在冻结阶段关闭 GUI 后才创建原版 restock 白名单和 PlayerBackpack 白名单，避免白名单建立后 GUI 手工移动库存。

AutoChest 现有 `RestockTargetListener` 必须不再依赖 `ignoreCancelled = true` 来判断库存未变。任何 PlayerBackpack 受控库存操作完成后，都要通过共享协调器使该玩家当前 AutoChest restock 任务失效，或使全部相关原版/插件背包白名单失效。最低限度是：所有可能改变原版背包的已取消 GUI 事件也要失效 AutoChest 白名单。

任务执行期间普通玩家库存点击、拖拽、拾取、丢弃与 PlayerBackpack 重新打开尝试均按现有或新增监听器使相应 restock 目标 fail-closed。仅比较 `isSimilar` 不足以证明玩家没有主动改变数量；白名单需要记录初始数量或每次合法 AutoChest 写入后更新受控期望数量。

## 失败降级与生命周期

- PlayerBackpack 未安装、未启用、API 不兼容或 API 初始化失败：继续原版 AutoChest 流程，记录兼容层不可用原因；不访问数据库。
- PlayerBackpack GUI 保存/关闭失败：拒绝本次命令，不建立扫描任务，不消耗物品；若冷却已按现有命令语义记录，保持现有“命令已接受即消费冷却”策略。
- PlayerBackpack 读取、CAS、存储或恢复失败：停止任务，释放 AutoChest 任务锁与 PlayerBackpack 会话；不得继续处理其他容器。
- 容器结构、区块、权限 Hook 或玩家状态失效：沿用现有跳过/取消规则；若已完成 PlayerBackpack 变更但容器写入失败，先尝试条件恢复。
- 插件禁用：AutoChest 先使其任务失效并等待/取消回调；所有存活会话在 finally 出口释放。PlayerBackpack 按自身禁用流程保存处于允许状态的 GUI；冻结会话不得被旧 GUI 覆盖。
- 玩家离线、死亡、换世界：任务取消并释放外部会话。后续 PlayerBackpack 生命周期处理按其自身锁与 revision 执行。

## 代码边界

### PlayerBackpack

- 新增公开 API 包、API 版本模型、DTO 和会话接口。
- 扩展数据库 schema，为玩家快照保存 revision；旧数据库迁移默认 revision 为零。
- 新增按 `targetId` 的背包会话协调器。
- 修改 `BackpackInventoryHolder`，保存 base revision 和会话 token。
- 修改 GUI 打开、点击、拖拽、翻页、关闭、自动拾取、死亡与禁用流程，使其经协调器和 CAS。
- 限制或收窄当前公开的内部写入方法；AutoChest 不调用 `BackpackService.replaceItems`、`saveGuiPage` 等内部方法。

### AutoChest

- 新增 PlayerBackpack 可选 Hook/适配器和依赖检查。
- 在命令处理器的任务建立前请求 PlayerBackpack 外部操作会话、保存关闭 GUI，并在下一 tick 后建立双域白名单。
- 将 PlayerBackpack 来源/目标适配为专用服务，不把其伪装为 Bukkit `Inventory`。
- 为跨域移动增加 before-image、after-image、revision、CAS、精确核验和条件补偿。
- 统一任务成功、取消、拒绝、线程池满、异常和插件禁用路径中的会话释放。
- 保持现有 `ContainerTransaction` 仅负责 Bukkit 原版库存与方块容器交易，避免其承担 SQLite 语义。

## 测试与验收

### PlayerBackpack 自动化测试

1. 同一目标背包只能有一个可编辑 viewer。
2. 第二个 viewer 打开同目标时被拒绝，首个 viewer 关闭后可重新打开。
3. GUI 保存使用 revision CAS；旧页面不能覆盖较新 snapshot。
4. 翻页、关闭、自动拾取、死亡清空与外部 API 写入互斥，且释放会话。
5. GUI 打开时 AutoChest 冻结会话会先保存、关闭所有匹配查看者，并在失败时不开始任务。
6. API 输入/输出的 `ItemStack` 均为副本；调用方修改副本不影响持久化数据。
7. revision schema 从旧 SQLite 数据库迁移后保持所有物品、容量、名称和自动拾取设置。

### AutoChest 自动化测试

1. PlayerBackpack 缺失、未启用和 API 主版本不匹配时，原版 deposit/restock 完整工作。
2. deposit 同时处理原版主背包与 PlayerBackpack 容量内、超额来源，并保持两阶段容器资格规则。
3. restock 只补充命令开始时原版与 PlayerBackpack 中已有的未满堆叠，不使用空槽或超额槽。
4. 每次跨域成功移动前后，来源与目标总量严格守恒。
5. PlayerBackpack CAS 失败时不写容器；容器写失败时成功条件恢复 PlayerBackpack。
6. 条件恢复失败时停止任务、释放会话并记录不可恢复审计。
7. GUI 关闭、玩家离线/死亡/换世界、容器失效、Hook 失效、线程池拒绝和插件禁用不会遗留会话锁。
8. 已取消但手工修改原版库存的 PlayerBackpack GUI 交互会使 restock 白名单 fail-closed。
9. 任务跨 tick 时玩家来源、容器物品或 PlayerBackpack snapshot 改变，提交会跳过冲突槽位而不覆盖。

### Paper 人工验收

在 Paper `1.21.4`、Java `21` 环境部署两插件，验证：

1. 本人打开 PlayerBackpack 时执行 `/ac deposit` 和 `/ac restock`，GUI 先保存关闭，随后两种库存域均正确处理。
2. 管理员查看在线或离线目标背包时，目标玩家执行命令，全部关联 GUI 被安全保存关闭。
3. 多管理员同时尝试编辑同一目标，只有一个可编辑会话；不能通过旧页关闭恢复已取走物品。
4. 自动拾取、翻页、Shift 操作、物品拖拽、掉落和命令在相邻 tick 发生时，无复制、无丢失、无不受控补货。
5. 断开 PlayerBackpack 数据库、模拟容器 `setItem` 异常和恢复失败，确认任务中止、日志可审计、数量不被静默改变。
6. 移除 PlayerBackpack 后重启，确认 AutoChest 原版 deposit/restock 行为保持不变。
