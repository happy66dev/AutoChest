package io.github.autochest.integration.playerbackpack;

// 导入 PlayerBackpack mutation 方向枚举喵~
// 使用本地中立 mutation 方向模型，避免静态依赖可选 API 喵~
import io.github.autochest.integration.playerbackpack.BackpackMutationDirection;
// 使用本地中立 mutation 请求模型喵~
import io.github.autochest.integration.playerbackpack.BackpackMutationRequest;
// 使用本地中立容器位置描述模型喵~
import io.github.autochest.integration.playerbackpack.BackpackContainerDescriptor;
// 使用本地中立容器 mutation 模型喵~
import io.github.autochest.integration.playerbackpack.BackpackContainerMutation;
// 使用本地中立 mutation 结果模型喵~
import io.github.autochest.integration.playerbackpack.BackpackMutationResult;
// 使用本地中立快照模型喵~
import io.github.autochest.integration.playerbackpack.BackpackSnapshot;
// 导入 AutoChest 容器事务共享的物品复制工具喵~
import io.github.autochest.service.ContainerTransaction;
// 导入 Bukkit 库存类型喵~
import org.bukkit.inventory.Inventory;
// 导入 Bukkit 物品类型喵~
import org.bukkit.inventory.ItemStack;
// 导入 UUID 生成幂等 mutation 身份喵~
import java.util.UUID;
// 导入异步阶段类型以跨 tick 等待 actor mutation 喵~
import java.util.concurrent.CompletableFuture;
// 导入异步完成阶段类型以保持 Bukkit 主线程非阻塞喵~
import java.util.concurrent.CompletionStage;
// 导入执行器类型以将 Bukkit 对象处理切回主线程喵~
import java.util.concurrent.Executor;
// 导入日志级别以标记严重不确定状态喵~
import java.util.logging.Level;
// 导入日志以输出不可恢复审计喵~
import java.util.logging.Logger;

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
        // 喵~防御：v2 backend 尚未接入跨 tick coordinator，禁止误走 v1 同步写路径喵~
        if (context != null && context.usesAsyncBackend()) {
            // 返回不可恢复结果，让上层立即终止任务而不触碰 Bukkit 或切换 provider 喵~
            logger.severe("[AutoChest] v2 async backend 尚未接入跨域 coordinator，拒绝同步 mutation 喵~");
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 喵~防御：上下文、容器和槽位数量必须有效喵~
        if (!isValidRequest(context, containerInventory, containerSlot, logicalSlot, amount)) {
            // 返回未提交结果喵~
            return skipped();
        }
        // 读取当前已确认快照中的 PlayerBackpack 来源镜像喵~
        BackpackSnapshot currentSnapshot = context.snapshot();
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
        // 喵~防御：准备阶段空结果代表 journal 状态未知，禁止继续提交喵~
        if (preparationResult == null) {
            // 返回不可恢复并保留 provider 侧审计现场喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 只有成功准备或完全相同的幂等重放才能修改 PlayerBackpack 喵~
        if (!preparationResult.applied()) {
            // 保持两侧物品不变喵~
            return skipped();
        }
        // 先持久化 PlayerBackpack 来源扣除，杜绝 SQLite 失败导致容器复制喵~
        BackpackMutationResult mutationResult = context.adapter().applyMutation(context.operation(), request);
        // apply 未确认时先区分明确未提交与状态不确定喵~
        if (mutationResult == null) {
            // 喵~防御：provider 返回空结果无法判断是否已提交，必须停止任务喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 明确需要 reconcile 的 provider 结果不能静默跳过喵~
        if (mutationResult.status() == BackpackMutationResult.Status.RECONCILIATION_REQUIRED) {
            // 保留 provider journal 并阻止后续猜测性写入喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 明确未应用的服务失败或 CAS 冲突可以安全跳过喵~
        if (!mutationResult.applied()) {
            // 冲突或存储失败时保持容器不变喵~
            return skipped();
        }
        // apply 成功必须精确移动请求数量，否则双方状态无法按本事务镜像解释喵~
        if (mutationResult.movedAmount() != amount) {
            // 喵~防御：部分成功或零移动均视为不可恢复，禁止按请求数量写入另一域喵~
            logger.severe("[AutoChest] PlayerBackpack mutation 数量不匹配，必须人工 reconcile: mutation="
                    + mutationId);
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // provider 返回的 revision 与 snapshot 必须一致，且必须严格推进当前 revision 喵~
        if (mutationResult.snapshot() == null
                || mutationResult.newRevision() != mutationResult.snapshot().revision()
                || mutationResult.newRevision() <= currentSnapshot.revision()) {
            // 喵~防御：revision 契约不一致时禁止推进上下文，避免补偿 CAS 使用错误基线喵~
            return compensateAppliedMutation(context, mutationId, logicalSlot, sourceAfter, sourceBefore,
                    amount, mutationResult.newRevision(), BackpackMutationDirection.DEPOSIT);
        }
        // applyMutation 已成功且 revision 契约正确时推进上下文喵~
        if (!context.advance(mutationResult.snapshot())) {
            // 上下文推进失败时尝试条件补偿喵~
            return compensateAppliedMutation(context, mutationId, logicalSlot, sourceAfter, sourceBefore,
                    amount, mutationResult.newRevision(), BackpackMutationDirection.DEPOSIT);
        }
        // provider apply 期间容器可能被事件或其他玩家修改，写入前必须重新比对 before-image 喵~
        if (!sameSlot(containerInventory.getItem(containerSlot), targetBefore)) {
            // 喵~防御：拒绝覆盖外部修改，保留 journal 并停止任务喵~
            logger.severe("[AutoChest] deposit 写入前容器槽位已变化，必须人工 reconcile: mutation=" + mutationId);
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
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
                    != BackpackOperationFailure.NONE) {
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
        // 喵~防御：v2 backend 尚未接入跨 tick coordinator，禁止误走 v1 同步写路径喵~
        if (context != null && context.usesAsyncBackend()) {
            // 返回不可恢复结果，让上层立即终止任务而不触碰 Bukkit 或切换 provider 喵~
            logger.severe("[AutoChest] v2 async backend 尚未接入跨域 coordinator，拒绝同步 mutation 喵~");
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 喵~防御：上下文、容器和槽位数量必须有效喵~
        if (!isValidRequest(context, containerInventory, containerSlot, logicalSlot, amount)) {
            // 返回未提交结果喵~
            return skipped();
        }
        // 读取当前已确认快照中的 PlayerBackpack 目标镜像喵~
        BackpackSnapshot currentSnapshot = context.snapshot();
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
        // 喵~防御：准备阶段空结果代表 journal 状态未知，禁止继续提交喵~
        if (preparationResult == null) {
            // 返回不可恢复并保留 provider 侧审计现场喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 只有成功准备或完全相同的幂等重放才能修改 PlayerBackpack 喵~
        if (!preparationResult.applied()) {
            // 保持两侧物品不变喵~
            return skipped();
        }
        // 先持久化 PlayerBackpack 目标增加，随后才扣减 Bukkit 容器来源喵~
        BackpackMutationResult mutationResult = context.adapter().applyMutation(context.operation(), request);
        // apply 未确认时先区分明确未提交与状态不确定喵~
        if (mutationResult == null) {
            // 喵~防御：provider 返回空结果无法判断是否已提交，必须停止任务喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 明确需要 reconcile 的 provider 结果不能静默跳过喵~
        if (mutationResult.status() == BackpackMutationResult.Status.RECONCILIATION_REQUIRED) {
            // 保留 provider journal 并阻止后续猜测性写入喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 明确未应用的服务失败或 CAS 冲突可以安全跳过喵~
        if (!mutationResult.applied()) {
            // 冲突或存储失败时保持容器不变喵~
            return skipped();
        }
        // apply 成功必须精确移动请求数量，否则不能扣减 Bukkit 来源喵~
        if (mutationResult.movedAmount() != amount) {
            // 喵~防御：部分成功或零移动会破坏数量守恒，立即进入人工 reconcile 喵~
            logger.severe("[AutoChest] PlayerBackpack restock 数量不匹配，必须人工 reconcile: mutation="
                    + mutationId);
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // provider 返回的 revision 与 snapshot 必须一致，且必须严格推进当前 revision 喵~
        if (mutationResult.snapshot() == null
                || mutationResult.newRevision() != mutationResult.snapshot().revision()
                || mutationResult.newRevision() <= currentSnapshot.revision()) {
            // 喵~防御：revision 契约不一致时禁止推进上下文，避免补偿 CAS 使用错误基线喵~
            return compensateAppliedMutation(context, mutationId, logicalSlot, targetAfter, targetBefore,
                    amount, mutationResult.newRevision(), BackpackMutationDirection.RESTOCK);
        }
        // applyMutation 已成功且 revision 契约正确时推进上下文喵~
        if (!context.advance(mutationResult.snapshot())) {
            // 上下文推进失败时尝试条件补偿喵~
            return compensateAppliedMutation(context, mutationId, logicalSlot, targetAfter, targetBefore,
                    amount, mutationResult.newRevision(), BackpackMutationDirection.RESTOCK);
        }
        // provider apply 期间容器可能被外部修改，restock 写入前必须重新比对来源 before-image 喵~
        if (!sameSlot(containerInventory.getItem(containerSlot), sourceBefore)) {
            // 喵~防御：拒绝覆盖外部修改，保留 journal 并停止任务喵~
            logger.severe("[AutoChest] restock 写入前容器槽位已变化，必须人工 reconcile: mutation=" + mutationId);
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
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
                    != BackpackOperationFailure.NONE) {
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

    // 异步执行 PlayerBackpack 来源到 Bukkit 容器的双域 mutation，所有 stage 完成后回到主线程喵~
    public CompletionStage<Result> depositAsync(PlayerBackpackTaskContext context, Inventory containerInventory,
                                                int containerSlot, int logicalSlot, int amount,
                                                Executor mainThreadExecutor) {
        // 喵~防御：缺少 async context 或主线程执行器时拒绝同步回退喵~
        if (context == null || !context.usesAsyncBackend() || mainThreadExecutor == null
                || !isValidRequest(context, containerInventory, containerSlot, logicalSlot, amount)) {
            // 返回未提交结果喵~
            return CompletableFuture.completedFuture(skipped());
        }
        // 在 Bukkit 主线程 capture 两侧 before-image，之后只跨异步边界传纯 DTO 喵~
        BackpackSnapshot currentSnapshot = context.snapshot();
        ItemStack sourceBefore = ContainerTransaction.cloneOrNull(currentSnapshot.itemAt(logicalSlot));
        ItemStack targetBefore = ContainerTransaction.cloneOrNull(containerInventory.getItem(containerSlot));
        // 喵~防御：来源、目标或容量不满足时不建立 journal 喵~
        if (!canTransfer(sourceBefore, targetBefore, amount)) {
            return CompletableFuture.completedFuture(skipped());
        }
        ItemStack sourceAfter = decrement(sourceBefore, amount);
        ItemStack targetAfter = increment(sourceBefore, targetBefore, amount);
        if (!isConserved(sourceBefore, targetBefore, sourceAfter, targetAfter)) {
            return CompletableFuture.completedFuture(skipped());
        }
        UUID mutationId = UUID.randomUUID();
        BackpackMutationRequest request = new BackpackMutationRequest(mutationId, currentSnapshot.playerId(),
                BackpackMutationDirection.DEPOSIT, currentSnapshot.revision(), logicalSlot,
                sourceBefore, sourceAfter, amount);
        BackpackContainerMutation containerMutation = createContainerMutation(containerInventory, containerSlot,
                targetBefore, targetAfter);
        if (containerMutation == null) {
            return CompletableFuture.completedFuture(skipped());
        }
        PlayerBackpackAsyncAdapter asyncAdapter = context.asyncAdapter();
        // 顺序固定为 prepare → apply，provider stage 不在 Bukkit 主线程阻塞等待喵~
        return asyncAdapter.prepareMutationAsync(context.operation(), request, containerMutation, mainThreadExecutor)
                .thenComposeAsync(preparation -> {
                    if (preparation == null || !preparation.applied()) {
                        // prepare 非 APPLIED 状态不能证明 journal 未创建，未知状态必须终止任务喵~
                        return CompletableFuture.<Result>completedFuture(
                                new Result(Status.FAILED_UNRECOVERABLE, 0));
                    }
                    // apply 请求仍含 Bukkit ItemStack，必须由主线程编码后提交喵~
                    return asyncAdapter.applyMutationAsync(context.operation(), request, mainThreadExecutor)
                            .thenCompose(applied -> finishAsyncDeposit(context, containerInventory, containerSlot,
                                    logicalSlot, amount, mutationId, sourceBefore, sourceAfter, targetBefore, targetAfter,
                                    applied, mainThreadExecutor));
                }, mainThreadExecutor)
                .exceptionally(failure -> {
                    logger.log(Level.SEVERE, "[AutoChest] 异步 deposit mutation 失败，必须人工 reconcile 喵~", failure);
                    return new Result(Status.FAILED_UNRECOVERABLE, 0);
                });
    }

    // 在异步 PlayerBackpack apply 完成后切回主线程提交容器，再异步终结 journal 喵~
    private CompletionStage<Result> finishAsyncDeposit(PlayerBackpackTaskContext context, Inventory inventory,
                                                        int containerSlot, int logicalSlot, int amount,
                                                        UUID mutationId, ItemStack sourceBefore, ItemStack sourceAfter,
                                                        ItemStack targetBefore, ItemStack targetAfter,
                                                        BackpackMutationResult mutationResult,
                                                        Executor mainThreadExecutor) {
        if (mutationResult == null || mutationResult.status() == BackpackMutationResult.Status.RECONCILIATION_REQUIRED) {
            return CompletableFuture.completedFuture(new Result(Status.FAILED_UNRECOVERABLE, 0));
        }
        // provider 明确未应用时不执行补偿，保留两侧 before-image 喵~
        if (!mutationResult.applied()) {
            return CompletableFuture.completedFuture(skipped());
        }
        // mutation 成功后所有 Bukkit 校验、写入和补偿决策都在主线程执行喵~
        if (mutationResult.movedAmount() != amount
                || mutationResult.snapshot() == null || mutationResult.newRevision() <= context.snapshot().revision()
                || mutationResult.newRevision() != mutationResult.snapshot().revision()
                || !context.advance(mutationResult.snapshot())) {
            return compensateAsync(context, inventory, containerSlot, targetBefore, targetAfter, logicalSlot,
                    sourceBefore, sourceAfter, amount, mutationId, BackpackMutationDirection.DEPOSIT,
                    mutationResult.newRevision(), mainThreadExecutor);
        }
        if (!sameSlot(inventory.getItem(containerSlot), targetBefore)) {
            return compensateAsync(context, inventory, containerSlot, targetBefore, targetAfter, logicalSlot,
                    sourceBefore, sourceAfter, amount, mutationId, BackpackMutationDirection.DEPOSIT,
                    mutationResult.newRevision(), mainThreadExecutor);
        }
        try {
            inventory.setItem(containerSlot, targetAfter.clone());
        } catch (RuntimeException exception) {
            return compensateAsync(context, inventory, containerSlot, targetBefore, targetAfter, logicalSlot,
                    sourceBefore, sourceAfter, amount, mutationId, BackpackMutationDirection.DEPOSIT,
                    mutationResult.newRevision(), mainThreadExecutor);
        }
        if (!sameSlot(inventory.getItem(containerSlot), targetAfter)) {
            return compensateAsync(context, inventory, containerSlot, targetBefore, targetAfter, logicalSlot,
                    sourceBefore, sourceAfter, amount, mutationId, BackpackMutationDirection.DEPOSIT,
                    mutationResult.newRevision(), mainThreadExecutor);
        }
        return context.asyncAdapter().markContainerAppliedAsync(context.operation(), mutationId)
                .thenApply(failure -> failure == BackpackOperationFailure.NONE
                        ? new Result(Status.SUCCESS, amount)
                        : new Result(Status.FAILED_UNRECOVERABLE, 0));
    }

    // 异步 deposit 失败时先条件恢复 Bukkit 容器，再提交 PlayerBackpack 补偿喵~
    private CompletionStage<Result> compensateAsync(PlayerBackpackTaskContext context, Inventory inventory,
                                                     int containerSlot, ItemStack containerBefore, ItemStack containerAfter,
                                                     int logicalSlot, ItemStack backpackBefore, ItemStack backpackAfter,
                                                     int amount, UUID originalMutationId,
                                                     BackpackMutationDirection direction, long appliedRevision,
                                                     Executor mainThreadExecutor) {
        // 仅当容器仍是事务 after-image 时才允许恢复，第三种状态必须人工 reconcile 喵~
        if (!sameSlot(inventory.getItem(containerSlot), containerBefore)) {
            if (!sameSlot(inventory.getItem(containerSlot), containerAfter)) {
                return CompletableFuture.completedFuture(new Result(Status.FAILED_UNRECOVERABLE, 0));
            }
            try {
                inventory.setItem(containerSlot, ContainerTransaction.cloneOrNull(containerBefore));
            } catch (RuntimeException exception) {
                return CompletableFuture.completedFuture(new Result(Status.FAILED_UNRECOVERABLE, 0));
            }
        }
        if (!sameSlot(inventory.getItem(containerSlot), containerBefore)) {
            return CompletableFuture.completedFuture(new Result(Status.FAILED_UNRECOVERABLE, 0));
        }
        BackpackMutationRequest compensationRequest = new BackpackMutationRequest(UUID.randomUUID(),
                context.operation().targetId(), direction, appliedRevision, logicalSlot, backpackAfter,
                backpackBefore, amount);
        return context.asyncAdapter().applyCompensationAsync(context.operation(), originalMutationId,
                        compensationRequest, mainThreadExecutor)
                .thenApply(result -> result != null && result.applied() && result.movedAmount() == amount
                        && result.snapshot() != null && context.advance(result.snapshot())
                        ? new Result(Status.RECOVERED, 0)
                        : new Result(Status.FAILED_UNRECOVERABLE, 0));
    }

    // 异步执行 Bukkit 容器来源到 PlayerBackpack 目标的双域 mutation 喵~
    public CompletionStage<Result> restockAsync(PlayerBackpackTaskContext context, Inventory containerInventory,
                                                int containerSlot, int logicalSlot, int amount,
                                                Executor mainThreadExecutor) {
        // 喵~防御：仅固定 async backend 可进入异步 restock，其他输入 fail-closed 喵~
        if (context == null || !context.usesAsyncBackend() || mainThreadExecutor == null
                || !isValidRequest(context, containerInventory, containerSlot, logicalSlot, amount)) {
            return CompletableFuture.completedFuture(skipped());
        }
        // 主线程 capture 容器来源与 PlayerBackpack 目标 before-image 喵~
        BackpackSnapshot currentSnapshot = context.snapshot();
        ItemStack sourceBefore = ContainerTransaction.cloneOrNull(containerInventory.getItem(containerSlot));
        ItemStack targetBefore = ContainerTransaction.cloneOrNull(currentSnapshot.itemAt(logicalSlot));
        if (!canTransfer(sourceBefore, targetBefore, amount)) {
            return CompletableFuture.completedFuture(skipped());
        }
        ItemStack sourceAfter = decrement(sourceBefore, amount);
        ItemStack targetAfter = increment(sourceBefore, targetBefore, amount);
        if (!isConserved(sourceBefore, targetBefore, sourceAfter, targetAfter)) {
            return CompletableFuture.completedFuture(skipped());
        }
        UUID mutationId = UUID.randomUUID();
        BackpackMutationRequest request = new BackpackMutationRequest(mutationId, currentSnapshot.playerId(),
                BackpackMutationDirection.RESTOCK, currentSnapshot.revision(), logicalSlot,
                targetBefore, targetAfter, amount);
        BackpackContainerMutation containerMutation = createContainerMutation(containerInventory, containerSlot,
                sourceBefore, sourceAfter);
        if (containerMutation == null) {
            return CompletableFuture.completedFuture(skipped());
        }
        PlayerBackpackAsyncAdapter asyncAdapter = context.asyncAdapter();
        // 先 prepare durable intent，再 apply PlayerBackpack CAS，完成后回主线程提交容器扣减喵~
        return asyncAdapter.prepareMutationAsync(context.operation(), request, containerMutation, mainThreadExecutor)
                .thenComposeAsync(preparation -> {
                    if (preparation == null || !preparation.applied()) {
                        // prepare 非 APPLIED 状态不能证明 journal 未创建，未知状态必须终止任务喵~
                        return CompletableFuture.<Result>completedFuture(
                                new Result(Status.FAILED_UNRECOVERABLE, 0));
                    }
                    return asyncAdapter.applyMutationAsync(context.operation(), request, mainThreadExecutor)
                            .thenCompose(applied -> finishAsyncRestock(context, containerInventory, containerSlot,
                                    logicalSlot, amount, mutationId, targetBefore, targetAfter,
                                    sourceBefore, sourceAfter, applied, mainThreadExecutor));
                }, mainThreadExecutor)
                .exceptionally(failure -> {
                    logger.log(Level.SEVERE, "[AutoChest] 异步 restock mutation 失败，必须人工 reconcile 喵~", failure);
                    return new Result(Status.FAILED_UNRECOVERABLE, 0);
                });
    }

    // 在 PlayerBackpack apply 成功后校验并写入 Bukkit 容器来源，再终结 journal 喵~
    private CompletionStage<Result> finishAsyncRestock(PlayerBackpackTaskContext context, Inventory inventory,
                                                        int containerSlot, int logicalSlot, int amount,
                                                        UUID mutationId, ItemStack targetBefore, ItemStack targetAfter,
                                                        ItemStack sourceBefore, ItemStack sourceAfter,
                                                        BackpackMutationResult mutationResult,
                                                        Executor mainThreadExecutor) {
        if (mutationResult == null || mutationResult.status() == BackpackMutationResult.Status.RECONCILIATION_REQUIRED) {
            return CompletableFuture.completedFuture(new Result(Status.FAILED_UNRECOVERABLE, 0));
        }
        if (!mutationResult.applied()) {
            return CompletableFuture.completedFuture(skipped());
        }
        if (mutationResult.movedAmount() != amount || mutationResult.snapshot() == null
                || mutationResult.newRevision() <= context.snapshot().revision()
                || mutationResult.newRevision() != mutationResult.snapshot().revision()
                || !context.advance(mutationResult.snapshot())) {
            return compensateAsync(context, inventory, containerSlot, sourceBefore, sourceAfter, logicalSlot,
                    targetBefore, targetAfter, amount, mutationId, BackpackMutationDirection.RESTOCK,
                    mutationResult.newRevision(), mainThreadExecutor);
        }
        if (!sameSlot(inventory.getItem(containerSlot), sourceBefore)) {
            return compensateAsync(context, inventory, containerSlot, sourceBefore, sourceAfter, logicalSlot,
                    targetBefore, targetAfter, amount, mutationId, BackpackMutationDirection.RESTOCK,
                    mutationResult.newRevision(), mainThreadExecutor);
        }
        try {
            inventory.setItem(containerSlot, ContainerTransaction.cloneOrNull(sourceAfter));
        } catch (RuntimeException exception) {
            return compensateAsync(context, inventory, containerSlot, sourceBefore, sourceAfter, logicalSlot,
                    targetBefore, targetAfter, amount, mutationId, BackpackMutationDirection.RESTOCK,
                    mutationResult.newRevision(), mainThreadExecutor);
        }
        if (!sameSlot(inventory.getItem(containerSlot), sourceAfter)) {
            return compensateAsync(context, inventory, containerSlot, sourceBefore, sourceAfter, logicalSlot,
                    targetBefore, targetAfter, amount, mutationId, BackpackMutationDirection.RESTOCK,
                    mutationResult.newRevision(), mainThreadExecutor);
        }
        return context.asyncAdapter().markContainerAppliedAsync(context.operation(), mutationId)
                .thenApply(failure -> failure == BackpackOperationFailure.NONE
                        ? new Result(Status.SUCCESS, amount)
                        : new Result(Status.FAILED_UNRECOVERABLE, 0));
    }

    // apply 成功但上下文无法推进时，直接条件补偿 PlayerBackpack，容器尚未写入喵~
    private Result compensateAppliedMutation(PlayerBackpackTaskContext context, UUID mutationId,
                                             int logicalSlot, ItemStack appliedAfter,
                                             ItemStack originalBefore, int amount,
                                             long appliedRevision,
                                             BackpackMutationDirection direction) {
        // 喵~防御：补偿输入必须完整，避免生成无法验证的恢复请求喵~
        if (context == null || appliedAfter == null || originalBefore == null || amount <= 0) {
            // 记录无法构造安全补偿的严重状态喵~
            logger.severe("[AutoChest] apply 成功后补偿参数非法，必须人工 reconcile: mutation=" + mutationId);
            // 返回不可恢复并阻止任务继续喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 使用 provider 返回的新 revision 作为补偿 CAS 基线，不能依赖旧 context revision 喵~
        if (appliedRevision < 0L) {
            // 喵~防御：无效 provider revision 无法安全补偿喵~
            logger.severe("[AutoChest] apply 成功后 revision 非法，必须人工 reconcile: mutation=" + mutationId);
            // 返回不可恢复并停止任务喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 创建仅恢复本次 logical slot 的补偿请求喵~
        UUID compensationMutationId = UUID.randomUUID();
        // 使用 apply 后物品作为 CAS before-image，恢复原始物品喵~
        BackpackMutationRequest compensationRequest = new BackpackMutationRequest(
                compensationMutationId, context.operation().targetId(), direction,
                appliedRevision, logicalSlot, appliedAfter, originalBefore, amount);
        // 调用带 journal 的条件补偿接口喵~
        BackpackMutationResult compensationResult = context.adapter().applyCompensation(
                context.operation(), mutationId, compensationRequest);
        // 只有补偿精确移动本次数量、快照存在且上下文严格推进时才确认恢复喵~
        if (compensationResult != null && compensationResult.applied()
                && compensationResult.movedAmount() == amount && compensationResult.snapshot() != null
                && context.advance(compensationResult.snapshot())) {
            // 返回已恢复且不产生移动统计喵~
            return new Result(Status.RECOVERED, 0);
        }
        // 喵~防御：补偿结果不确定时保留 journal 并中止任务喵~
        logger.severe("[AutoChest] apply 成功后条件补偿失败，必须人工 reconcile: mutation=" + mutationId);
        // 返回不可恢复状态，调用方必须释放会话喵~
        return new Result(Status.FAILED_UNRECOVERABLE, 0);
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
        // 当前槽位已经回到 before-image 时无需重复写容器，直接进入 PlayerBackpack 补偿喵~
        if (sameSlot(currentBukkitItem, bukkitBefore)) {
            // 保留 before-image 现场，跳过容器恢复步骤喵~
        } else if (!sameSlot(currentBukkitItem, bukkitAfter)) {
            // 容器处于第三种状态时无法证明归属，必须保留现场并隔离任务喵~
            logger.log(Level.SEVERE, "[AutoChest] 跨域失败后 Bukkit 槽位已被外部修改，必须人工 reconcile: mutation="
                    + originalMutationId + " logicalSlot=" + logicalSlot, writeException);
            // 返回不可恢复状态，禁止覆盖外部修改喵~
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        } else {
            // 只有 after-image 仍然存在时才执行条件恢复喵~
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
        }
        // 记录恢复后的容器镜像喵~
        ItemStack restoredBukkitItem;
        try {
            // 读取恢复后的容器槽位喵~
            restoredBukkitItem = ContainerTransaction.cloneOrNull(inventory.getItem(bukkitSlot));
        } catch (RuntimeException readException) {
            // 喵~防御：恢复后无法读取槽位时状态不确定，禁止继续补偿喵~
            logger.log(Level.SEVERE, "[AutoChest] 跨域恢复后无法读取 Bukkit 槽位，必须人工 reconcile: mutation="
                    + originalMutationId, readException);
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 精确复核容器已回到事务 before-image 喵~
        if (!sameSlot(restoredBukkitItem, bukkitBefore)) {
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
        // 喵~防御：补偿接口异常也必须转成不可恢复结果，不能逃逸任务清理出口喵~
        BackpackMutationResult compensationResult;
        try {
            // 在容器已精确恢复后执行 journal-backed 条件补偿喵~
            compensationResult = context.adapter().applyCompensation(
                    context.operation(), originalMutationId, compensationRequest);
        } catch (RuntimeException compensationException) {
            // 记录补偿异常并保留 journal 现场喵~
            logger.log(Level.SEVERE, "[AutoChest] 跨域 PlayerBackpack 条件补偿异常，必须人工 reconcile: mutation="
                    + originalMutationId, compensationException);
            return new Result(Status.FAILED_UNRECOVERABLE, 0);
        }
        // 只有已提交或幂等重放、精确移动数量、快照存在且严格推进时才确认完全恢复喵~
        if (compensationResult != null && compensationResult.applied()
                && compensationResult.movedAmount() == amount && compensationResult.snapshot() != null
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
        // 喵~防御：上下文为空或已关闭时不能读取快照喵~
        if (context == null || !context.isOpen()) {
            // 返回未提交结果喵~
            return false;
        }
        // 读取当前任务快照容量，拒绝容量外 logical slot 喵~
        BackpackSnapshot snapshot = context.snapshot();
        // 只有会话仍打开、库存非空且槽位数量合法时才允许跨域 mutation 喵~
        return snapshot != null && inventory != null
                && bukkitSlot >= 0 && bukkitSlot < inventory.getSize()
                && logicalSlot > 0 && logicalSlot <= snapshot.capacity() && amount > 0;
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
