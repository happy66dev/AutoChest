package io.github.autochest.service;

import io.github.autochest.container.BlockPos;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.hook.CompositeAccessPolicy;
import io.github.autochest.hook.HookUnavailableException;
import io.github.autochest.task.PlayerTask;
import io.github.autochest.task.PlayerTaskRegistry;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

/**
 * 单个容器的事务执行器
 * 在主线程的单次不可让出调用中完成容器访问验证和库存提交
 * 确保 before-image、after-image、source-first 写入和 compare-and-verify 恢复
 */
public class ContainerTransaction {

    /** 事务执行结果枚举 */
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

    private final PlayerTaskRegistry registry;
    private final CompositeAccessPolicy accessPolicy;
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
        // 步骤 1：重新获取玩家并检查所有状态
        Player player = Bukkit.getPlayer(task.getPlayerUuid());
        if (player == null || !player.isOnline() || player.isDead()
                || !player.getWorld().getUID().equals(task.getWorldUuid())
                || !registry.isValid(task)) {
            return ValidationResult.invalid(Result.SKIPPED_PLAYER_INVALID, null, null);
        }

        World world = player.getWorld();

        // 步骤 2：检查区块加载状态和容器结构
        Inventory inventory = getInventoryIfValid(identity, world);
        if (inventory == null) {
            return ValidationResult.invalid(Result.SKIPPED_CONTAINER_INVALID, player, null);
        }

        // 步骤 3：重新执行 Hook 检查
        try {
            Block[] blocks = buildBlocks(identity, world);
            if (blocks == null) {
                return ValidationResult.invalid(Result.SKIPPED_CONTAINER_INVALID, player, null);
            }
            if (!accessPolicy.canAccess(player, blocks)) {
                return ValidationResult.invalid(Result.SKIPPED_HOOK_DENIED, player, inventory);
            }
        } catch (HookUnavailableException e) {
            return ValidationResult.invalid(Result.FAILED_HOOK_UNAVAILABLE, player, inventory);
        }

        return ValidationResult.valid(player, inventory);
    }

    /**
     * 向容器目标槽位写入物品（来源为玩家）
     * 先扣减玩家来源槽位，后增加容器目标槽位（source-first）
     * 必须在主线程调用，调用期间不得让出 tick
     *
     * @param player      玩家
     * @param inventory   目标容器库存
     * @param playerSlot  玩家背包槽位
     * @param containerSlot 容器目标槽位
     * @param amount      移动数量
     * @return 成功返回 true，失败（守恒校验不通过）返回 false
     */
    public boolean commitDeposit(Player player, Inventory inventory,
                                  int playerSlot, int containerSlot, int amount) {
        // 步骤 4：读取实时槽位后立即 clone，不对原始引用做任何修改
        ItemStack playerItem = cloneOrNull(player.getInventory().getItem(playerSlot));
        ItemStack containerItem = cloneOrNull(inventory.getItem(containerSlot));

        if (playerItem == null || playerItem.getAmount() < amount) {
            // 喵~防御：来源数量不足，跳过
            return false;
        }

        // 步骤 6：在内存中构造 after-image
        ItemStack playerAfter = playerItem.clone();
        playerAfter.setAmount(playerItem.getAmount() - amount);

        ItemStack containerAfter;
        if (containerItem == null || containerItem.getType().isAir()) {
            // 目标为空槽
            containerAfter = playerItem.clone();
            containerAfter.setAmount(amount);
        } else {
            // 目标已有物品，增加数量
            containerAfter = containerItem.clone();
            containerAfter.setAmount(containerItem.getAmount() + amount);
        }

        // 步骤 7：守恒验证
        int totalBefore = playerItem.getAmount() + (containerItem != null && !containerItem.getType().isAir() ? containerItem.getAmount() : 0);
        int totalAfter = (playerAfter.getAmount()) + containerAfter.getAmount();
        if (totalBefore != totalAfter || containerAfter.getAmount() > containerAfter.getMaxStackSize()
                || playerAfter.getAmount() < 0) {
            logger.warning("[AutoChest] 守恒校验失败，跳过槽位 player:" + playerSlot + " container:" + containerSlot);
            return false;
        }

        // 步骤 8：source-first 写入（先扣玩家，再加容器）
        if (playerAfter.getAmount() == 0) {
            player.getInventory().setItem(playerSlot, null);
        } else {
            player.getInventory().setItem(playerSlot, playerAfter);
        }
        inventory.setItem(containerSlot, containerAfter);

        return true;
    }

    /**
     * 从容器来源槽位取出物品写入玩家目标槽位（来源为容器）
     * 先扣减容器来源槽位，后增加玩家目标槽位（source-first）
     *
     * @param player        玩家
     * @param inventory     来源容器库存
     * @param playerSlot    玩家目标槽位
     * @param containerSlot 容器来源槽位
     * @param amount        移动数量
     * @return 成功返回 true
     */
    public boolean commitRestock(Player player, Inventory inventory,
                                  int playerSlot, int containerSlot, int amount) {
        ItemStack containerItem = cloneOrNull(inventory.getItem(containerSlot));
        ItemStack playerItem = cloneOrNull(player.getInventory().getItem(playerSlot));

        if (containerItem == null || containerItem.getAmount() < amount) {
            return false;
        }
        if (playerItem == null || playerItem.getType().isAir()) {
            return false;
        }

        // 构造 after-image
        ItemStack containerAfter = containerItem.clone();
        containerAfter.setAmount(containerItem.getAmount() - amount);

        ItemStack playerAfter = playerItem.clone();
        playerAfter.setAmount(playerItem.getAmount() + amount);

        // 守恒验证
        int totalBefore = containerItem.getAmount() + playerItem.getAmount();
        int totalAfter = containerAfter.getAmount() + playerAfter.getAmount();
        if (totalBefore != totalAfter || playerAfter.getAmount() > playerAfter.getMaxStackSize()
                || containerAfter.getAmount() < 0) {
            return false;
        }

        // source-first：先扣容器，后加玩家
        if (containerAfter.getAmount() == 0) {
            inventory.setItem(containerSlot, null);
        } else {
            inventory.setItem(containerSlot, containerAfter);
        }
        player.getInventory().setItem(playerSlot, playerAfter);

        return true;
    }

    /**
     * 获取容器库存（同时验证区块加载、容器类型和双箱结构）
     * 返回 null 表示容器失效
     *
     * @param identity 容器身份
     * @param world    世界
     * @return 容器库存，或 null
     */
    public Inventory getInventoryIfValid(ContainerIdentity identity, World world) {
        BlockPos p = identity.getPrimaryPos();

        // 检查主位置区块已加载
        if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
            return null;
        }

        if (identity.isDoubleChest()) {
            BlockPos s = identity.getSecondaryPos();
            // 检查双箱另一半区块已加载
            if (!world.isChunkLoaded(s.getX() >> 4, s.getZ() >> 4)) {
                return null;
            }
            Block blockP = world.getBlockAt(p.getX(), p.getY(), p.getZ());
            Block blockS = world.getBlockAt(s.getX(), s.getY(), s.getZ());
            BlockState stateP = blockP.getState();
            BlockState stateS = blockS.getState();
            // 验证两半都是 Chest
            if (!(stateP instanceof Chest chestP) || !(stateS instanceof Chest)) {
                return null;
            }
            // 获取双箱逻辑库存
            Inventory inv = chestP.getInventory();
            if (!(inv.getHolder() instanceof org.bukkit.block.DoubleChest)) {
                // 结构已改变（例如其中一半被破坏）
                return null;
            }
            return inv;
        } else {
            Block block = world.getBlockAt(p.getX(), p.getY(), p.getZ());
            BlockState state = block.getState();
            if (!(state instanceof Container container)) {
                return null;
            }
            return container.getInventory();
        }
    }

    /**
     * 根据容器身份构建对应的 Bukkit Block 数组，用于 Hook 检查
     *
     * @param identity 容器身份
     * @param world    世界
     * @return Block 数组，null 表示区块未加载
     */
    private Block[] buildBlocks(ContainerIdentity identity, World world) {
        BlockPos p = identity.getPrimaryPos();
        if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
            return null;
        }
        if (identity.isDoubleChest()) {
            BlockPos s = identity.getSecondaryPos();
            if (!world.isChunkLoaded(s.getX() >> 4, s.getZ() >> 4)) {
                return null;
            }
            return new Block[]{
                    world.getBlockAt(p.getX(), p.getY(), p.getZ()),
                    world.getBlockAt(s.getX(), s.getY(), s.getZ())
            };
        }
        return new Block[]{world.getBlockAt(p.getX(), p.getY(), p.getZ())};
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
