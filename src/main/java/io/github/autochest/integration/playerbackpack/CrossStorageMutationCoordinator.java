package io.github.autochest.integration.playerbackpack;

// 导入 PlayerBackpack mutation 方向枚举喵~
import com.playerbackpack.api.BackpackMutationDirection;
// 导入 PlayerBackpack mutation 请求模型喵~
import com.playerbackpack.api.BackpackMutationRequest;
// 导入 PlayerBackpack mutation 结果模型喵~
import com.playerbackpack.api.BackpackMutationResult;
// 导入 PlayerBackpack 快照模型喵~
import com.playerbackpack.api.BackpackSnapshotView;
// 导入 AutoChest 容器事务共享的物品复制工具喵~
import io.github.autochest.service.ContainerTransaction;
// 导入 Bukkit 库存类型喵~
import org.bukkit.inventory.Inventory;
// 导入 Bukkit 物品类型喵~
import org.bukkit.inventory.ItemStack;
// 导入 UUID 生成幂等 mutation 身份喵~
import java.util.UUID;
// 导入日志以输出不可恢复审计喵~
import java.util.logging.Logger;
// 导入日志级别以标记严重不确定状态喵~
import java.util.logging.Level;

// 协调 PlayerBackpack 单槽 CAS 与 Bukkit 容器单槽精确写入喵~
public final class CrossStorageMutationCoordinator {

    // 声明单次跨域提交的最终状态喵~
    public enum Status {
        // 表示两侧均已精确提交喵~
        SUCCESS,
        // 表示提交前状态不再匹配，未写入 Bukkit 容器喵~
        SKIPPED,
        // 表示 PlayerBackpack 已改动但 Bukkit 失败且已安全补偿喵~
        RECOVERED,
        // 表示状态不确定，调用方必须立即中止任务喵~
        FAILED_UNRECOVERABLE
    }

    // 返回一次跨域移动的状态与实际移动数量喵~
    public record Result(Status status, int movedAmount) {
        // 校验结果状态与移动数量喵~
        public Result {
            // 喵~防御：状态不能为空且数量不能为负喵~
            if (status == null || movedAmount < 0) {
                // 拒绝非法协调结果喵~
                throw new IllegalArgumentException("跨域协调结果非法喵~");
            }
        }
    }

    // 保存不可恢复审计日志喵~
    private final Logger logger;

    // 创建协调器并校验日志依赖喵~
    public CrossStorageMutationCoordinator(Logger logger) {
        // 喵~防御：日志不能为空，否则无法记录严重双域不确定状态喵~
        if (logger == null) {
            // 拒绝没有审计出口的协调器喵~
            throw new IllegalArgumentException("跨域协调器日志不能为空喵~");
        }
        // 保存审计日志喵~
        this.logger = logger;
    }

    // 将 PlayerBackpack 来源扣除后写入 Bukkit 容器目标喵~
    public Result deposit(PlayerBackpackTaskContext context, Inventory containerInventory,
                          int containerSlot, int logicalSlot, int amount) {
        // 喵~防御：上下文、容器和槽位数量必须有效喵~
        if (!isValidRequest(context, containerInventory, containerSlot, logicalSlot, amount)) {
            // 返回未提交结果喵~
            return skipped();
        }
        // 读取当前已确认快照中的 PlayerBackpack 来源镜像喵~
        BackpackSnapshotView currentSnapshot = context.snapshot();
        // 克隆来源物品以隔离 API 返回引用喵~
        ItemStack sourceBefore = ContainerTransaction.cloneOrNull(currentSnapshot.itemAt(logicalSlot));
        // 克隆 Bukkit 容器目标镜像喵~
        ItemStack targetBefore = ContainerTransaction.cloneOrNull(containerInventory.getItem(containerSlot));
        // 验证来源、目标相似性和容量喵~
        if (!canTransfer(sourceBefore, targetBefore, amount)) {
            // 不触碰任一存储域喵~
            return skipped();
        }
        // 从来源 before-image 构造扣除后的 PlayerBackpack 镜像喵~
        ItemStack sourceAfter = decrement(sourceBefore, amount);
        // 从目标 before-image 构造增加后的 Bukkit 容器镜像喵~
        ItemStack targetAfter = increment(sourceBefore, targetBefore, amount);
        // 喵~防御：先验证数量守恒，避免构造非法 after-image 喵~
        if (!isConserved(sourceBefore, targetBefore, sourceAfter, targetAfter)) {
            // 不提交任一域喵~
            return skipped();
        }
        // 生成唯一 mutation id，使重复调用不会重复扣除背包喵~
        UUID mutationId = UUID.randomUUID();
        // 构造受 revision 和完整 before-image 保护的来源扣除请求喵~
        BackpackMutationRequest request = new BackpackMutationRequest(mutationId, currentSnapshot.playerId(),
                BackpackMutationDirection.DEPOSIT, currentSnapshot.revision(), logicalSlot,
                sourceBefore, sourceAfter, amount);
        // 先持久化 PlayerBackpack 来源扣除，杜绝 SQLite 失败导致容器复制喵~
        BackpackMutationResult mutationResult = context.adapter().applyMutation(context.operation(), request);
        // 仅已提交或幂等重放结果才允许写 Bukkit 容器喵~
        if (!mutationResult.applied() || mutationResult.snapshot() == null
                || !context.advance(mutationResult.snapshot())) {
            // 冲突或存储失败时保持容器不变喵~
            return skipped();
        }
        try {
            // 写入容器目标的独立副本喵~
            containerInventory.setItem(containerSlot, targetAfter.clone());
        } catch (RuntimeException exception) {
            // Bukkit 写异常后尝试条件补偿 PlayerBackpack 来源喵~
            return compensateOrFail(context, containerInventory, containerSlot, targetBefore,
                    logicalSlot, sourceBefore, sourceAfter, amount, mutationId, exception);
        }
        // 精确复核 Bukkit 容器写后镜像喵~
        if (sameSlot(containerInventory.getItem(containerSlot), targetAfter)) {
            // 两域已完成严格守恒移动喵~
            return new Result(Status.SUCCESS, amount);
        }
        // 写后不一致时尝试条件补偿 PlayerBackpack 来源喵~
        return compensateOrFail(context, containerInventory, containerSlot, targetBefore,
                logicalSlot, sourceBefore, sourceAfter, amount, mutationId, null);
    }

    // 从 Bukkit 容器来源扣除后增加 PlayerBackpack 目标喵~
    public Result restock(PlayerBackpackTaskContext context, Inventory containerInventory,
                          int containerSlot, int logicalSlot, int amount) {
        // 喵~防御：上下文、容器和槽位数量必须有效喵~
        if (!isValidRequest(context, containerInventory, containerSlot, logicalSlot, amount)) {
            // 返回未提交结果喵~
            return skipped();
        }
        // 读取当前已确认快照中的 PlayerBackpack 目标镜像喵~
        BackpackSnapshotView currentSnapshot = context.snapshot();
        // 克隆 Bukkit 容器来源镜像喵~
        ItemStack sourceBefore = ContainerTransaction.cloneOrNull(containerInventory.getItem(containerSlot));
        // 克隆 PlayerBackpack 目标镜像喵~
        ItemStack targetBefore = ContainerTransaction.cloneOrNull(currentSnapshot.itemAt(logicalSlot));
        // 验证容器来源、背包目标相似性和容量喵~
        if (!canTransfer(sourceBefore, targetBefore, amount)) {
            // 不触碰任一存储域喵~
            return skipped();
        }
        // 构造 Bukkit 容器来源扣除 after-image 喵~
        ItemStack sourceAfter = decrement(sourceBefore, amount);
        // 构造 PlayerBackpack 目标增加 after-image 喵~
        ItemStack targetAfter = increment(sourceBefore, targetBefore, amount);
        // 喵~防御：先验证数量守恒喵~
        if (!isConserved(sourceBefore, targetBefore, sourceAfter, targetAfter)) {
            // 不提交任一域喵~
            return skipped();
        }
        // 生成唯一 mutation id 保护重放喵~
        UUID mutationId = UUID.randomUUID();
        // 构造受 revision 和完整 before-image 保护的背包目标增加请求喵~
        BackpackMutationRequest request = new BackpackMutationRequest(mutationId, currentSnapshot.playerId(),
                BackpackMutationDirection.RESTOCK, currentSnapshot.revision(), logicalSlot,
                targetBefore, targetAfter, amount);
        // 先持久化 PlayerBackpack 目标增加，随后才扣减 Bukkit 容器来源喵~
        BackpackMutationResult mutationResult = context.adapter().applyMutation(context.operation(), request);
        // API 失败时不得扣减容器来源喵~
        if (!mutationResult.applied() || mutationResult.snapshot() == null
                || !context.advance(mutationResult.snapshot())) {
            // 冲突或存储失败时保持容器不变喵~
            return skipped();
        }
        try {
            // source-first 写入 Bukkit 容器来源 after-image 喵~
            containerInventory.setItem(containerSlot, ContainerTransaction.cloneOrNull(sourceAfter));
        } catch (RuntimeException exception) {
            // Bukkit 写异常后尝试条件补偿 PlayerBackpack 目标喵~
            return compensateOrFail(context, containerInventory, containerSlot, sourceBefore,
                    logicalSlot, targetBefore, targetAfter, amount, mutationId, exception);
        }
        // 精确复核 Bukkit 容器来源写后镜像喵~
        if (sameSlot(containerInventory.getItem(containerSlot), sourceAfter)) {
            // 两域已完成严格守恒移动喵~
            return new Result(Status.SUCCESS, amount);
        }
        // 写后不一致时尝试条件补偿 PlayerBackpack 目标喵~
        return compensateOrFail(context, containerInventory, containerSlot, sourceBefore,
                logicalSlot, targetBefore, targetAfter, amount, mutationId, null);
    }

    // 尝试恢复 Bukkit 槽位并通过 API 条件恢复 PlayerBackpack 槽位喵~
    private Result compensateOrFail(PlayerBackpackTaskContext context, Inventory inventory, int bukkitSlot,
                                    ItemStack bukkitBefore, int logicalSlot, ItemStack backpackBefore,
                                    ItemStack backpackAfter, int amount, UUID originalMutationId,
                                    RuntimeException writeException) {
        // 记录当前 Bukkit 槽位，供审计判断是否仍属于本事务喵~
        ItemStack currentBukkitItem;
        try {
            // 读取写入失败后的实际容器镜像喵~
            currentBukkitItem = ContainerTransaction.cloneOrNull(inventory.getItem(bukkitSlot));
        } catch (RuntimeException readException) {
            // 喵~防御：连容器当前状态都无法读取时，状态必然不确定喵~
            logger.log(Level.SEVERE, "[AutoChest] 跨域失败后无法读取 Bukkit 槽位，必须人工 reconcile: mutation="
                    + originalMutationId + " logicalSlot=" + logicalSlot, readException);
            // 返回不可恢复状态并停止任务喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 只有仍然等于本事务 after-image 时才允许尝试回写 Bukkit before-image 喵~
        if (sameSlot(currentBukkitItem, backpackAfter)) {
            // 当前 API 缺少跨域事务统一 after-image，不能把 PlayerBackpack 物品误当成 Bukkit after-image 喵~
            logger.warning("[AutoChest] 跨域失败后的 Bukkit 槽位镜像与独立 after-image 不可证明一致，跳过无条件恢复喵~");
        }
        // 当前公开 API 不能表达空槽 expectedBefore，也没有 journal-backed compensate endpoint 喵~
        logger.log(Level.SEVERE, "[AutoChest] 跨域 Bukkit 写入失败但当前 PlayerBackpack API 无法执行条件补偿，已中止任务: mutation="
                + originalMutationId + " logicalSlot=" + logicalSlot + " amount=" + amount, writeException);
        // 返回不确定状态，调用方必须停止任务并释放操作会话喵~
        return new Result(Status.FAILED_UNRECOVERABLE, 0);
    }

    // 校验公共请求参数喵~
    private boolean isValidRequest(PlayerBackpackTaskContext context, Inventory inventory,
                                   int bukkitSlot, int logicalSlot, int amount) {
        // 只有会话仍打开、库存非空且槽位数量合法时才允许跨域 mutation 喵~
        return context != null && context.isOpen() && inventory != null
                && bukkitSlot >= 0 && bukkitSlot < inventory.getSize()
                && logicalSlot > 0 && amount > 0;
    }

    // 验证来源到目标可按给定数量移动喵~
    private boolean canTransfer(ItemStack source, ItemStack target, int amount) {
        // 来源必须存在并足够，目标必须为空或同类且有容量喵~
        return source != null && source.getAmount() >= amount
                && (target == null || target.isSimilar(source))
                && (target == null ? 0 : target.getAmount()) + amount <= source.getMaxStackSize();
    }

    // 从来源 before-image 构造扣除后的 after-image 喵~
    private ItemStack decrement(ItemStack before, int amount) {
        // 复制来源物品避免修改共享对象喵~
        ItemStack after = before.clone();
        // 扣除本次移动数量喵~
        after.setAmount(before.getAmount() - amount);
        // 数量归零统一表达为 null 空槽喵~
        return after.getAmount() <= 0 ? null : after;
    }

    // 从来源和目标 before-image 构造增加后的 after-image 喵~
    private ItemStack increment(ItemStack source, ItemStack target, int amount) {
        // 目标为空时从来源克隆物品身份，否则克隆已有同类目标喵~
        ItemStack after = target == null ? source.clone() : target.clone();
        // 增加本次移动数量喵~
        after.setAmount((target == null ? 0 : target.getAmount()) + amount);
        // 返回独立 after-image 喵~
        return after;
    }

    // 验证两个域的物品数量守恒与堆叠范围喵~
    private boolean isConserved(ItemStack sourceBefore, ItemStack targetBefore,
                                ItemStack sourceAfter, ItemStack targetAfter) {
        // 计算提交前总数量喵~
        int totalBefore = sourceBefore.getAmount() + (targetBefore == null ? 0 : targetBefore.getAmount());
        // 计算提交后总数量喵~
        int totalAfter = (sourceAfter == null ? 0 : sourceAfter.getAmount()) + targetAfter.getAmount();
        // 要求数量守恒且两端不超容量喵~
        return totalBefore == totalAfter
                && (sourceAfter == null || sourceAfter.getAmount() > 0)
                && targetAfter.getAmount() > 0
                && targetAfter.getAmount() <= targetAfter.getMaxStackSize();
    }

    // 精确比较两个库存槽位，null 与 AIR 均表示空槽喵~
    private boolean sameSlot(ItemStack actual, ItemStack expected) {
        // 规范化两个槽位镜像喵~
        ItemStack normalizedActual = ContainerTransaction.cloneOrNull(actual);
        // 规范化预期槽位镜像喵~
        ItemStack normalizedExpected = ContainerTransaction.cloneOrNull(expected);
        // 任一为空时要求两者均为空喵~
        if (normalizedActual == null || normalizedExpected == null) {
            // 返回统一空槽比较结果喵~
            return normalizedActual == null && normalizedExpected == null;
        }
        // 非空物品需完整 equals，数量差异不能通过喵~
        return normalizedActual.equals(normalizedExpected);
    }

    // 创建未提交的结果喵~
    private Result skipped() {
        // 所有提交前冲突都报告为零移动喵~
        return new Result(Status.SKIPPED, 0);
    }
}
