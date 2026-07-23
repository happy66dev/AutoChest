package io.github.autochest.service;

import io.github.autochest.container.ContainerIdentity;
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
        this.transaction = transaction;
        this.registry = registry;
        this.plugin = plugin;
        this.logger = logger;
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
        if (eligibleSlots.isEmpty()) {
            onDone.onComplete(new RestockStats());
            return;
        }

        List<ContainerIdentity> identities = new ArrayList<>(plan.sortedContainers);

        processSlotsBudgeted(eligibleSlots, 0, 0, identities, playerTask, whitelist,
                new RestockStats(), onDone);
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

        // 所有槽位处理完毕
        onDone.onComplete(stats);
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


    public static class RestockStats {
        /** 成功补充的物品总数 */
        public int itemsMoved;
        /** 实际参与的容器数 */
        public int containersUsed;
        /** 跳过的容器数 */
        public int skipped;
    }

    /** 补货操作完成回调接口 */
    public interface RestockCallback {
        /** 完成时调用 */
        void onComplete(RestockStats stats);
        /** 任务被取消时调用 */
        void onCancelled();
    }
}
