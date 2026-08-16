package io.github.autochest.integration.playerbackpack;

// 导入 PlayerBackpack mutation 方向枚举喵~
import com.playerbackpack.api.BackpackMutationDirection;
// 导入 PlayerBackpack mutation 请求模型喵~
import com.playerbackpack.api.BackpackMutationRequest;
// 导入 PlayerBackpack 容器位置描述模型喵~
import com.playerbackpack.api.BackpackContainerDescriptor;
// 导入 PlayerBackpack 容器 before/after 镜像模型喵~
import com.playerbackpack.api.BackpackContainerMutation;
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
        // 从 Bukkit 库存位置构造可恢复的容器槽位描述喵~
        BackpackContainerMutation containerMutation = createContainerMutation(containerInventory, containerSlot, targetBefore, targetAfter);
        // 喵~防御：无法稳定定位容器时不得开始跨域提交喵~
        if (containerMutation == null) {
            // 返回未提交结果喵~
            return skipped();
        }
        // 先 durable 写入双域完整 before/after intent，崩溃后由 PlayerBackpack 启动恢复隔离喵~
        BackpackMutationResult preparationResult = context.adapter().prepareMutation(context.operation(), request, containerMutation);
        // 只有成功准备或完全相同的幂等重放才能修改 PlayerBackpack 喵~
        if (!preparationResult.applied()) {
            // 保持两侧物品不变喵~
            return skipped();
        }
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
            return compensateOrFail(context, containerInventory, containerSlot, targetBefore, targetAfter,
                    logicalSlot, sourceBefore, sourceAfter, amount, mutationId, BackpackMutationDirection.DEPOSIT, exception);
        }
        // 精确复核 Bukkit 容器写后镜像喵~
        if (sameSlot(containerInventory.getItem(containerSlot), targetAfter)) {
            // 容器精确提交后 durable 终结 journal，失败时不可声明跨域操作成功喵~
            if (context.adapter().markContainerApplied(context.operation(), mutationId)
                    != com.playerbackpack.api.BackpackOperationFailure.NONE) {
                // journal 未终结时保留可恢复记录并停止后续任务喵~
                logger.severe("[AutoChest] 容器已提交但 journal 终结失败，必须人工 reconcile: mutation=" + mutationId);
                // 返回不确定状态喵~
                return new Result(Status.FAILED_UNRECOVERABLE, 0);
            }
            // 两域已完成严格守恒移动喵~
            return new Result(Status.SUCCESS, amount);
        }
        // 写后不一致时尝试条件补偿 PlayerBackpack 来源喵~
        return compensateOrFail(context, containerInventory, containerSlot, targetBefore, targetAfter,
                logicalSlot, sourceBefore, sourceAfter, amount, mutationId, BackpackMutationDirection.DEPOSIT, null);
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
        // 从 Bukkit 库存位置构造可恢复的容器槽位描述喵~
        BackpackContainerMutation containerMutation = createContainerMutation(containerInventory, containerSlot, sourceBefore, sourceAfter);
        // 喵~防御：无法稳定定位容器时不得开始跨域提交喵~
        if (containerMutation == null) {
            // 返回未提交结果喵~
            return skipped();
        }
        // 先 durable 写入双域完整 before/after intent，崩溃后由 PlayerBackpack 启动恢复隔离喵~
        BackpackMutationResult preparationResult = context.adapter().prepareMutation(context.operation(), request, containerMutation);
        // 只有成功准备或完全相同的幂等重放才能修改 PlayerBackpack 喵~
        if (!preparationResult.applied()) {
            // 保持两侧物品不变喵~
            return skipped();
        }
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
            return compensateOrFail(context, containerInventory, containerSlot, sourceBefore, sourceAfter,
                    logicalSlot, targetBefore, targetAfter, amount, mutationId, BackpackMutationDirection.RESTOCK, exception);
        }
        // 精确复核 Bukkit 容器来源写后镜像喵~
        if (sameSlot(containerInventory.getItem(containerSlot), sourceAfter)) {
            // 容器精确提交后 durable 终结 journal，失败时不可声明跨域操作成功喵~
            if (context.adapter().markContainerApplied(context.operation(), mutationId)
                    != com.playerbackpack.api.BackpackOperationFailure.NONE) {
                // journal 未终结时保留可恢复记录并停止后续任务喵~
                logger.severe("[AutoChest] 容器已提交但 journal 终结失败，必须人工 reconcile: mutation=" + mutationId);
                // 返回不确定状态喵~
                return new Result(Status.FAILED_UNRECOVERABLE, 0);
            }
            // 两域已完成严格守恒移动喵~
            return new Result(Status.SUCCESS, amount);
        }
        // 写后不一致时尝试条件补偿 PlayerBackpack 目标喵~
        return compensateOrFail(context, containerInventory, containerSlot, sourceBefore, sourceAfter,
                logicalSlot, targetBefore, targetAfter, amount, mutationId, BackpackMutationDirection.RESTOCK, null);
    }

    // 尝试恢复 Bukkit 槽位并通过 API 条件恢复 PlayerBackpack 槽位喵~
    private Result compensateOrFail(PlayerBackpackTaskContext context, Inventory inventory, int bukkitSlot,
                                    ItemStack bukkitBefore, ItemStack bukkitAfter, int logicalSlot, ItemStack backpackBefore,
                                    ItemStack backpackAfter, int amount, UUID originalMutationId,
                                    BackpackMutationDirection originalDirection, RuntimeException writeException) {
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
        // 只有容器仍是本事务写入后的 after-image 时，才允许恢复容器 before-image 喵~
        if (!sameSlot(currentBukkitItem, bukkitAfter)) {
            // 容器处于第三种状态时无法证明归属，必须保留现场并隔离任务喵~
            logger.log(Level.SEVERE, "[AutoChest] 跨域失败后 Bukkit 槽位已被外部修改，必须人工 reconcile: mutation="
                    + originalMutationId + " logicalSlot=" + logicalSlot, writeException);
            // 返回不可恢复状态，禁止覆盖外部修改喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        try {
            // 先条件恢复容器 before-image，避免覆盖外部并发修改喵~
            inventory.setItem(bukkitSlot, ContainerTransaction.cloneOrNull(bukkitBefore));
        } catch (RuntimeException restoreException) {
            // 喵~防御：容器条件恢复异常时保留两域现场并记录审计喵~
            logger.log(Level.SEVERE, "[AutoChest] 跨域失败后 Bukkit before-image 恢复失败，必须人工 reconcile: mutation="
                    + originalMutationId + " logicalSlot=" + logicalSlot, restoreException);
            // 返回不可恢复状态喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 精确复核容器已回到事务 before-image 喵~
        if (!sameSlot(inventory.getItem(bukkitSlot), bukkitBefore)) {
            // 喵~防御：恢复后镜像不一致时禁止声明补偿成功喵~
            logger.log(Level.SEVERE, "[AutoChest] 跨域失败后 Bukkit before-image 复核失败，必须人工 reconcile: mutation="
                    + originalMutationId + " logicalSlot=" + logicalSlot);
            // 返回不可恢复状态喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 构造仅恢复本次 PlayerBackpack 槽位的独立补偿 mutation id 喵~
        UUID compensationMutationId = UUID.randomUUID();
        // 使用 PB 已提交 after-image 作为 CAS before-image，恢复到原始 before-image 喵~
        BackpackMutationRequest compensationRequest = new BackpackMutationRequest(
                compensationMutationId, context.operation().targetId(), originalDirection,
                context.snapshot().revision(), logicalSlot, backpackAfter, backpackBefore, amount);
        // 在容器已精确恢复后执行 journal-backed 条件补偿，避免产生新的重复物品喵~
        BackpackMutationResult compensationResult = context.adapter().applyCompensation(
                context.operation(), originalMutationId, compensationRequest);
        // 只有已提交或幂等重放且快照存在并严格推进上下文时才确认完全恢复喵~
        if (compensationResult.applied() && compensationResult.snapshot() != null
                && context.advance(compensationResult.snapshot())) {
            // 两侧均回到 before-image，报告已恢复且不计入成功搬运喵~
            return new Result(Status.RECOVERED, 0);
        }
        // 补偿未确认时保留 journal 供启动恢复，禁止继续执行任务喵~
        logger.log(Level.SEVERE, "[AutoChest] 跨域 PlayerBackpack 条件补偿失败，必须人工 reconcile: mutation="
                + originalMutationId + " logicalSlot=" + logicalSlot + " amount=" + amount, writeException);
        // 返回不确定状态，调用方必须停止任务并释放操作会话喵~
        return new Result(Status.FAILED_UNRECOVERABLE, 0);
    }

    // 从 Bukkit 容器库存构造 durable journal 使用的位置与槽位完整镜像喵~
    private BackpackContainerMutation createContainerMutation(Inventory inventory, int slot,
                                                              ItemStack before, ItemStack after) {
        // 喵~防御：库存或槽位无效时不能构造可恢复容器 identity 喵~
        if (inventory == null || slot < 0 || slot >= inventory.getSize()) {
            // 返回空值阻止跨域写入喵~
            return null;
        }
        // 获取 Bukkit 为方块容器提供的位置快照喵~
        org.bukkit.Location location = inventory.getLocation();
        // 喵~防御：玩家库存、虚拟库存或无世界位置的库存不能参与 durable 跨域事务喵~
        if (location == null || location.getWorld() == null) {
            // 返回空值保持双方物品不变喵~
            return null;
        }
        // 创建不持有 Bukkit 对象的容器位置描述喵~
        BackpackContainerDescriptor descriptor = new BackpackContainerDescriptor(
                location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), slot);
        // 返回深复制前后镜像的容器 mutation 意图喵~
        return new BackpackContainerMutation(descriptor, before, after);
    }

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
