package io.github.autochest.service;

import io.github.autochest.container.BlockPos;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.hook.CompositeAccessPolicy;
import io.github.autochest.hook.HookUnavailableException;
import io.github.autochest.task.PlayerTask;
import io.github.autochest.task.PlayerTaskRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 单个容器的事务执行器
 * 在主线程的单次不可让出调用中完成容器访问验证和库存提交
 * 确保 before-image、after-image、source-first 写入和 compare-and-verify 恢复
 */
public class ContainerTransaction {

    /** 容器验证或事务的失败结果枚举 */
    public enum Result {
        /** 成功移动了物品 */
        SUCCESS,
        /** 玩家状态无效（离线/换世界/死亡/任务失效） */
        SKIPPED_PLAYER_INVALID,
        /** 容器失效（区块卸载/方块改变/双箱结构变化） */
        SKIPPED_CONTAINER_INVALID,
        /** Hook 拒绝访问 */
        SKIPPED_HOOK_DENIED,
        /** Hook 不可用（已安装但初始化失败），应终止整个任务 */
        FAILED_HOOK_UNAVAILABLE,
        /** 没有可移动的物品（物品不匹配或数量已满） */
        SKIPPED_NO_MATCH,
        /** 无法恢复的事务失败，应终止整个任务 */
        FAILED_UNRECOVERABLE
    }

    /** 单次库存提交的结果状态 */
    public enum CommitStatus {
        /** 两个槽位均已精确写成 after-image */
        SUCCESS,
        /** 提交前实时状态不再满足移动条件，未写入任何库存 */
        SKIPPED_NO_MATCH,
        /** 写后异常或复核不符，但两个槽位均已恢复为 before-image */
        RECOVERED,
        /** 无法安全恢复，必须立即终止整个任务 */
        FAILED_UNRECOVERABLE
    }

    /** 单次库存提交结果，成功时携带实际移动数量 */
    public static final class CommitResult {
        /** 本次提交的最终状态 */
        public final CommitStatus status;
        /** 仅成功时大于零的实际移动数量 */
        public final int movedAmount;

        private CommitResult(CommitStatus status, int movedAmount) {
            this.status = status;
            this.movedAmount = movedAmount;
        }

        /**
         * 创建成功结果
         *
         * @param movedAmount 实际移动数量
         * @return 成功结果
         */
        static CommitResult success(int movedAmount) {
            return new CommitResult(CommitStatus.SUCCESS, movedAmount);
        }

        /**
         * 创建未提交结果
         *
         * @return 无匹配结果
         */
        static CommitResult skipped() {
            return new CommitResult(CommitStatus.SKIPPED_NO_MATCH, 0);
        }

        /**
         * 创建已恢复结果
         *
         * @return 已恢复结果
         */
        static CommitResult recovered() {
            return new CommitResult(CommitStatus.RECOVERED, 0);
        }

        /**
         * 创建不可恢复结果
         *
         * @return 不可恢复结果
         */
        static CommitResult failed() {
            return new CommitResult(CommitStatus.FAILED_UNRECOVERABLE, 0);
        }
    }

    /** 玩家任务注册表，用于提交前复验任务状态 */
    private final PlayerTaskRegistry registry;

    /** 聚合后的可选保护插件访问策略 */
    private final CompositeAccessPolicy accessPolicy;

    /** 插件日志记录器，用于输出不可恢复事务审计 */
    private final Logger logger;

    /**
     * 创建容器事务执行器
     *
     * @param registry     任务注册表
     * @param accessPolicy 容器访问策略
     * @param logger       日志记录器
     */
    public ContainerTransaction(PlayerTaskRegistry registry, CompositeAccessPolicy accessPolicy, Logger logger) {
        this.registry = registry;
        this.accessPolicy = accessPolicy;
        this.logger = logger;
    }

    /**
     * 验证玩家状态和容器有效性，获取容器库存
     * 必须在主线程调用
     *
     * @param task     当前任务
     * @param identity 目标容器身份
     * @return 验证结果，包含玩家和库存；若验证失败则 result 非 null
     */
    public ValidationResult validate(PlayerTask task, ContainerIdentity identity) {
        // 步骤 1：重新获取玩家并检查所有状态。
        Player player = Bukkit.getPlayer(task.getPlayerUuid());
        if (player == null || !player.isOnline() || player.isDead()
                || !player.getWorld().getUID().equals(task.getWorldUuid())
                || !registry.isValid(task)) {
            return ValidationResult.invalid(Result.SKIPPED_PLAYER_INVALID, null, null);
        }

        // 步骤 2：检查区块加载状态和容器结构。
        World world = player.getWorld();
        Inventory inventory = getInventoryIfValid(identity, world);
        if (inventory == null) {
            return ValidationResult.invalid(Result.SKIPPED_CONTAINER_INVALID, player, null);
        }

        // 步骤 3：重新执行 Hook 检查。
        try {
            Block[] blocks = buildBlocks(identity, world);
            if (blocks == null) {
                return ValidationResult.invalid(Result.SKIPPED_CONTAINER_INVALID, player, null);
            }
            if (!accessPolicy.canAccess(player, blocks)) {
                return ValidationResult.invalid(Result.SKIPPED_HOOK_DENIED, player, inventory);
            }
        } catch (HookUnavailableException exception) {
            return ValidationResult.invalid(Result.FAILED_HOOK_UNAVAILABLE, player, inventory);
        }

        return ValidationResult.valid(player, inventory);
    }

    /**
     * 向容器目标槽位写入物品，来源为玩家背包
     *
     * @param player        执行操作的玩家
     * @param inventory     目标容器库存
     * @param playerSlot    玩家来源槽位
     * @param containerSlot 容器目标槽位
     * @param amount        请求移动数量
     * @return 精确提交或安全恢复后的结果
     */
    public CommitResult commitDeposit(Player player, Inventory inventory,
                                      int playerSlot, int containerSlot, int amount) {
        return commit(player.getInventory(), playerSlot, inventory, containerSlot, amount,
                "deposit", player.getUniqueId());
    }

    /**
     * 从容器来源槽位取出物品写入玩家目标槽位
     *
     * @param player        执行操作的玩家
     * @param inventory     来源容器库存
     * @param playerSlot    玩家目标槽位
     * @param containerSlot 容器来源槽位
     * @param amount        请求移动数量
     * @return 精确提交或安全恢复后的结果
     */
    public CommitResult commitRestock(Player player, Inventory inventory,
                                      int playerSlot, int containerSlot, int amount) {
        return commit(inventory, containerSlot, player.getInventory(), playerSlot, amount,
                "restock", player.getUniqueId());
    }

    /**
     * 以来源先扣、目标后增的固定顺序提交两个库存槽位
     * 写入后精确复核 after-image；异常或不一致时只恢复仍属于本事务的 after-image
     *
     * @param sourceInventory 来源库存
     * @param sourceSlot      来源槽位
     * @param targetInventory 目标库存
     * @param targetSlot      目标槽位
     * @param amount          请求移动数量
     * @param operation       审计用操作名称
     * @param playerUuid      审计用玩家 UUID
     * @return 提交结果
     */
    private CommitResult commit(Inventory sourceInventory, int sourceSlot,
                                Inventory targetInventory, int targetSlot, int amount,
                                String operation, java.util.UUID playerUuid) {
        // 喵~防御：无效数量绝不触碰库存槽位。
        if (amount <= 0) {
            return CommitResult.skipped();
        }

        // 步骤 4：读取实时槽位后立即 clone，避免修改 Bukkit 返回的原始引用。
        ItemStack sourceBefore = cloneOrNull(sourceInventory.getItem(sourceSlot));
        ItemStack targetBefore = cloneOrNull(targetInventory.getItem(targetSlot));
        if (!canTransfer(sourceBefore, targetBefore, amount)) {
            return CommitResult.skipped();
        }

        // 步骤 5：仅由 before-image 的克隆构造完整 after-image。
        ItemStack sourceAfter = sourceBefore.clone();
        sourceAfter.setAmount(sourceBefore.getAmount() - amount);
        sourceAfter = normalizeEmpty(sourceAfter);
        ItemStack targetAfter = targetBefore == null ? sourceBefore.clone() : targetBefore.clone();
        targetAfter.setAmount((targetBefore == null ? 0 : targetBefore.getAmount()) + amount);

        // 喵~防御：在写入前验证数量守恒、来源非负及目标容量。
        if (!isConserved(sourceBefore, targetBefore, sourceAfter, targetAfter)) {
            logger.warning("[AutoChest] 事务守恒校验失败，跳过 " + operation
                    + " player=" + playerUuid + " sourceSlot=" + sourceSlot + " targetSlot=" + targetSlot);
            return CommitResult.skipped();
        }

        try {
            // 步骤 6：source-first，事务期间绝不让出 tick。
            sourceInventory.setItem(sourceSlot, cloneOrNull(sourceAfter));
            targetInventory.setItem(targetSlot, targetAfter.clone());
        } catch (RuntimeException exception) {
            return recoverOrFail(sourceInventory, sourceSlot, sourceBefore, sourceAfter,
                    targetInventory, targetSlot, targetBefore, targetAfter,
                    operation, playerUuid, amount, exception);
        }

        // 步骤 7：写后逐槽精确比较，数量不同也必须视为失败。
        if (sameSlot(sourceInventory.getItem(sourceSlot), sourceAfter)
                && sameSlot(targetInventory.getItem(targetSlot), targetAfter)) {
            return CommitResult.success(amount);
        }

        return recoverOrFail(sourceInventory, sourceSlot, sourceBefore, sourceAfter,
                targetInventory, targetSlot, targetBefore, targetAfter,
                operation, playerUuid, amount, null);
    }

    /**
     * 判断两个实时槽位是否允许完成本次同类堆叠移动
     *
     * @param source 来源 before-image
     * @param target 目标 before-image，可为空
     * @param amount 请求移动数量
     * @return true 表示允许构造 after-image
     */
    private boolean canTransfer(ItemStack source, ItemStack target, int amount) {
        if (source == null || source.getAmount() < amount) {
            return false;
        }
        if (target != null && !target.isSimilar(source)) {
            return false;
        }
        int targetAmount = target == null ? 0 : target.getAmount();
        return targetAmount + amount <= source.getMaxStackSize();
    }

    /**
     * 验证 after-image 没有改变两个槽位合计数量，且两端均在合法范围
     *
     * @param sourceBefore 来源 before-image
     * @param targetBefore 目标 before-image
     * @param sourceAfter  来源 after-image
     * @param targetAfter  目标 after-image
     * @return true 表示守恒且容量合法
     */
    private boolean isConserved(ItemStack sourceBefore, ItemStack targetBefore,
                                ItemStack sourceAfter, ItemStack targetAfter) {
        int totalBefore = sourceBefore.getAmount() + (targetBefore == null ? 0 : targetBefore.getAmount());
        int totalAfter = (sourceAfter == null ? 0 : sourceAfter.getAmount()) + targetAfter.getAmount();
        return totalBefore == totalAfter
                && (sourceAfter == null || sourceAfter.getAmount() >= 0)
                && targetAfter.getAmount() >= 0
                && targetAfter.getAmount() <= targetAfter.getMaxStackSize();
    }

    /**
     * 尝试 compare-and-verify 恢复两个库存槽位
     * 仅恢复仍精确等于本事务 after-image 的槽位，避免覆盖外部变化
     *
     * @param sourceInventory 来源库存
     * @param sourceSlot      来源槽位
     * @param sourceBefore    来源 before-image
     * @param sourceAfter     来源 after-image
     * @param targetInventory 目标库存
     * @param targetSlot      目标槽位
     * @param targetBefore    目标 before-image
     * @param targetAfter     目标 after-image
     * @param operation       审计操作名称
     * @param playerUuid      审计玩家 UUID
     * @param amount          请求移动数量
     * @param writeException  写入异常，可为空
     * @return RECOVERED 或 FAILED_UNRECOVERABLE
     */
    private CommitResult recoverOrFail(Inventory sourceInventory, int sourceSlot,
                                       ItemStack sourceBefore, ItemStack sourceAfter,
                                       Inventory targetInventory, int targetSlot,
                                       ItemStack targetBefore, ItemStack targetAfter,
                                       String operation, java.util.UUID playerUuid, int amount,
                                       RuntimeException writeException) {
        try {
            restoreIfStillAfter(sourceInventory, sourceSlot, sourceAfter, sourceBefore);
            restoreIfStillAfter(targetInventory, targetSlot, targetAfter, targetBefore);
        } catch (RuntimeException restoreException) {
            logUnrecoverable(operation, playerUuid, sourceSlot, targetSlot, amount,
                    sourceBefore, sourceAfter, targetBefore, targetAfter,
                    sourceInventory.getItem(sourceSlot), targetInventory.getItem(targetSlot),
                    writeException, restoreException);
            return CommitResult.failed();
        }

        if (sameSlot(sourceInventory.getItem(sourceSlot), sourceBefore)
                && sameSlot(targetInventory.getItem(targetSlot), targetBefore)) {
            logger.warning("[AutoChest] 事务写后复核失败但已安全恢复: operation=" + operation
                    + " player=" + playerUuid + " sourceSlot=" + sourceSlot + " targetSlot=" + targetSlot);
            return CommitResult.recovered();
        }

        logUnrecoverable(operation, playerUuid, sourceSlot, targetSlot, amount,
                sourceBefore, sourceAfter, targetBefore, targetAfter,
                sourceInventory.getItem(sourceSlot), targetInventory.getItem(targetSlot),
                writeException, null);
        return CommitResult.failed();
    }

    /**
     * 当槽位仍由本事务 after-image 占据时才回写 before-image
     *
     * @param inventory     要恢复的库存
     * @param slot          要恢复的槽位
     * @param expectedAfter 本事务 after-image
     * @param before        原始 before-image
     */
    private void restoreIfStillAfter(Inventory inventory, int slot, ItemStack expectedAfter, ItemStack before) {
        if (sameSlot(inventory.getItem(slot), expectedAfter)) {
            inventory.setItem(slot, cloneOrNull(before));
        }
    }

    /**
     * 精确比较库存槽位；null 与 AIR 被视为同一种空槽
     *
     * @param actual   实时槽位物品
     * @param expected 预期槽位物品
     * @return true 表示完整 ItemStack（含数量）相同
     */
    private boolean sameSlot(ItemStack actual, ItemStack expected) {
        ItemStack normalizedActual = cloneOrNull(actual);
        ItemStack normalizedExpected = cloneOrNull(expected);
        if (normalizedActual == null || normalizedExpected == null) {
            return normalizedActual == null && normalizedExpected == null;
        }
        return normalizedActual.equals(normalizedExpected);
    }

    /**
     * 将 amount 为零的 after-image 规范化为空槽
     *
     * @param item 可能为空或数量为零的物品
     * @return 非空有效物品或 null
     */
    private ItemStack normalizeEmpty(ItemStack item) {
        if (item == null || item.getAmount() <= 0 || item.getType().isAir()) {
            return null;
        }
        return item;
    }

    /**
     * 记录无法安全恢复的高严重度事务审计日志
     */
    private void logUnrecoverable(String operation, java.util.UUID playerUuid,
                                  int sourceSlot, int targetSlot, int amount,
                                  ItemStack sourceBefore, ItemStack sourceAfter,
                                  ItemStack targetBefore, ItemStack targetAfter,
                                  ItemStack sourceActual, ItemStack targetActual,
                                  RuntimeException writeException, RuntimeException restoreException) {
        String auditMessage = "[AutoChest] 不可恢复库存事务，已中止任务: operation=" + operation
                + " player=" + playerUuid + " sourceSlot=" + sourceSlot + " targetSlot=" + targetSlot
                + " amount=" + amount + " sourceBefore=" + describe(sourceBefore)
                + " sourceAfter=" + describe(sourceAfter) + " sourceActual=" + describe(sourceActual)
                + " targetBefore=" + describe(targetBefore) + " targetAfter=" + describe(targetAfter)
                + " targetActual=" + describe(targetActual);
        logger.severe(auditMessage);
        if (writeException != null) {
            logger.log(Level.SEVERE, "[AutoChest] 原始库存写入异常", writeException);
        }
        if (restoreException != null) {
            logger.log(Level.SEVERE, "[AutoChest] 库存恢复异常", restoreException);
        }
    }

    /**
     * 将物品快照压缩为可读审计描述
     *
     * @param item 要描述的物品
     * @return null/AIR 或类型和数量描述
     */
    private String describe(ItemStack item) {
        ItemStack normalizedItem = cloneOrNull(item);
        if (normalizedItem == null) {
            return "empty";
        }
        return normalizedItem.getType() + "x" + normalizedItem.getAmount();
    }

    /**
     * 获取容器库存，同时验证区块加载、容器类型和双箱结构
     * 返回 null 表示容器失效
     *
     * @param identity 容器身份
     * @param world    世界
     * @return 容器库存，或 null
     */
    public Inventory getInventoryIfValid(ContainerIdentity identity, World world) {
        BlockPos primaryPosition = identity.getPrimaryPos();
        if (!world.isChunkLoaded(primaryPosition.getX() >> 4, primaryPosition.getZ() >> 4)) {
            return null;
        }

        if (identity.isDoubleChest()) {
            BlockPos secondaryPosition = identity.getSecondaryPos();
            if (!world.isChunkLoaded(secondaryPosition.getX() >> 4, secondaryPosition.getZ() >> 4)) {
                return null;
            }
            Block primaryBlock = world.getBlockAt(primaryPosition.getX(), primaryPosition.getY(), primaryPosition.getZ());
            Block secondaryBlock = world.getBlockAt(secondaryPosition.getX(), secondaryPosition.getY(), secondaryPosition.getZ());
            BlockState primaryState = primaryBlock.getState();
            BlockState secondaryState = secondaryBlock.getState();
            if (primaryBlock.getType() != toMaterial(identity.getContainerType())
                    || secondaryBlock.getType() != toMaterial(identity.getContainerType())
                    || !(primaryState instanceof Chest primaryChest) || !(secondaryState instanceof Chest)) {
                return null;
            }
            Inventory doubleChestInventory = primaryChest.getInventory();
            if (!(doubleChestInventory.getHolder() instanceof org.bukkit.block.DoubleChest doubleChest)
                    || !isSameDoubleChest(identity, world, doubleChest)) {
                // 喵~防御：双箱两半重组后，记录的 Hook 方块可能不再对应实际写入库存。
                return null;
            }
            return doubleChestInventory;
        }

        Block block = world.getBlockAt(primaryPosition.getX(), primaryPosition.getY(), primaryPosition.getZ());
        BlockState state = block.getState();
        if (block.getType() != toMaterial(identity.getContainerType())
                || !isExpectedSingleContainer(state, identity.getContainerType())) {
            return null;
        }
        return ((Container) state).getInventory();
    }

    /**
     * 将扫描时容器类型映射为提交阶段必须精确匹配的 Bukkit 材料
     *
     * @param containerType 扫描时容器类型
     * @return 对应 Bukkit 材料
     */
    private Material toMaterial(ContainerIdentity.ContainerType containerType) {
        return switch (containerType) {
            case CHEST -> Material.CHEST;
            case TRAPPED_CHEST -> Material.TRAPPED_CHEST;
            case BARREL -> Material.BARREL;
        };
    }

    /**
     * 验证单容器状态与扫描时的精确容器类型一致
     *
     * @param state         当前方块状态
     * @param containerType 扫描时容器类型
     * @return true 表示状态可安全提供同类库存
     */
    private boolean isExpectedSingleContainer(BlockState state,
                                              ContainerIdentity.ContainerType containerType) {
        return switch (containerType) {
            case CHEST, TRAPPED_CHEST -> state instanceof Chest;
            case BARREL -> state instanceof org.bukkit.block.Barrel;
        };
    }
    /**
     * 验证实时逻辑双箱的两半坐标仍与扫描时身份完全一致
     *
     * @param identity    扫描时保存的双箱身份
     * @param world       当前世界
     * @param doubleChest 实时逻辑双箱
     * @return true 表示两半无序坐标对完全一致
     */
    private boolean isSameDoubleChest(ContainerIdentity identity, World world,
                                      org.bukkit.block.DoubleChest doubleChest) {
        org.bukkit.inventory.InventoryHolder leftHolder = doubleChest.getLeftSide();
        org.bukkit.inventory.InventoryHolder rightHolder = doubleChest.getRightSide();
        if (!(leftHolder instanceof Chest leftChest) || !(rightHolder instanceof Chest rightChest)) {
            return false;
        }
        Block leftBlock = leftChest.getBlock();
        Block rightBlock = rightChest.getBlock();
        BlockPos leftPosition = new BlockPos(world.getUID(), leftBlock.getX(), leftBlock.getY(), leftBlock.getZ());
        BlockPos rightPosition = new BlockPos(world.getUID(), rightBlock.getX(), rightBlock.getY(), rightBlock.getZ());
        BlockPos primaryPosition = identity.getPrimaryPos();
        BlockPos secondaryPosition = identity.getSecondaryPos();
        return (leftPosition.equals(primaryPosition) && rightPosition.equals(secondaryPosition))
                || (leftPosition.equals(secondaryPosition) && rightPosition.equals(primaryPosition));
    }
    /**
     * 根据容器身份构建对应的 Bukkit Block 数组，用于 Hook 检查
     *
     * @param identity 容器身份
     * @param world    世界
     * @return Block 数组，null 表示区块未加载
     */
    private Block[] buildBlocks(ContainerIdentity identity, World world) {
        BlockPos primaryPosition = identity.getPrimaryPos();
        if (!world.isChunkLoaded(primaryPosition.getX() >> 4, primaryPosition.getZ() >> 4)) {
            return null;
        }
        if (identity.isDoubleChest()) {
            BlockPos secondaryPosition = identity.getSecondaryPos();
            if (!world.isChunkLoaded(secondaryPosition.getX() >> 4, secondaryPosition.getZ() >> 4)) {
                return null;
            }
            return new Block[]{
                    world.getBlockAt(primaryPosition.getX(), primaryPosition.getY(), primaryPosition.getZ()),
                    world.getBlockAt(secondaryPosition.getX(), secondaryPosition.getY(), secondaryPosition.getZ())
            };
        }
        return new Block[]{world.getBlockAt(primaryPosition.getX(), primaryPosition.getY(), primaryPosition.getZ())};
    }

    /**
     * 安全 clone 一个 ItemStack，null 或 AIR 返回 null
     *
     * @param item 原始物品
     * @return clone 或 null
     */
    public static ItemStack cloneOrNull(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return item.clone();
    }

    /**
     * 验证结果载体，包含验证状态和有效的玩家/库存引用
     */
    public static final class ValidationResult {
        /** 若非 null 表示验证失败的原因 */
        public final Result failureResult;
        /** 验证通过时的玩家对象 */
        public final Player player;
        /** 验证通过时的容器库存 */
        public final Inventory inventory;

        private ValidationResult(Result failureResult, Player player, Inventory inventory) {
            this.failureResult = failureResult;
            this.player = player;
            this.inventory = inventory;
        }

        static ValidationResult valid(Player player, Inventory inventory) {
            return new ValidationResult(null, player, inventory);
        }

        static ValidationResult invalid(Result reason, Player player, Inventory inventory) {
            return new ValidationResult(reason, player, inventory);
        }

        public boolean isValid() {
            return failureResult == null;
        }
    }
}
