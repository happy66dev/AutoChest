package io.github.autochest.integration.playerbackpack;

// 导入 PlayerBackpack 稳定 API 接口喵~
import com.playerbackpack.api.PlayerBackpackApi;
// 导入不可变快照模型喵~
import com.playerbackpack.api.BackpackSnapshotView;
// 导入操作句柄模型喵~
import com.playerbackpack.api.PlayerBackpackOperation;
// 导入 GUI 冻结失败枚举喵~
import com.playerbackpack.api.BackpackOperationFailure;
// 导入 mutation 请求模型喵~
import com.playerbackpack.api.BackpackMutationRequest;
// 导入 mutation 结果模型喵~
import com.playerbackpack.api.BackpackMutationResult;
// 导入 UUID 以绑定目标和请求方喵~
import java.util.UUID;
// 导入可选值类型以表达不存在或失败喵~
import java.util.Optional;
// 导入日志类型以输出运行期异常诊断喵~
import java.util.logging.Logger;
// 导入日志级别以记录第三方异常喵~
import java.util.logging.Level;

// 隔离 PlayerBackpack provider 运行期异常和链接错误喵~
public final class PlayerBackpackAdapter {

    // 保存已校验的 API 服务喵~
    private final PlayerBackpackApi api;
    // 保存日志依赖以输出第三方异常诊断喵~
    private final Logger logger;

    // 创建适配器并校验依赖喵~
    public PlayerBackpackAdapter(PlayerBackpackApi api, Logger logger) {
        // 喵~防御：API 和日志都不能为空喵~
        if (api == null || logger == null) {
            // 拒绝不完整适配器喵~
            throw new IllegalArgumentException("PlayerBackpack 适配器依赖不能为空喵~");
        }
        // 保存已校验的 API 服务喵~
        this.api = api;
        // 保存日志依赖喵~
        this.logger = logger;
    }

    // 读取既有背包的不可变快照喵~
    public Optional<BackpackSnapshotView> loadSnapshot(UUID targetId) {
        // 喵~防御：目标 UUID 不能为空喵~
        if (targetId == null) {
            // 返回空值避免猜测性读取喵~
            return Optional.empty();
        }
        try {
            // 调用 provider 读取快照喵~
            return api.loadSnapshot(targetId);
        } catch (RuntimeException | LinkageError exception) {
            // 喵~防御：第三方异常或 ABI 不兼容时只记录且返回空喵~
            logger.log(Level.WARNING, "[AutoChest] PlayerBackpack loadSnapshot 失败，目标=" + targetId + " 喵~", exception);
            // 返回空值让上层回退到原版流程喵~
            return Optional.empty();
        }
    }

    // 尝试独占目标背包并返回外部操作句柄喵~
    public Optional<PlayerBackpackOperation> tryBeginOperation(UUID targetId, UUID requesterId, String reason) {
        // 喵~防御：目标、请求方和原因都不能为空喵~
        if (targetId == null || requesterId == null || reason == null) {
            // 返回空值拒绝无效请求喵~
            return Optional.empty();
        }
        try {
            // 调用 provider 尝试取得独占会话喵~
            return api.tryBeginOperation(targetId, requesterId, reason);
        } catch (RuntimeException | LinkageError exception) {
            // 喵~防御：第三方异常时只记录且不开始操作喵~
            logger.log(Level.WARNING, "[AutoChest] PlayerBackpack tryBeginOperation 失败，目标=" + targetId + " 喵~", exception);
            // 返回空值让命令拒绝本次任务喵~
            return Optional.empty();
        }
    }

    // 保存并关闭目标背包全部打开 GUI 喵~
    public BackpackOperationFailure saveAndCloseOpenGui(PlayerBackpackOperation operation) {
        // 喵~防御：操作句柄不能为空喵~
        if (operation == null) {
            // 返回前置条件失败喵~
            return BackpackOperationFailure.PRECONDITION_FAILED;
        }
        try {
            // 调用 provider 保存并关闭全部匹配 GUI 喵~
            return api.saveAndCloseOpenGui(operation);
        } catch (RuntimeException | LinkageError exception) {
            // 喵~防御：第三方异常时保守返回存储失败喵~
            logger.log(Level.SEVERE, "[AutoChest] PlayerBackpack saveAndCloseOpenGui 失败，目标=" + operation.targetId() + " 喵~", exception);
            // 返回存储失败让命令拒绝任务喵~
            return BackpackOperationFailure.STORAGE_FAILURE;
        }
    }

    // 应用受 revision 和 before-image 保护的幂等 mutation 喵~
    public BackpackMutationResult applyMutation(PlayerBackpackOperation operation, BackpackMutationRequest request) {
        // 喵~防御：操作句柄和 mutation 请求不能为空喵~
        if (operation == null || request == null) {
            // 返回明确失败状态喵~
            return new BackpackMutationResult(BackpackMutationResult.Status.SERVICE_UNAVAILABLE, 0L, 0, null, "操作或请求为空喵~");
        }
        try {
            // 调用 provider 执行 CAS mutation 喵~
            return api.applyMutation(operation, request);
        } catch (RuntimeException | LinkageError exception) {
            // 喵~防御：第三方异常时返回不确定状态喵~
            logger.log(Level.SEVERE, "[AutoChest] PlayerBackpack applyMutation 异常，mutation=" + request.mutationId() + " 喵~", exception);
            // 返回协调失败状态让上层中止任务喵~
            return new BackpackMutationResult(BackpackMutationResult.Status.RECONCILIATION_REQUIRED, 0L, 0, null, exception.getMessage());
        }
    }

    // 释放外部操作会话喵~
    public void finish(PlayerBackpackOperation operation) {
        // 喵~防御：操作句柄为空时跳过释放喵~
        if (operation == null) {
            // 不调用 provider 避免空指针异常喵~
            return;
        }
        try {
            // 调用 provider 释放目标锁喵~
            api.finishOperation(operation);
        } catch (RuntimeException | LinkageError exception) {
            // 喵~防御：finishOperation 异常也只记录，不能阻止任务收尾喵~
            logger.log(Level.WARNING, "[AutoChest] PlayerBackpack finishOperation 异常，目标=" + operation.targetId() + " 喵~", exception);
        }
    }
}
