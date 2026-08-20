package io.github.autochest.scan;

import io.github.autochest.container.BlockPos;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.hook.CompositeAccessPolicy;
import io.github.autochest.hook.HookUnavailableException;
import io.github.autochest.task.PlayerTask;
import io.github.autochest.task.PlayerTaskRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.EnderChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.function.Consumer;

/**
 * 分 tick 容器扫描器
 * 按双预算（方块数 + 纳秒数）在每个 tick 推进坐标枚举
 * 只读取已加载区块，不加载新区块，不持有 Player 引用超过 tick 执行范围
 */
public class ScanTask implements Runnable {

    /** 执行扫描的玩家任务 */
    private final PlayerTask playerTask;

    /** 任务注册表，用于 isValid 检查 */
    private final PlayerTaskRegistry registry;

    /** 复合访问策略，扫描时过滤受保护容器 */
    private final CompositeAccessPolicy accessPolicy;

    /** 插件实例，用于调度和日志 */
    private final Plugin plugin;

    /** 扫描完成时的回调，在主线程调用 */
    private final Consumer<List<ContainerIdentity>> onComplete;

    /** 扫描被取消时的回调，在主线程调用 */
    private final Runnable onCancelled;

    // ===== 扫描状态 =====

    /** 当前扫描的坐标偏移 X（相对中心） */
    private int offsetX;

    /** 当前扫描的坐标偏移 Y */
    private int offsetY;

    /** 当前扫描的坐标偏移 Z */
    private int offsetZ;

    /** 扫描范围 X 半径 */
    private final int radiusX;

    /** 扫描范围 Y 半径 */
    private final int radiusY;

    /** 扫描范围 Z 半径 */
    private final int radiusZ;

    /** 已发现的容器，使用 canonicalKey 去重 */
    private final LinkedHashMap<String, ContainerIdentity> found = new LinkedHashMap<>();

    /** 是否已完成扫描 */
    private boolean finished = false;

    /**
     * 创建扫描任务
     *
     * @param playerTask   玩家任务
     * @param registry     任务注册表
     * @param accessPolicy 容器访问策略
     * @param plugin       插件实例
     * @param onComplete   扫描完成回调（传入排序后的容器列表）
     * @param onCancelled  扫描取消回调
     */
    public ScanTask(
            PlayerTask playerTask,
            PlayerTaskRegistry registry,
            CompositeAccessPolicy accessPolicy,
            Plugin plugin,
            Consumer<List<ContainerIdentity>> onComplete,
            Runnable onCancelled
    ) {
        this.playerTask = playerTask;
        this.registry = registry;
        this.accessPolicy = accessPolicy;
        this.plugin = plugin;
        this.onComplete = onComplete;
        this.onCancelled = onCancelled;

        this.radiusX = playerTask.getConfigSnapshot().getScanRadiusX();
        this.radiusY = playerTask.getConfigSnapshot().getScanRadiusY();
        this.radiusZ = playerTask.getConfigSnapshot().getScanRadiusZ();

        // 从偏移量 (-radiusX, -radiusY, -radiusZ) 开始枚举
        this.offsetX = -radiusX;
        this.offsetY = -radiusY;
        this.offsetZ = -radiusZ;
    }

    /**
     * 每 tick 执行一步扫描，由 BukkitScheduler.runTaskTimer 周期调用
     */
    @Override
    public void run() {
        // 检查任务是否仍然有效
        if (!registry.isValid(playerTask)) {
            cancel();
            return;
        }

        // 重新获取玩家，检查在线、世界和死亡状态
        Player player = Bukkit.getPlayer(playerTask.getPlayerUuid());
        if (player == null || !player.isOnline() || player.isDead()
                || !player.getWorld().getUID().equals(playerTask.getWorldUuid())) {
            cancel();
            return;
        }

        World world = player.getWorld();
        int centerX = playerTask.getCenterX();
        int centerY = playerTask.getCenterY();
        int centerZ = playerTask.getCenterZ();

        long blocksPerTick = playerTask.getConfigSnapshot().getScanBlocksPerTick();
        long nanosPerTick = playerTask.getConfigSnapshot().getScanNanosPerTick();
        long tickStart = System.nanoTime();
        long blocksChecked = 0;

        // 在预算内推进坐标枚举
        while (!finished) {
            // 检查预算（方块数和纳秒数双限制）
            if (blocksChecked >= blocksPerTick || System.nanoTime() - tickStart >= nanosPerTick) {
                // 预算耗尽，下个 tick 继续
                return;
            }

            int blockX = centerX + offsetX;
            int blockY = centerY + offsetY;
            int blockZ = centerZ + offsetZ;

            // 裁剪 Y 到世界合法高度范围，跳过越界坐标
            if (blockY >= world.getMinHeight() && blockY < world.getMaxHeight()) {
                // 检查区块是否已加载，不加载新区块
                if (world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                    Block block = world.getBlockAt(blockX, blockY, blockZ);
                    checkBlock(block, world, centerX, centerY, centerZ, player);
                }
            }

            blocksChecked++;

            // 推进三维坐标枚举：Z 最快，Y 次之，X 最慢
            offsetZ++;
            if (offsetZ > radiusZ) {
                offsetZ = -radiusZ;
                offsetY++;
                if (offsetY > radiusY) {
                    offsetY = -radiusY;
                    offsetX++;
                    if (offsetX > radiusX) {
                        // 枚举完毕
                        finished = true;
                    }
                }
            }
        }

        // 喵~防御：取消或失效后不再进入完成回调，避免重复规划和重复提示喵~
        if (finished) {
            // 取消路径已经由 onCancelled 处理，直接结束本次 tick 喵~
            return;
        }
        // 标记扫描已完成，周期调度器后续 tick 不再重复回调喵~
        finished = true;
        // 扫描完成，按本次任务冻结的玩家偏好过滤并稳定排序后回调。
        List<ContainerIdentity> sorted = ContainerOrdering.order(
                new ArrayList<>(found.values()), playerTask.getPreferencesSnapshot());
        onComplete.accept(sorted);
    }

    /**
     * 检查单个方块是否为可参与补货的容器
     *
     * @param block   目标方块
     * @param world   世界
     * @param cx      扫描中心 X
     * @param cy      扫描中心 Y
     * @param cz      扫描中心 Z
     * @param player  执行操作的玩家
     */
    private void checkBlock(Block block, World world, int cx, int cy, int cz, Player player) {
        BlockState state = block.getState();

        // 仅处理普通箱、陷阱箱、木桶、潜影盒和末影箱。
        if (!(state instanceof org.bukkit.block.Barrel)
                && !(state instanceof Chest)
                && !(state instanceof ShulkerBox)
                && !(state instanceof EnderChest)) {
            return;
        }

        ContainerIdentity identity;

        if (state instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();

            if (holder instanceof DoubleChest doubleChest) {
                // 双箱：解析两半坐标，两半区块均需已加载
                org.bukkit.inventory.InventoryHolder leftHolder = doubleChest.getLeftSide();
                org.bukkit.inventory.InventoryHolder rightHolder = doubleChest.getRightSide();

                if (!(leftHolder instanceof Chest leftChest)
                        || !(rightHolder instanceof Chest rightChest)) {
                    return;
                }

                Block leftBlock = leftChest.getBlock();
                Block rightBlock = rightChest.getBlock();

                // 检查双箱两半区块均已加载
                if (!world.isChunkLoaded(leftBlock.getX() >> 4, leftBlock.getZ() >> 4)
                        || !world.isChunkLoaded(rightBlock.getX() >> 4, rightBlock.getZ() >> 4)) {
                    return;
                }

                BlockPos posA = new BlockPos(world.getUID(), leftBlock.getX(), leftBlock.getY(), leftBlock.getZ());
                BlockPos posB = new BlockPos(world.getUID(), rightBlock.getX(), rightBlock.getY(), rightBlock.getZ());
                ContainerIdentity.ContainerType containerType = toContainerType(leftBlock.getType());
                if (containerType == null || leftBlock.getType() != rightBlock.getType()) {
                    return;
                }
                BlockPos center = new BlockPos(world.getUID(), cx, cy, cz);

                long distSq = ContainerIdentity.computeDistanceSquared(center, posA, posB);
                identity = new ContainerIdentity(posA, posB, containerType, distSq);
            } else {
                // 单箱
                BlockPos pos = new BlockPos(world.getUID(), block.getX(), block.getY(), block.getZ());
                ContainerIdentity.ContainerType containerType = toContainerType(block.getType());
                if (containerType == null) {
                    return;
                }
                BlockPos center = new BlockPos(world.getUID(), cx, cy, cz);
                identity = new ContainerIdentity(pos, containerType, pos.distanceSquared(center));
            }
        } else {
            // 潜影盒和末影箱均只能作为单方块容器处理。
            BlockPos pos = new BlockPos(world.getUID(), block.getX(), block.getY(), block.getZ());
            BlockPos center = new BlockPos(world.getUID(), cx, cy, cz);
            ContainerIdentity.ContainerType containerType = toContainerType(block.getType());
            if (containerType == null) {
                return;
            }
            identity = new ContainerIdentity(pos, containerType, pos.distanceSquared(center));
        }

        // 玩家黑名单优先于保护 Hook，避免对被明确排除种类执行无意义检查。
        if (!playerTask.getPreferencesSnapshot().allows(identity.getContainerType())) {
            return;
        }

        // 去重：已发现过的容器直接跳过
        String key = identity.canonicalKey();
        if (found.containsKey(key)) {
            return;
        }

        // Hook 检查：构建方块数组并检查访问权限
        try {
            Block[] blocks = buildBlocks(identity, world);
            if (blocks == null) {
                return;
            }
            if (accessPolicy.canAccess(player, blocks)) {
                found.put(key, identity);
            }
        } catch (HookUnavailableException e) {
            // Hook 不可用时中止整个扫描，由上层命令层处理
            cancel();
        } catch (Exception e) {
            // 喵~防御：其他异常静默跳过该容器
            plugin.getLogger().warning("[AutoChest] 扫描时访问策略异常: " + e.getMessage());
        }
    }

    /**
     * 将允许扫描的 Bukkit 材料转换为容器类型快照
     *
     * @param material 扫描时方块材料
     * @return 对应容器类型，不支持时返回 null
     */
    private ContainerIdentity.ContainerType toContainerType(Material material) {
        if (material == Material.CHEST) {
            return ContainerIdentity.ContainerType.CHEST;
        }
        if (material == Material.TRAPPED_CHEST) {
            return ContainerIdentity.ContainerType.TRAPPED_CHEST;
        }
        if (material == Material.BARREL) {
            return ContainerIdentity.ContainerType.BARREL;
        }
        if (isShulkerBoxMaterial(material)) {
            return ContainerIdentity.ContainerType.SHULKER_BOX;
        }
        if (material == Material.ENDER_CHEST) {
            return ContainerIdentity.ContainerType.ENDER_CHEST;
        }
        return null;
    }

    /**
     * 判断材料是否为原版 17 种潜影盒之一。
     *
     * @param material 待判断的方块材料
     * @return true 表示材料属于潜影盒系列
     */
    static boolean isShulkerBoxMaterial(Material material) {
        // 喵~防御：空材料不能匹配任何潜影盒。
        if (material == null) {
            return false;
        }
        // 通过材料名称统一识别未染色与 16 种染色潜影盒，避免漏写颜色变体。
        return material == Material.SHULKER_BOX || material.name().endsWith("_SHULKER_BOX");
    }
    /**
     * 根据容器身份构建对应的 Bukkit Block 数组
     * 双箱两半区块若有任一未加载则返回 null
     *
     * @param identity 容器身份
     * @param world    世界
     * @return 方块数组，或 null 表示跳过
     */
    private Block[] buildBlocks(ContainerIdentity identity, World world) {
        if (identity.isDoubleChest()) {
            BlockPos p = identity.getPrimaryPos();
            BlockPos s = identity.getSecondaryPos();
            if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)
                    || !world.isChunkLoaded(s.getX() >> 4, s.getZ() >> 4)) {
                return null;
            }
            return new Block[]{
                    world.getBlockAt(p.getX(), p.getY(), p.getZ()),
                    world.getBlockAt(s.getX(), s.getY(), s.getZ())
            };
        } else {
            BlockPos p = identity.getPrimaryPos();
            if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
                return null;
            }
            return new Block[]{world.getBlockAt(p.getX(), p.getY(), p.getZ())};
        }
    }

    /**
     * 取消扫描任务并触发取消回调
     */
    private void cancel() {
        // 喵~防御：取消回调必须幂等，避免 scheduler 重复触发任务释放和提示喵~
        if (finished) {
            // 已经完成或取消时不重复执行生命周期回调喵~
            return;
        }
        // 发布扫描终态，阻止当前或后续 tick 进入完成回调喵~
        finished = true;
        // 通知命令层释放任务资源喵~
        onCancelled.run();
    }
}
