package io.github.autochest.service;

import io.github.autochest.container.ContainerIdentity;
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
    private enum ContainerOutcome {
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
        this.transaction = transaction;
        this.registry = registry;
        this.plugin = plugin;
        this.logger = logger;
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

        // 收集玩家主背包 9..35 的物品快照（用于计算剩余需求）
        // 每次让出后重新读取，保证剩余量基于实时状态
        Map<Integer, ItemStack> playerItems = new LinkedHashMap<>();
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack item = ContainerTransaction.cloneOrNull(player.getInventory().getItem(slot));
            if (item != null) {
                playerItems.put(slot, item);
            }
        }

        if (playerItems.isEmpty()) {
            // 主背包为空，无需操作
            if (phase == Phase.FILL_EXISTING) {
                // 继续第二阶段也无意义
                onDone.onComplete(stats);
            } else {
                onDone.onComplete(stats);
            }
            return;
        }

        List<ContainerIdentity> identities = new ArrayList<>(plan.sortedContainers);

        // 按预算逐容器处理，处理完后若有剩余让出 tick 继续
        processContainersBudgeted(phase, identities, player, world, playerTask, plan, stats, 0,
                () -> {
                    if (phase == Phase.FILL_EXISTING) {
                        // FILL_EXISTING 完成后进入 USE_EMPTY 阶段
                        Bukkit.getScheduler().runTask(plugin, () ->
                                runPhase(Phase.USE_EMPTY, plan, playerTask, onDone, stats));
                    } else {
                        onDone.onComplete(stats);
                    }
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

        // 遍历玩家主背包 9..35 的每个非空槽位。
        for (int playerSlot = 9; playerSlot <= 35; playerSlot++) {
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
                transferOutcome = depositToEmptySlots(player, containerInventory, playerSlot, playerItem, stats);
            } else {
                transferOutcome = ContainerOutcome.CONTINUE;
            }

            if (transferOutcome != ContainerOutcome.CONTINUE) {
                return transferOutcome;
            }
        }

        if (stats.itemsMoved > itemsBeforeThisContainer) {
            stats.containersUsed++;
        }
        return ContainerOutcome.CONTINUE;
    }

    /**
     * 向容器已有非满相似堆叠中填充物品
     *
     * @param player          玩家
     * @param containerInv    容器库存
     * @param playerSlot      玩家槽位
     * @param playerItem      玩家物品快照（仅用于 isSimilar 比较）
     * @param stats           统计数据
     * @return 当前容器处理结果
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
        /** 成功移动的物品总数 */
        public int itemsMoved;
        /** 实际参与的容器数（每次成功写入后累加）*/
        public int containersUsed;
        /** 跳过的容器数 */
        public int skipped;
    }

    /** 存入操作完成回调接口 */
    public interface DepositCallback {
        /** 完成时调用（包含无匹配的情况） */
        void onComplete(DepositStats stats);
        /** 任务被取消时调用 */
        void onCancelled();
    }
}
