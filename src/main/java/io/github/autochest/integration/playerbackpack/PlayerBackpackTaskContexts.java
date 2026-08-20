package io.github.autochest.integration.playerbackpack;

// 导入 UUID 作为玩家任务资源键喵~
import java.util.UUID;
// 导入并发映射支持生命周期事件与任务回调安全竞争喵~
import java.util.concurrent.ConcurrentHashMap;

// 管理每名玩家当前 AutoChest 任务持有的 PlayerBackpack 会话喵~
public final class PlayerBackpackTaskContexts {

    // 保存玩家 UUID 到唯一任务上下文的映射喵~
    private final ConcurrentHashMap<UUID, PlayerBackpackTaskContext> contexts = new ConcurrentHashMap<>();
    // 保存尚未完成 context 注册的预备 operation，覆盖停服竞态喵~
    private final ConcurrentHashMap<UUID, PendingOperation> pendingOperations = new ConcurrentHashMap<>();

    // 为玩家登记唯一 PlayerBackpack 任务上下文喵~
    public boolean register(UUID playerUuid, PlayerBackpackTaskContext context) {
        // 喵~防御：玩家或上下文为空时拒绝登记喵~
        if (playerUuid == null || context == null) {
            // 返回失败避免产生无法释放的资源喵~
            return false;
        }
        // CAS 登记防止并发命令覆盖旧会话喵~
        return contexts.putIfAbsent(playerUuid, context) == null;
    }

    // 查询玩家当前任务上下文喵~
    public PlayerBackpackTaskContext get(UUID playerUuid) {
        // 喵~防御：空 UUID 没有可查询资源喵~
        if (playerUuid == null) {
            // 返回空上下文喵~
            return null;
        }
        // 返回当前登记的上下文引用喵~
        return contexts.get(playerUuid);
    }

    // 仅当引用匹配时移除并幂等释放上下文喵~
    public void release(UUID playerUuid, PlayerBackpackTaskContext expectedContext) {
        // 喵~防御：参数为空时不误删其他任务资源喵~
        if (playerUuid == null || expectedContext == null) {
            // 直接返回保持当前映射不变喵~
            return;
        }
        // 仅匹配当前任务引用时移除，防止迟到回调释放新任务喵~
        if (contexts.remove(playerUuid, expectedContext)) {
            // 幂等关闭 PlayerBackpack 外部操作句柄喵~
            expectedContext.close();
        }
    }

    // 登记尚未创建完整 context 的异步 operation，停服时统一释放喵~
    public boolean registerPending(UUID playerUuid, PlayerBackpackAsyncAdapter asyncAdapter, BackpackOperation operation) {
        // 喵~防御：预备资源缺失时拒绝登记，避免无法释放的幽灵 token 喵~
        if (playerUuid == null || asyncAdapter == null || operation == null) {
            return false;
        }
        // 使用玩家 UUID 唯一覆盖保护，避免不同预备流程互相释放喵~
        return pendingOperations.putIfAbsent(playerUuid, new PendingOperation(asyncAdapter, operation)) == null;
    }

    // 移除指定 operation 的预备登记，供成功注册 context 或异常出口调用喵~
    public void removePending(UUID playerUuid, BackpackOperation operation) {
        // 喵~防御：空参数不触碰其他玩家资源喵~
        if (playerUuid == null || operation == null) {
            return;
        }
        // 仅引用相同 operation 才允许移除，防止迟到回调误删新预备流程喵~
        pendingOperations.remove(playerUuid, new PendingOperation(null, operation));
    }

    // 释放指定玩家所有已登记资源，覆盖离线、停服和 provider 撤销喵~
    public void releasePlayer(UUID playerUuid) {
        // 喵~防御：空 UUID 不执行映射操作喵~
        if (playerUuid == null) {
            return;
        }
        // 原子移除当前完整上下文喵~
        PlayerBackpackTaskContext context = contexts.remove(playerUuid);
        // 有完整上下文时执行幂等关闭喵~
        if (context != null) {
            context.close();
        }
        // 原子移除尚未登记的预备 operation 喵~
        PendingOperation pendingOperation = pendingOperations.remove(playerUuid);
        // 有预备 operation 时释放 provider reservation 喵~
        if (pendingOperation != null) {
            pendingOperation.asyncAdapter().finishOperationAsync(pendingOperation.operation());
        }
    }

    // 插件禁用时释放所有完整上下文与预备 operation 喵~
    public void releaseAll() {
        // 遍历完整上下文并按引用条件释放喵~
        contexts.forEach(this::release);
        // 遍历预备 operation 并按玩家 UUID 释放喵~
        pendingOperations.forEach((playerUuid, pendingOperation) -> releasePlayer(playerUuid));
    }

    // 封装异步预备资源，使用 operation 相等性保证条件移除喵~
    private record PendingOperation(PlayerBackpackAsyncAdapter asyncAdapter, BackpackOperation operation) {
        // 自定义相等判断只比较 operation，允许 removePending 不持有 adapter 引用喵~
        @Override
        public boolean equals(Object other) {
            return other instanceof PendingOperation pending && operation.equals(pending.operation());
        }

        // operation 是唯一资源身份，不把 adapter 实例纳入条件判断喵~
        @Override
        public int hashCode() {
            return operation.hashCode();
        }
    }

    // 返回当前资源数量供生命周期测试断言喵~
    public int size() {
        // 返回并发映射的当前近实时大小喵~
        return contexts.size();
    }
}
