package io.github.autochest.service;

import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.integration.playerbackpack.CrossStorageMutationCoordinator;
import io.github.autochest.integration.playerbackpack.PlayerBackpackTaskContext;
import io.github.autochest.integration.playerbackpack.PlayerBackpackTaskContexts;
import io.github.autochest.scan.CandidatePlanner.PlanResult;
import io.github.autochest.scan.InventorySnapshotFactory;
import io.github.autochest.task.PlayerTask;
import io.github.autochest.task.PlayerTaskRegistry;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * 存入服务（Deposit）
 * 执行全局两阶段存入：先对所有候选容器补满已有堆叠，再使用候选容器空槽
 * 每个容器的操作按"单次不可让出事务"执行，遍历间隔受提交预算约束
 */
public class DepositService {

    /** 操作阶段枚举 */
    private enum Phase {
        /** 第一阶段：补满已有未满堆叠 */
        FILL_EXISTING,
        /** 第二阶段：使用候选容器空槽 */
        USE_EMPTY
    }

    /** 容器处理结果，用于将提交失败安全传播到预算外层 */
    enum ContainerOutcome {
        /** 当前容器可继续处理 */
        CONTINUE,
        /** 当前容器已恢复异常状态，应跳到下一个容器 */
        SKIP_CONTAINER,
        /** 事务无法恢复，必须取消整个任务 */
        ABORT_TASK
    }

    private final ContainerTransaction transaction;
    private final PlayerTaskRegistry registry;
    private final Plugin plugin;
    private final Logger logger;
    // 保存跨域协调器以处理 PlayerBackpack 来源和 Bukkit 容器目标喵~
    private final CrossStorageMutationCoordinator crossStorageCoordinator;
    // 保存跨域任务上下文表以读取当前玩家会话喵~
    private final PlayerBackpackTaskContexts playerBackpackTaskContexts;

    /**
     * 创建存入服务
     *
     * @param transaction 容器事务执行器
     * @param registry    玩家任务注册表
     * @param plugin      插件实例（用于调度）
     * @param logger      日志记录器
     */
    public DepositService(ContainerTransaction transaction, PlayerTaskRegistry registry,
                          Plugin plugin, Logger logger) {
        this(transaction, registry, plugin, logger, null, null);
    }

    // 创建可选 PlayerBackpack 双域存入服务喵~
    public DepositService(ContainerTransaction transaction, PlayerTaskRegistry registry,
                          Plugin plugin, Logger logger,
                          CrossStorageMutationCoordinator crossStorageCoordinator,
                          PlayerBackpackTaskContexts playerBackpackTaskContexts) {
        // 保存 Bukkit 容器事务依赖喵~
        this.transaction = transaction;
        // 保存任务注册表喵~
        this.registry = registry;
        // 保存主线程调度插件喵~
        this.plugin = plugin;
        // 保存日志依赖喵~
        this.logger = logger;
        // 保存可选双域协调器喵~
        this.crossStorageCoordinator = crossStorageCoordinator;
        // 保存可选跨域上下文表喵~
        this.playerBackpackTaskContexts = playerBackpackTaskContexts;
    }

    /**
     * 执行全局两阶段存入
     * 必须在主线程调用；遍历间通过 BukkitScheduler 让出 tick
     *
     * @param plan         异步规划结果（候选容器列表）
     * @param playerTask   玩家任务
     * @param onDone       完成后的回调（含统计数据）
     */
    public void execute(PlanResult plan, PlayerTask playerTask, DepositCallback onDone) {
        // 在主线程执行，分阶段按预算让出
        runPhase(Phase.FILL_EXISTING, plan, playerTask, onDone, new DepositStats());
    }

    /**
     * 执行指定阶段的容器遍历
     *
     * @param phase      当前阶段
     * @param plan       规划结果
     * @param playerTask 玩家任务
     * @param onDone     完成回调
     * @param stats      累计统计数据
     */
    private void runPhase(Phase phase, PlanResult plan, PlayerTask playerTask,
                          DepositCallback onDone, DepositStats stats) {
        // 验证玩家状态
        Player player = Bukkit.getPlayer(playerTask.getPlayerUuid());
        if (!registry.isValid(playerTask) || player == null || !player.isOnline() || player.isDead()
                || !player.getWorld().getUID().equals(playerTask.getWorldUuid())) {
            onDone.onCancelled();
            return;
        }

        World world = player.getWorld();

        // 收集玩家完整背包 0..35 的物品快照（用于计算剩余需求）。
        // 每次让出后重新读取，保证剩余量基于实时状态。
        Map<Integer, ItemStack> playerItems = new LinkedHashMap<>();
        for (int slot = 0; slot <= 35; slot++) {
            // 仅允许任务快照授权 deposit 的槽位作为整理来源。
            if (!playerTask.getPreferencesSnapshot().allowsDeposit(slot)) {
                continue;
            }
            ItemStack item = ContainerTransaction.cloneOrNull(player.getInventory().getItem(slot));
            if (item != null) {
                playerItems.put(slot, item);
            }
        }

        if (playerItems.isEmpty() && playerBackpackTaskContext(playerTask) == null) {
            // 原版和 PlayerBackpack 来源均为空，无需操作喵~
            onDone.onComplete(stats);
            return;
        }

        List<ContainerIdentity> identities = new ArrayList<>(plan.sortedContainers);

        // 按预算逐容器处理，处理完后若有剩余让出 tick 继续
        processContainersBudgeted(phase, identities, player, world, playerTask, plan, stats, 0,
                () -> {
                    // 原版来源完成后按 PlayerBackpack 独立 cursor 分 tick 处理喵~
                    processPlayerBackpackPhase(phase, identities, playerTask, plan, stats,
                            () -> {
                                // PB 来源完成后才进入下一阶段或完成任务喵~
                                if (phase == Phase.FILL_EXISTING) {
                                    // FILL_EXISTING 完成后进入 USE_EMPTY 阶段喵~
                                    Bukkit.getScheduler().runTask(plugin, () ->
                                            runPhase(Phase.USE_EMPTY, plan, playerTask, onDone, stats));
                                } else {
                                    // 两个来源域均完成后报告任务成功喵~
                                    onDone.onComplete(stats);
                                }
                            },
                            onDone);
                },
                onDone);
    }

    /**
     * 按提交预算逐容器执行，超出预算则让出 tick 后继续
     *
     * @param phase      当前阶段
     * @param identities 容器列表
     * @param player     玩家
     * @param world      世界
     * @param playerTask 玩家任务
     * @param plan       规划结果
     * @param stats      统计数据
     * @param startIndex 从第几个容器开始处理
     * @param onPhoneDone 本阶段完成后的回调
     * @param onDone     任务完成回调
     */
    private void processContainersBudgeted(
            Phase phase,
            List<ContainerIdentity> identities,
            Player player,
            World world,
            PlayerTask playerTask,
            PlanResult plan,
            DepositStats stats,
            int startIndex,
            Runnable onPhoneDone,
            DepositCallback onDone
    ) {
        int containersPerTick = playerTask.getConfigSnapshot().getSubmitContainersPerTick();
        long nanosPerTick = playerTask.getConfigSnapshot().getSubmitNanosPerTick();
        long tickStart = System.nanoTime();
        int processed = 0;
        int i = startIndex;

        while (i < identities.size()) {
            // 预算检查（在容器事务之间检查，不在事务内部中断）
            if (processed >= containersPerTick || System.nanoTime() - tickStart >= nanosPerTick) {
                // 让出 tick，下个 tick 从当前位置继续
                final int nextIndex = i;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // 重新验证玩家
                    Player freshPlayer = Bukkit.getPlayer(playerTask.getPlayerUuid());
                    if (!registry.isValid(playerTask) || freshPlayer == null || !freshPlayer.isOnline() || freshPlayer.isDead()
                            || !freshPlayer.getWorld().getUID().equals(playerTask.getWorldUuid())) {
                        onDone.onCancelled();
                        return;
                    }
                    processContainersBudgeted(phase, identities, freshPlayer, freshPlayer.getWorld(),
                            playerTask, plan, stats, nextIndex, onPhoneDone, onDone);
                });
                return;
            }

            ContainerIdentity identity = identities.get(i);
            ContainerOutcome containerOutcome = processOneContainer(phase, identity, player, world, playerTask, plan, stats);
            if (containerOutcome == ContainerOutcome.ABORT_TASK) {
                // 喵~防御：Hook 或库存事务已不可恢复，立即中止整个任务。
                onDone.onCancelled();
                return;
            }
            processed++;
            i++;
        }

        // 本阶段所有容器处理完毕
        onPhoneDone.run();
    }

    // 获取当前任务的 PlayerBackpack 上下文，供空原版背包判断使用喵~
    private PlayerBackpackTaskContext playerBackpackTaskContext(PlayerTask playerTask) {
        // 无跨域表时安全返回空喵~
        if (playerBackpackTaskContexts == null || playerTask == null) {
            // 表示没有 PlayerBackpack 来源喵~
            return null;
        }
        // 返回当前玩家已登记上下文喵~
        return playerBackpackTaskContexts.get(playerTask.getPlayerUuid());
    }

    // 处理 PlayerBackpack 来源并在提交预算耗尽后保存 cursor 续跑喵~
    private void processPlayerBackpackPhase(Phase phase, List<ContainerIdentity> identities,
                                             PlayerTask playerTask, PlanResult plan,
                                             DepositStats stats, Runnable onComplete,
                                             DepositCallback onDone) {
        // 无跨域依赖时直接完成 PB 阶段喵~
        if (crossStorageCoordinator == null || playerBackpackTaskContexts == null) {
            // 没有 PB 来源无需执行任何主线程操作喵~
            onComplete.run();
            return;
        }
        // 读取当前玩家登记的 PB 会话喵~
        PlayerBackpackTaskContext context = playerBackpackTaskContexts.get(playerTask.getPlayerUuid());
        // 会话缺失或已关闭时安全跳过 PB 域喵~
        if (context == null || !context.isOpen()) {
            // 不猜测背包状态，交回统一完成出口喵~
            onComplete.run();
            return;
        }
        // 从最新快照建立稳定 logical slot worklist，过滤空 key 和非法 key 喵~
        List<Integer> logicalSlots = new ArrayList<>();
        for (Integer logicalSlot : context.snapshot().items().navigableKeySet()) {
            // 喵~防御：逻辑槽位必须为正数，deposit 可读取 overflow 但不写入 overflow 喵~
            if (logicalSlot != null && logicalSlot > 0) {
                // 保留稳定升序来源顺序喵~
                logicalSlots.add(logicalSlot);
            }
        }
        // 使用统一提交预算限制 PB validate 与 mutation 的主线程工作喵~
        int operationsPerTick = Math.max(1, playerTask.getConfigSnapshot().getSubmitContainersPerTick());
        long nanosPerTick = Math.max(1L, playerTask.getConfigSnapshot().getSubmitNanosPerTick());
        // 创建当前 tick 的统一提交预算喵~
        SubmissionBudget submissionBudget = new SubmissionBudget(operationsPerTick, nanosPerTick,
                System.nanoTime());
        // 从起点开始处理，完整 mutation 只在本次调用内执行喵~
        processPlayerBackpackCursor(phase, identities, playerTask, plan, stats, logicalSlots,
                0, 0, 0, submissionBudget, operationsPerTick, nanosPerTick, onComplete, onDone);
    }

    // 按 logical slot、容器和容器槽位 cursor 处理 PB 来源喵~
    private void processPlayerBackpackCursor(Phase phase, List<ContainerIdentity> identities,
                                              PlayerTask playerTask, PlanResult plan,
                                              DepositStats stats, List<Integer> logicalSlots,
                                              int logicalSlotIndex, int containerIndex,
                                              int containerSlotIndex, SubmissionBudget submissionBudget,
                                              int operationsPerTick, long nanosPerTick,
                                              Runnable onComplete,
                                              DepositCallback onDone) {
        // 当前 tick 预算由调用方创建，跨 tick 不复用旧的时间起点喵~
        // 读取当前会话，下一 tick 不复用旧上下文引用喵~
        PlayerBackpackTaskContext context = playerBackpackTaskContexts.get(playerTask.getPlayerUuid());
        // 会话失效时立即取消并交给统一释放出口喵~
        if (context == null || !context.isOpen()) {
            // 跨域状态无法确认时禁止继续写入喵~
            onDone.onCancelled();
            return;
        }
        // 逐个 logical slot 处理来源喵~
        while (logicalSlotIndex < logicalSlots.size()) {
            // 从最新 snapshot 读取来源，避免使用跨 tick 旧物品镜像喵~
            int logicalSlot = logicalSlots.get(logicalSlotIndex);
            ItemStack backpackItem = ContainerTransaction.cloneOrNull(context.snapshot().itemAt(logicalSlot));
            // 来源已为空则推进到下一个 logical slot 喵~
            if (backpackItem == null) {
                logicalSlotIndex++;
                containerIndex = 0;
                containerSlotIndex = 0;
                continue;
            }
            // 生成完整物品身份，限制任务开始时候选容器资格喵~
            String itemKey = InventorySnapshotFactory.itemKey(backpackItem);
            // 遍历候选容器喵~
            while (containerIndex < identities.size()) {
                // 预算检查只能发生在完整 mutation 之间喵~
                if (submissionBudget.exhausted(System.nanoTime())) {
                    // 保存当前 cursor，并在下一 tick 重新验证任务和上下文喵~
                    schedulePlayerBackpackContinuation(phase, identities, playerTask, plan, stats,
                            logicalSlots, logicalSlotIndex, containerIndex, containerSlotIndex,
                            operationsPerTick, nanosPerTick, onComplete, onDone);
                    return;
                }
                // 验证当前容器和 Hook 喵~
                ContainerIdentity identity = identities.get(containerIndex);
                ContainerTransaction.ValidationResult validation = transaction.validate(playerTask, identity);
                // 校验不代表完整 mutation，不消耗 mutation 预算，避免 maxOperations=1 时活锁喵~
                // Hook 不可用时停止整个任务喵~
                if (validation.failureResult == ContainerTransaction.Result.FAILED_HOOK_UNAVAILABLE) {
                    // 交给命令层统一取消与释放喵~
                    onDone.onCancelled();
                    return;
                }
                // 失效容器或候选资格不符时尝试下一个容器喵~
                if (!validation.isValid() || !plan.isSnapshotCandidate(itemKey, identity)) {
                    containerIndex++;
                    containerSlotIndex = 0;
                    continue;
                }
                // 读取已验证容器库存，槽位扫描同样受纳秒预算限制喵~
                Inventory inventory = validation.inventory;
                while (containerSlotIndex < inventory.getSize()) {
                    // 每个槽位读取前检查时间预算，避免 PB 大容器单 tick 长循环喵~
                    if (submissionBudget.exhausted(System.nanoTime())) {
                        // 保留当前槽位 cursor，下一 tick 从此处继续喵~
                        schedulePlayerBackpackContinuation(phase, identities, playerTask, plan, stats,
                                logicalSlots, logicalSlotIndex, containerIndex, containerSlotIndex,
                                operationsPerTick, nanosPerTick, onComplete, onDone);
                        return;
                    }
                    // 读取实时目标槽位喵~
                    ItemStack target = ContainerTransaction.cloneOrNull(inventory.getItem(containerSlotIndex));
                    // FILL_EXISTING 只处理已有相似未满堆叠喵~
                    if (phase == Phase.FILL_EXISTING && (target == null || !target.isSimilar(backpackItem)
                            || target.getAmount() >= target.getMaxStackSize())) {
                        containerSlotIndex++;
                        continue;
                    }
                    // USE_EMPTY 必须保持容器相似资格喵~
                    if (phase == Phase.USE_EMPTY && !containerHasSimilar(inventory, backpackItem)) {
                        containerSlotIndex++;
                        continue;
                    }
                    // USE_EMPTY 允许继续填充本阶段新建的相似未满堆喵~
                    if (phase == Phase.USE_EMPTY && target != null
                            && (!target.isSimilar(backpackItem) || target.getAmount() >= target.getMaxStackSize())) {
                        containerSlotIndex++;
                        continue;
                    }
                    // 计算当前目标槽可接收数量喵~
                    int capacity = target == null ? backpackItem.getMaxStackSize()
                            : target.getMaxStackSize() - target.getAmount();
                    // 限制移动数量不超过来源数量和目标容量喵~
                    int amount = Math.min(backpackItem.getAmount(), capacity);
                    // 喵~防御：非法移动数量只推进槽位，不执行 mutation 喵~
                    if (amount <= 0) {
                        containerSlotIndex++;
                        continue;
                    }
                    // 跨域 mutation 必须在主线程完整执行，不在内部让出 tick 喵~
                    CrossStorageMutationCoordinator.Result result = crossStorageCoordinator.deposit(
                            context, inventory, containerSlotIndex, logicalSlot, amount);
                    // 一次完整 mutation 结束后计入操作预算喵~
                    submissionBudget.markOperation();
                    // 成功后读取 context 最新 snapshot 并更新统计喵~
                    if (result.status() == CrossStorageMutationCoordinator.Status.SUCCESS) {
                        // 仅统计协调器确认的实际移动数量喵~
                        stats.itemsMoved += result.movedAmount();
                        // 成功写入目标后按 canonical identity 去重统计容器喵~
                        stats.markContainerUsed(identity);
                        // 重新读取来源，确保本阶段新建堆可被继续填充喵~
                        backpackItem = ContainerTransaction.cloneOrNull(context.snapshot().itemAt(logicalSlot));
                        // 来源耗尽则推进 logical slot 喵~
                        if (backpackItem == null) {
                            logicalSlotIndex++;
                            containerIndex = 0;
                            containerSlotIndex = 0;
                            break;
                        }
                        // 当前槽位可能已满，推进到下一槽位喵~
                        containerSlotIndex++;
                        continue;
                    }
                    // 已安全恢复时跳过当前容器喵~
                    if (result.status() == CrossStorageMutationCoordinator.Status.RECOVERED) {
                        stats.skipped++;
                        containerIndex++;
                        containerSlotIndex = 0;
                        break;
                    }
                    // 未提交冲突只推进当前槽位，避免旧目标重复尝试喵~
                    if (result.status() == CrossStorageMutationCoordinator.Status.SKIPPED) {
                        containerSlotIndex++;
                        continue;
                    }
                    // 不确定状态必须立即取消任务喵~
                    onDone.onCancelled();
                    return;
                }
                // 当前容器扫描完成后进入下一个容器喵~
                if (logicalSlotIndex < logicalSlots.size() &&
                        context.snapshot().itemAt(logicalSlot) != null) {
                    containerIndex++;
                    containerSlotIndex = 0;
                }
            }
            // 所有容器均无法继续时推进来源，保持现有候选资格语义喵~
            logicalSlotIndex++;
            containerIndex = 0;
            containerSlotIndex = 0;
        }
        // 全部 PB 来源完成后执行阶段回调喵~
        onComplete.run();
    }

    // 调度下一 tick 继续 PB cursor，并在回调前重新验证玩家生命周期喵~
    private void schedulePlayerBackpackContinuation(Phase phase, List<ContainerIdentity> identities,
                                                     PlayerTask playerTask, PlanResult plan,
                                                     DepositStats stats, List<Integer> logicalSlots,
                                                     int logicalSlotIndex, int containerIndex,
                                                     int containerSlotIndex, int operationsPerTick,
                                                     long nanosPerTick, Runnable onComplete,
                                                     DepositCallback onDone) {
        // 使用 Bukkit 主线程 scheduler 续跑，避免跨线程访问 Bukkit 对象喵~
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 重新获取玩家并确认任务仍处于有效世界喵~
            Player player = Bukkit.getPlayer(playerTask.getPlayerUuid());
            // 喵~防御：玩家离线、死亡、换世界或任务失效时取消写入喵~
            if (!registry.isValid(playerTask) || player == null || !player.isOnline() || player.isDead()
                    || !player.getWorld().getUID().equals(playerTask.getWorldUuid())) {
                // 交给统一完成出口释放会话喵~
                onDone.onCancelled();
                return;
            }
            // 为下一 tick 创建新的时间与操作预算喵~
            SubmissionBudget nextSubmissionBudget = new SubmissionBudget(operationsPerTick, nanosPerTick,
                    System.nanoTime());
            // 以新上下文和相同 immutable worklist 继续处理喵~
            processPlayerBackpackCursor(phase, identities, playerTask, plan, stats, logicalSlots,
                    logicalSlotIndex, containerIndex, containerSlotIndex, nextSubmissionBudget,
                    operationsPerTick, nanosPerTick, onComplete, onDone);
        });
    }

    /**
     * 处理单个容器的存入逻辑（不可让出的单次事务）
     *
     * @param phase      当前阶段
     * @param identity   目标容器
     * @param player     玩家
     * @param world      世界
     * @param playerTask 玩家任务
     * @param plan       规划结果
     * @param stats      统计数据
     */
    /**
     * 处理单个容器的存入逻辑（不可让出的单次事务）
     *
     * @return 当前容器处理结果，用于决定是否继续任务
     */
    private ContainerOutcome processOneContainer(Phase phase, ContainerIdentity identity, Player player,
                                                 World world, PlayerTask playerTask, PlanResult plan, DepositStats stats) {
        // 验证玩家和容器。
        ContainerTransaction.ValidationResult validationResult = transaction.validate(playerTask, identity);
        if (!validationResult.isValid()) {
            if (validationResult.failureResult == ContainerTransaction.Result.FAILED_HOOK_UNAVAILABLE) {
                // 喵~防御：Hook 运行期失效，应中止整个任务。
                return ContainerOutcome.ABORT_TASK;
            }
            stats.skipped++;
            return ContainerOutcome.CONTINUE;
        }

        Inventory containerInventory = validationResult.inventory;
        int itemsBeforeThisContainer = stats.itemsMoved;

        // 遍历玩家背包 0..35 的每个非空且允许整理的槽位。
        for (int playerSlot = 0; playerSlot <= 35; playerSlot++) {
            // 被任务创建时冻结为禁止 deposit 的槽位不能参与任一存入阶段。
            if (!playerTask.getPreferencesSnapshot().allowsDeposit(playerSlot)) {
                continue;
            }
            ItemStack playerItem = ContainerTransaction.cloneOrNull(player.getInventory().getItem(playerSlot));
            if (playerItem == null) {
                continue;
            }

            String playerItemKey = InventorySnapshotFactory.itemKey(playerItem);
            // 喵~防御：容器必须在任务快照时已含同类物品，实时新增同类物品不得获得接收资格。
            if (!plan.isSnapshotCandidate(playerItemKey, identity)) {
                continue;
            }

            ContainerOutcome transferOutcome;
            if (phase == Phase.FILL_EXISTING) {
                transferOutcome = depositToExistingStacks(player, containerInventory, playerSlot, playerItem, stats);
            } else if (containerHasSimilar(containerInventory, playerItem)) {
                // 第二阶段统一先合并再使用空槽，避免零散来源拆散同类堆叠喵~
                transferOutcome = depositInUseEmptyPhase(
                        player, containerInventory, playerSlot, playerItem, stats);
            } else {
                transferOutcome = ContainerOutcome.CONTINUE;
            }

            if (transferOutcome != ContainerOutcome.CONTINUE) {
                return transferOutcome;
            }
        }

        if (stats.itemsMoved > itemsBeforeThisContainer) {
            // 原版成功写入与 PlayerBackpack 成功写入共用去重统计喵~
            stats.markContainerUsed(identity);
        }
        return ContainerOutcome.CONTINUE;
    }

    /**
     * 在第二阶段先合并已有和本阶段创建的同类堆叠，再在来源仍有剩余时使用空槽。
     *
     * @param player 执行存入的玩家。
     * @param containerInventory 当前已验证的目标容器库存。
     * @param playerSlot 玩家来源槽位。
     * @param playerItem 当前来源物品的比较快照。
     * @param stats 当前任务累计统计。
     * @return 本容器继续、跳过或中止状态。
     */
    ContainerOutcome depositInUseEmptyPhase(Player player, Inventory containerInventory,
                                            int playerSlot, ItemStack playerItem, DepositStats stats) {
        // 喵~防御：容器已无相似物品时不得因本阶段创建新堆叠，保留空箱子不能接收物品语义喵~
        if (!containerHasSimilar(containerInventory, playerItem)) {
            // 不满足实时相似资格时不触碰来源或目标库存喵~
            return ContainerOutcome.CONTINUE;
        }
        // 先填充已有及本阶段刚创建的同类未满堆叠，避免来源零散时拆分为多个小堆叠喵~
        ContainerOutcome existingStacksOutcome =
                depositToExistingStacks(player, containerInventory, playerSlot, playerItem, stats);
        // 非继续结果必须原样传播，避免恢复失败被空槽路径掩盖喵~
        if (existingStacksOutcome != ContainerOutcome.CONTINUE) {
            // 返回事务层决定的安全控制流喵~
            return existingStacksOutcome;
        }
        // 读取合并后的实时来源槽位，判断是否还需要占用目标空槽喵~
        ItemStack remainingPlayerItem =
                ContainerTransaction.cloneOrNull(player.getInventory().getItem(playerSlot));
        // 来源已耗尽时无需再扫描空槽喵~
        if (remainingPlayerItem == null) {
            // 当前来源已经完整存入容器喵~
            return ContainerOutcome.CONTINUE;
        }
        // 将剩余物品放入空槽，保持原有候选资格限制喵~
        return depositToEmptySlots(player, containerInventory, playerSlot, playerItem, stats);
    }

    /**
     * 向容器已有非满相似堆叠中填充物品。
     *
     * @param player 执行存入的玩家。
     * @param containerInv 当前已验证的目标容器库存。
     * @param playerSlot 玩家来源槽位。
     * @param playerItem 来源物品的比较快照。
     * @param stats 当前任务累计统计。
     * @return 本容器继续、跳过或中止状态。
     */
    private ContainerOutcome depositToExistingStacks(Player player, Inventory containerInv,
                                                     int playerSlot, ItemStack playerItem, DepositStats stats) {
        for (int containerSlot = 0; containerSlot < containerInv.getSize(); containerSlot++) {
            ItemStack current = ContainerTransaction.cloneOrNull(player.getInventory().getItem(playerSlot));
            if (current == null) {
                break;
            }

            ItemStack target = ContainerTransaction.cloneOrNull(containerInv.getItem(containerSlot));
            if (target == null || !target.isSimilar(playerItem)
                    || target.getAmount() >= target.getMaxStackSize()) {
                continue;
            }

            int canMove = Math.min(current.getAmount(), target.getMaxStackSize() - target.getAmount());
            if (canMove <= 0) {
                continue;
            }

            ContainerTransaction.CommitResult commitResult =
                    transaction.commitDeposit(player, containerInv, playerSlot, containerSlot, canMove);
            if (commitResult.status == ContainerTransaction.CommitStatus.SUCCESS) {
                stats.itemsMoved += commitResult.movedAmount;
                continue;
            }
            if (commitResult.status == ContainerTransaction.CommitStatus.RECOVERED) {
                stats.skipped++;
                return ContainerOutcome.SKIP_CONTAINER;
            }
            if (commitResult.status == ContainerTransaction.CommitStatus.FAILED_UNRECOVERABLE) {
                return ContainerOutcome.ABORT_TASK;
            }
        }
        return ContainerOutcome.CONTINUE;
    }

    /**
     * 向容器空槽存入物品（第二阶段）
     *
     * @param player       玩家
     * @param containerInv 容器库存
     * @param playerSlot   玩家槽位
     * @param playerItem   物品快照
     * @param stats        统计数据
     * @return 当前容器处理结果
     */
    private ContainerOutcome depositToEmptySlots(Player player, Inventory containerInv,
                                                 int playerSlot, ItemStack playerItem, DepositStats stats) {
        for (int containerSlot = 0; containerSlot < containerInv.getSize(); containerSlot++) {
            ItemStack current = ContainerTransaction.cloneOrNull(player.getInventory().getItem(playerSlot));
            if (current == null) {
                break;
            }

            ItemStack target = ContainerTransaction.cloneOrNull(containerInv.getItem(containerSlot));
            if (target != null) {
                continue;
            }

            int canMove = Math.min(current.getAmount(), current.getMaxStackSize());
            if (canMove <= 0) {
                continue;
            }

            ContainerTransaction.CommitResult commitResult =
                    transaction.commitDeposit(player, containerInv, playerSlot, containerSlot, canMove);
            if (commitResult.status == ContainerTransaction.CommitStatus.SUCCESS) {
                stats.itemsMoved += commitResult.movedAmount;
                continue;
            }
            if (commitResult.status == ContainerTransaction.CommitStatus.RECOVERED) {
                stats.skipped++;
                return ContainerOutcome.SKIP_CONTAINER;
            }
            if (commitResult.status == ContainerTransaction.CommitStatus.FAILED_UNRECOVERABLE) {
                return ContainerOutcome.ABORT_TASK;
            }
        }
        return ContainerOutcome.CONTINUE;
    }

    /**
     * 检查容器是否实时存在与给定物品相似的堆叠（第二阶段资格检查）
     *
     * @param containerInv 容器库存
     * @param item         比较物品
     * @return true 表示容器实时含同类物品
     */
    private boolean containerHasSimilar(Inventory containerInv, ItemStack item) {
        for (ItemStack slot : containerInv.getContents()) {
            if (slot != null && !slot.getType().isAir() && slot.isSimilar(item)) {
                return true;
            }
        }
        return false;
    }

    /** 存入统计数据，可变，在主线程内逐步更新 */
    public static class DepositStats {
        /** 已成功写入的容器规范键集合，用于跨阶段和跨域去重。 */
        private final Set<String> usedContainerKeys = new HashSet<>();
        /** 成功移动的物品总数 */
        public int itemsMoved;
        /** 实际参与的容器数（每个容器在本任务中最多计一次） */
        public int containersUsed;
        /** 跳过的容器数 */
        public int skipped;

        /**
         * 将成功写入的容器记入统计，重复写入同一规范容器不重复计数。
         *
         * @param identity 已成功接收物品的容器身份。
         */
        void markContainerUsed(ContainerIdentity identity) {
            // 喵~防御：缺少容器身份时不能安全生成去重键，保守不增加统计喵~
            if (identity == null) {
                // 空身份不计入完成消息，避免虚增容器数喵~
                return;
            }
            // 首次写入该规范容器时才增加公开容器计数喵~
            if (usedContainerKeys.add(identity.canonicalKey())) {
                // 记录本任务实际使用过的独立容器数量喵~
                containersUsed++;
            }
        }
    }

    /** 存入操作完成回调接口 */
    public interface DepositCallback {
        /** 完成时调用（包含无匹配的情况） */
        void onComplete(DepositStats stats);
        /** 任务被取消时调用 */
        void onCancelled();
    }
}
