package io.github.autochest.service;

import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.integration.playerbackpack.CrossStorageMutationCoordinator;
import io.github.autochest.integration.playerbackpack.PlayerBackpackTaskContext;
import io.github.autochest.integration.playerbackpack.PlayerBackpackTaskContexts;
import io.github.autochest.scan.CandidatePlanner.PlanResult;
import io.github.autochest.task.PlayerTask;
import io.github.autochest.task.PlayerTaskRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * 补货服务（Restock）
 * 以玩家槽位为外层优先级，从附近容器取物品补满已有非满堆叠
 * 目标槽位白名单在命令接受时生成，变化即永久失效
 */
public class RestockService {

    /** 内层容器处理结果，用于安全传播库存事务失败 */
    private enum ContainerOutcome {
        /** 当前容器可继续处理 */
        CONTINUE,
        /** 当前容器发生已恢复异常，应跳到下一个容器 */
        SKIP_CONTAINER,
        /** 库存事务不可恢复，必须取消整个任务 */
        ABORT_TASK
    }

    private final ContainerTransaction transaction;
    private final PlayerTaskRegistry registry;
    private final Plugin plugin;
    private final Logger logger;
    // 保存跨域协调器以处理 Bukkit 容器来源到 PlayerBackpack 目标喵~
    private final CrossStorageMutationCoordinator crossStorageCoordinator;
    // 保存任务上下文表以取得当前 PlayerBackpack 独占会话喵~
    private final PlayerBackpackTaskContexts playerBackpackTaskContexts;

    /**
     * 创建补货服务
     *
     * @param transaction 容器事务执行器
     * @param registry    玩家任务注册表
     * @param plugin      插件实例（用于调度）
     * @param logger      日志记录器
     */
    public RestockService(ContainerTransaction transaction, PlayerTaskRegistry registry,
                          Plugin plugin, Logger logger) {
        this(transaction, registry, plugin, logger, null, null);
    }

    // 创建可选 PlayerBackpack 双域补货服务喵~
    public RestockService(ContainerTransaction transaction, PlayerTaskRegistry registry,
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
        // 保存可选跨域任务表喵~
        this.playerBackpackTaskContexts = playerBackpackTaskContexts;
    }

    /**
     * 执行补货操作
     * 按玩家槽位升序（外层）× 容器距离升序（内层）分配来源物品
     *
     * @param plan       异步规划结果
     * @param playerTask 玩家任务
     * @param whitelist  命令接受时生成的不可变目标槽位白名单
     * @param onDone     完成后的回调
     */
    public void execute(PlanResult plan, PlayerTask playerTask,
                         RestockTargetWhitelist whitelist, RestockCallback onDone) {
        // 获取按槽位升序排列的合格目标槽位
        List<Integer> eligibleSlots = whitelist.eligibleSlotsSorted();
        if (eligibleSlots.isEmpty() && playerBackpackTargetCount(playerTask) == 0) {
            // 原版与 PlayerBackpack 均没有合格目标时直接完成喵~
            onDone.onComplete(new RestockStats());
            return;
        }

        List<ContainerIdentity> identities = new ArrayList<>(plan.sortedContainers);

        processSlotsBudgeted(eligibleSlots, 0, 0, identities, playerTask, whitelist,
                new RestockStats(), onDone);
    }

    // 统计当前快照中容量内已有非满 PlayerBackpack 目标数量喵~
    private int playerBackpackTargetCount(PlayerTask playerTask) {
        // 无跨域任务表时没有 PlayerBackpack 目标喵~
        if (playerBackpackTaskContexts == null || playerTask == null) {
            // 返回零目标喵~
            return 0;
        }
        // 读取当前玩家任务上下文喵~
        PlayerBackpackTaskContext context = playerBackpackTaskContexts.get(playerTask.getPlayerUuid());
        // 上下文缺失或关闭时没有可写目标喵~
        if (context == null || !context.isOpen()) {
            // 返回零目标喵~
            return 0;
        }
        // 初始化合格目标计数喵~
        int targetCount = 0;
        // 遍历快照中的非空槽位喵~
        for (Integer logicalSlot : context.snapshot().items().navigableKeySet()) {
            // 跳过空键、容量外 overflow 和非法槽位喵~
            if (logicalSlot == null || logicalSlot <= 0 || logicalSlot > context.snapshot().capacity()) {
                continue;
            }
            // 读取目标物品副本喵~
            ItemStack item = ContainerTransaction.cloneOrNull(context.snapshot().itemAt(logicalSlot));
            // 只统计已有且未满堆叠喵~
            if (item != null && item.getAmount() < item.getMaxStackSize()) {
                // 增加合格目标计数喵~
                targetCount++;
            }
        }
        // 返回合格目标数量喵~
        return targetCount;
    }

    /**
     * 按预算逐槽位+容器处理，超出预算则让出 tick 后继续
     * 预算以"容器事务数"为单位，每次 validate() 调用都计入预算
     *
     * @param eligibleSlots   合格目标槽位列表
     * @param slotIndex       当前处理到第几个槽位
     * @param containerIndex  当前槽位处理到第几个容器
     * @param identities      容器列表
     * @param playerTask      玩家任务
     * @param whitelist       目标槽位白名单
     * @param stats           统计数据
     * @param onDone          完成回调
     */
    private void processSlotsBudgeted(
            List<Integer> eligibleSlots,
            int slotIndex,
            int containerIndex,
            List<ContainerIdentity> identities,
            PlayerTask playerTask,
            RestockTargetWhitelist whitelist,
            RestockStats stats,
            RestockCallback onDone
    ) {
        Player player = Bukkit.getPlayer(playerTask.getPlayerUuid());
        if (!registry.isValid(playerTask) || player == null || !player.isOnline() || player.isDead()
                || !player.getWorld().getUID().equals(playerTask.getWorldUuid())) {
            onDone.onCancelled();
            return;
        }

        int containersPerTick = playerTask.getConfigSnapshot().getSubmitContainersPerTick();
        long nanosPerTick = playerTask.getConfigSnapshot().getSubmitNanosPerTick();
        long tickStart = System.nanoTime();
        int processed = 0;

        int si = slotIndex;
        while (si < eligibleSlots.size()) {
            int playerSlot = eligibleSlots.get(si);

            // 任务快照禁止补货的槽位即使白名单残留也必须跳过。
            if (!playerTask.getPreferencesSnapshot().allowsRestock(playerSlot)) {
                si++;
                containerIndex = 0;
                continue;
            }
            // 实时检查槽位资格
            ItemStack currentItem = ContainerTransaction.cloneOrNull(player.getInventory().getItem(playerSlot));
            if (!whitelist.isEligible(playerSlot, currentItem)) {
                si++;
                containerIndex = 0;
                continue;
            }

            // 使用任务开始时快照的最大堆叠数作为目标，避免 datapack 运行期修改上限导致不一致
            int needed = whitelist.getMaxStackSize(playerSlot) - currentItem.getAmount();
            if (needed <= 0) {
                si++;
                containerIndex = 0;
                continue;
            }

            // 从当前 containerIndex 开始遍历容器，每个容器都计入预算
            int ci = containerIndex;
            while (ci < identities.size() && needed > 0) {
                // 预算检查（在容器事务之间）
                if (processed >= containersPerTick || System.nanoTime() - tickStart >= nanosPerTick) {
                    final int nextSi = si;
                    final int nextCi = ci;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player fp = Bukkit.getPlayer(playerTask.getPlayerUuid());
                        if (!registry.isValid(playerTask) || fp == null || !fp.isOnline() || fp.isDead()
                                || !fp.getWorld().getUID().equals(playerTask.getWorldUuid())) {
                            onDone.onCancelled();
                            return;
                        }
                        processSlotsBudgeted(eligibleSlots, nextSi, nextCi, identities,
                                playerTask, whitelist, stats, onDone);
                    });
                    return;
                }

                ContainerIdentity identity = identities.get(ci);
                ContainerTransaction.ValidationResult vr = transaction.validate(playerTask, identity);
                processed++;

                if (vr.failureResult == ContainerTransaction.Result.FAILED_HOOK_UNAVAILABLE) {
                    // 喵~防御：Hook 运行期失效，中止整个任务
                    onDone.onCancelled();
                    return;
                }

                if (vr.isValid()) {
                    Inventory containerInv = vr.inventory;
                    // 记录本容器操作前的已移动数量，用于判断是否实际参与
                    int itemsBeforeThisContainer = stats.itemsMoved;
                    // 使用 whitelist 的期望物品做 isSimilar 比较，避免 tick-yield 后 currentItem 数量不一致
                    ItemStack expectedItem = whitelist.getExpectedItem(playerSlot);
                    if (expectedItem == null) {
                        needed = 0;
                        break;
                    }
                    ContainerOutcome containerOutcome = transferFromContainer(player, containerInv, playerSlot,
                            expectedItem, needed, playerTask, whitelist, stats);
                    if (containerOutcome == ContainerOutcome.ABORT_TASK) {
                        // 喵~防御：库存事务无法安全恢复，立即中止整个任务。
                        onDone.onCancelled();
                        return;
                    }
                    if (containerOutcome == ContainerOutcome.SKIP_CONTAINER) {
                        stats.skipped++;
                    } else {
                        ItemStack updatedPlayerItem =
                                ContainerTransaction.cloneOrNull(player.getInventory().getItem(playerSlot));
                        if (updatedPlayerItem == null) {
                            needed = 0;
                        } else {
                            needed = whitelist.getMaxStackSize(playerSlot) - updatedPlayerItem.getAmount();
                        }
                    }
                    // 仅当本容器实际移动了物品时才计为"参与容器"。
                    if (stats.itemsMoved > itemsBeforeThisContainer) {
                        stats.containersUsed++;
                    }
                } else {
                    stats.skipped++;
                }
                ci++;
            }

            // 该槽位处理完毕，移到下一槽
            si++;
            containerIndex = 0;
        }

        // 所有原版目标槽位处理完毕后，按逻辑槽位升序处理 PlayerBackpack 容量内目标喵~
        processPlayerBackpackTargets(identities, playerTask, stats, onDone);
        // PlayerBackpack 分支自行在完成或取消时调用回调喵~
        return;
    }

    /**
     * 从一个已验证容器向当前玩家目标槽位转移同类物品
     *
     * @param player        执行补货的玩家
     * @param containerInv  已验证容器库存
     * @param playerSlot    玩家目标槽位
     * @param expectedItem  白名单期望物品
     * @param needed        当前还需要的数量
     * @param playerTask    当前玩家任务
     * @param whitelist     目标白名单
     * @param stats         累计统计
     * @return 当前容器处理结果
     */
    private ContainerOutcome transferFromContainer(Player player, Inventory containerInv,
                                                   int playerSlot, ItemStack expectedItem, int needed,
                                                   PlayerTask playerTask, RestockTargetWhitelist whitelist,
                                                   RestockStats stats) {
        for (int containerSlot = 0; containerSlot < containerInv.getSize() && needed > 0; containerSlot++) {
            ItemStack containerItem = ContainerTransaction.cloneOrNull(containerInv.getItem(containerSlot));
            if (containerItem == null || !containerItem.isSimilar(expectedItem)) {
                continue;
            }

            // 喵~防御：任务快照禁止补货时不得向该槽位提交事务。
            if (!playerTask.getPreferencesSnapshot().allowsRestock(playerSlot)) {
                return ContainerOutcome.CONTINUE;
            }
            // 喵~防御：每次提交前重新确认任务和目标槽位仍有效，避免事件或生命周期变化后写入。
            ItemStack submitPlayerItem = ContainerTransaction.cloneOrNull(player.getInventory().getItem(playerSlot));
            if (!registry.isValid(playerTask) || !whitelist.isEligible(playerSlot, submitPlayerItem)) {
                return ContainerOutcome.CONTINUE;
            }

            int canMove = Math.min(needed, containerItem.getAmount());
            if (canMove <= 0) {
                continue;
            }

            ContainerTransaction.CommitResult commitResult =
                    transaction.commitRestock(player, containerInv, playerSlot, containerSlot, canMove);
            if (commitResult.status == ContainerTransaction.CommitStatus.SUCCESS) {
                stats.itemsMoved += commitResult.movedAmount;
                needed -= commitResult.movedAmount;
                continue;
            }
            if (commitResult.status == ContainerTransaction.CommitStatus.RECOVERED) {
                return ContainerOutcome.SKIP_CONTAINER;
            }
            if (commitResult.status == ContainerTransaction.CommitStatus.FAILED_UNRECOVERABLE) {
                return ContainerOutcome.ABORT_TASK;
            }
        }
        return ContainerOutcome.CONTINUE;
    }


    // 处理 PlayerBackpack 容量内已有非满目标，排除空槽、满槽和 overflow 喵~
    private void processPlayerBackpackTargets(List<ContainerIdentity> identities,
                                              PlayerTask playerTask,
                                              RestockStats stats,
                                              RestockCallback onDone) {
        // 喵~防御：缺少跨域依赖时不读取或写入 PlayerBackpack 喵~
        if (crossStorageCoordinator == null || playerBackpackTaskContexts == null) {
            // 原版目标已完成，直接报告结果喵~
            onDone.onComplete(stats);
            return;
        }
        // 获取当前玩家唯一外部操作会话喵~
        PlayerBackpackTaskContext context = playerBackpackTaskContexts.get(playerTask.getPlayerUuid());
        // 会话缺失或关闭时不猜测背包目标状态喵~
        if (context == null || !context.isOpen()) {
            // 交给统一完成出口报告结果喵~
            onDone.onComplete(stats);
            return;
        }
        // 从开始快照建立稳定逻辑槽位列表喵~
        List<Integer> logicalSlots = new ArrayList<>();
        // 按快照顺序读取所有逻辑槽位喵~
        for (Integer logicalSlot : context.snapshot().items().navigableKeySet()) {
            // 喵~防御：拒绝非法和容量外槽位喵~
            if (logicalSlot != null && logicalSlot > 0 && logicalSlot <= context.snapshot().capacity()) {
                // 保存合法目标槽位喵~
                logicalSlots.add(logicalSlot);
            }
        }
        // 从第一个目标、容器和容器槽位开始分 tick 执行喵~
        processPlayerBackpackTargetCursor(identities, playerTask, stats, logicalSlots,
                0, 0, 0, onDone);
    }

    // 以可恢复 cursor 执行 PlayerBackpack 补货，完整 mutation 不跨 tick 喵~
    private void processPlayerBackpackTargetCursor(List<ContainerIdentity> identities,
                                                    PlayerTask playerTask,
                                                    RestockStats stats,
                                                    List<Integer> logicalSlots,
                                                    int logicalSlotIndex,
                                                    int containerIndex,
                                                    int containerSlotIndex,
                                                    RestockCallback onDone) {
        // 重新获取玩家，禁止跨 tick 保存 Bukkit 引用喵~
        Player player = Bukkit.getPlayer(playerTask.getPlayerUuid());
        // 喵~防御：玩家离线、死亡、换世界或任务失效时立即取消喵~
        if (!registry.isValid(playerTask) || player == null || !player.isOnline() || player.isDead()
                || !player.getWorld().getUID().equals(playerTask.getWorldUuid())) {
            // 交给命令层释放会话喵~
            onDone.onCancelled();
            return;
        }
        // 每个 tick 读取最新 PlayerBackpack 上下文喵~
        PlayerBackpackTaskContext context = playerBackpackTaskContexts.get(playerTask.getPlayerUuid());
        // 上下文关闭后停止新的跨域提交喵~
        if (context == null || !context.isOpen()) {
            // 无法确认状态时保守取消任务喵~
            onDone.onCancelled();
            return;
        }
        // 读取并归一化当前 tick 操作预算喵~
        int operationsPerTick = Math.max(1, playerTask.getConfigSnapshot().getSubmitContainersPerTick());
        // 读取并归一化当前 tick 纳秒预算喵~
        long nanosPerTick = Math.max(1L, playerTask.getConfigSnapshot().getSubmitNanosPerTick());
        // 创建当前 tick 的预算起点喵~
        SubmissionBudget budget = new SubmissionBudget(operationsPerTick, nanosPerTick, System.nanoTime());
        // 使用局部 cursor 变量推进本 tick 工作喵~
        int currentLogicalSlotIndex = logicalSlotIndex;
        // 使用局部容器 cursor 变量推进本 tick 工作喵~
        int currentContainerIndex = containerIndex;
        // 使用局部容器槽位 cursor 变量推进本 tick 工作喵~
        int currentContainerSlotIndex = containerSlotIndex;
        // 遍历所有目标逻辑槽位喵~
        while (currentLogicalSlotIndex < logicalSlots.size()) {
            // 读取最新目标快照，避免跨 tick 使用旧 ItemStack 喵~
            int logicalSlot = logicalSlots.get(currentLogicalSlotIndex);
            // 克隆当前目标物品喵~
            ItemStack targetItem = ContainerTransaction.cloneOrNull(context.snapshot().itemAt(logicalSlot));
            // 喵~防御：空目标和已满目标不创建新物品喵~
            if (targetItem == null || targetItem.getAmount() >= targetItem.getMaxStackSize()) {
                // 推进目标并重置容器 cursor 喵~
                currentLogicalSlotIndex++;
                currentContainerIndex = 0;
                currentContainerSlotIndex = 0;
                continue;
            }
            // 遍历候选容器喵~
            while (currentContainerIndex < identities.size()) {
                // 喵~防御：预算耗尽时保存安全 cursor 喵~
                if (budget.exhausted(System.nanoTime())) {
                    // 下一 tick 重新验证后继续喵~
                    schedulePlayerBackpackTargetContinuation(identities, playerTask, stats, logicalSlots,
                            currentLogicalSlotIndex, currentContainerIndex, currentContainerSlotIndex, onDone);
                    return;
                }
                // 验证容器和 Hook 喵~
                ContainerTransaction.ValidationResult validation = transaction.validate(
                        playerTask, identities.get(currentContainerIndex));
                // Hook 不可用时立即取消喵~
                if (validation.failureResult == ContainerTransaction.Result.FAILED_HOOK_UNAVAILABLE) {
                    // 释放任务会话喵~
                    onDone.onCancelled();
                    return;
                }
                // 失效容器跳过喵~
                if (!validation.isValid()) {
                    // 推进容器并重置槽位喵~
                    currentContainerIndex++;
                    currentContainerSlotIndex = 0;
                    continue;
                }
                // 读取本次已验证容器库存喵~
                Inventory inventory = validation.inventory;
                // 扫描容器槽位喵~
                while (currentContainerSlotIndex < inventory.getSize()) {
                    // 每个槽位边界检查纳秒预算喵~
                    if (budget.exhausted(System.nanoTime())) {
                        // 从当前槽位保存 cursor 喵~
                        schedulePlayerBackpackTargetContinuation(identities, playerTask, stats, logicalSlots,
                                currentLogicalSlotIndex, currentContainerIndex, currentContainerSlotIndex, onDone);
                        return;
                    }
                    // 读取来源槽位镜像喵~
                    ItemStack sourceItem = ContainerTransaction.cloneOrNull(
                            inventory.getItem(currentContainerSlotIndex));
                    // 空槽或不同物品不能补货喵~
                    if (sourceItem == null || !sourceItem.isSimilar(targetItem)) {
                        // 推进来源槽位喵~
                        currentContainerSlotIndex++;
                        continue;
                    }
                    // 计算目标剩余容量喵~
                    int needed = targetItem.getMaxStackSize() - targetItem.getAmount();
                    // 计算本次移动数量喵~
                    int amount = Math.min(needed, sourceItem.getAmount());
                    // 喵~防御：数量非法时跳过来源槽位喵~
                    if (amount <= 0) {
                        // 推进来源槽位喵~
                        currentContainerSlotIndex++;
                        continue;
                    }
                    // 执行完整跨域 mutation，不在内部让出 tick 喵~
                    CrossStorageMutationCoordinator.Result result = crossStorageCoordinator.restock(
                            context, inventory, currentContainerSlotIndex, logicalSlot, amount);
                    // 完整 mutation 完成后才计入操作预算喵~
                    budget.markOperation();
                    // 成功后刷新目标镜像和统计喵~
                    if (result.status() == CrossStorageMutationCoordinator.Status.SUCCESS) {
                        // 统计协调器确认的实际移动数量喵~
                        stats.itemsMoved += result.movedAmount();
                        // 当前容器实际参与补货，统计容器使用次数喵~
                        if (result.movedAmount() > 0) {
                            // 按规范身份统计实际参与容器，避免 PB 多次写入重复计数喵~
                            stats.markContainerUsed(identities.get(currentContainerIndex));
                        }
                        // 读取 provider 返回后的最新目标喵~
                        targetItem = ContainerTransaction.cloneOrNull(context.snapshot().itemAt(logicalSlot));
                        // 目标满后进入下一个逻辑槽位喵~
                        if (targetItem == null || targetItem.getAmount() >= targetItem.getMaxStackSize()) {
                            // 推进目标并重置 cursor 喵~
                            currentLogicalSlotIndex++;
                            currentContainerIndex = 0;
                            currentContainerSlotIndex = 0;
                            break;
                        }
                        // 目标未满，重新检查当前来源槽位喵~
                        continue;
                    }
                    // 已恢复时跳过当前容器喵~
                    if (result.status() == CrossStorageMutationCoordinator.Status.RECOVERED) {
                        // 记录跳过并推进容器喵~
                        stats.skipped++;
                        currentContainerIndex++;
                        currentContainerSlotIndex = 0;
                        break;
                    }
                    // 不可恢复状态立即取消任务喵~
                    if (result.status() == CrossStorageMutationCoordinator.Status.FAILED_UNRECOVERABLE) {
                        // 保守停止后续 mutation 喵~
                        onDone.onCancelled();
                        return;
                    }
                    // 未提交结果推进槽位，等待后续候选喵~
                    currentContainerSlotIndex++;
                }
                // 当前容器扫描结束后推进容器喵~
                if (currentLogicalSlotIndex >= logicalSlots.size()
                        || currentContainerSlotIndex >= inventory.getSize()) {
                    // 重置槽位并推进容器喵~
                    currentContainerIndex++;
                    currentContainerSlotIndex = 0;
                }
            }
            // 所有容器均无法补货时推进目标喵~
            currentLogicalSlotIndex++;
            currentContainerIndex = 0;
            currentContainerSlotIndex = 0;
        }
        // 全部 PlayerBackpack 目标处理完毕喵~
        onDone.onComplete(stats);
    }

    // 调度下一 tick 继续 PlayerBackpack 补货，并重新创建预算喵~
    private void schedulePlayerBackpackTargetContinuation(List<ContainerIdentity> identities,
                                                          PlayerTask playerTask,
                                                          RestockStats stats,
                                                          List<Integer> logicalSlots,
                                                          int logicalSlotIndex,
                                                          int containerIndex,
                                                          int containerSlotIndex,
                                                          RestockCallback onDone) {
        // 只在 Bukkit 主线程调度下一次 cursor 喵~
        Bukkit.getScheduler().runTask(plugin, () -> processPlayerBackpackTargetCursor(identities, playerTask,
                stats, logicalSlots, logicalSlotIndex, containerIndex, containerSlotIndex, onDone));
    }

    public static class RestockStats {
        /** 成功补充的物品总数 */
        public int itemsMoved;
        // 当前任务中成功使用过的容器规范键，避免 PB 多次 mutation 重复统计喵~
        private final Set<String> usedContainerKeys = new HashSet<>();
        /** 实际参与的容器数 */
        public int containersUsed;
        /** 已跳过的容器数 */
        public int skipped;

        // 成功使用容器时按 canonical key 去重统计喵~
        private void markContainerUsed(ContainerIdentity identity) {
            // 喵~防御：容器身份为空时无法安全统计喵~
            if (identity == null) {
                return;
            }
            // 只有首次成功写入该容器才增加统计喵~
            if (usedContainerKeys.add(identity.canonicalKey())) {
                containersUsed++;
            }
        }
    }

    /** 补货操作完成回调接口 */
    public interface RestockCallback {
        /** 完成时调用 */
        void onComplete(RestockStats stats);
        /** 任务被取消时调用 */
        void onCancelled();
    }
}
