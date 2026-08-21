package io.github.autochest.integration.playerbackpack;

// 导入 UUID 作为玩家任务资源键喵~
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 管理每名玩家当前 AutoChest 任务持有的 PlayerBackpack 会话喵~
public final class PlayerBackpackTaskContexts {

    // 保存玩家 UUID 到唯一任务上下文的映射喵~
    private final ConcurrentHashMap<UUID, PlayerBackpackTaskContext> contexts = new ConcurrentHashMap<>();
    // 保存尚未完成 context 注册的预备 operation，覆盖停服竞态喵~
    private final ConcurrentHashMap<UUID, PendingOperation> pendingOperations = new ConcurrentHashMap<>();
    // 标识插件是否已关闭资源入口，阻止迟到 callback 再登记 context 喵~
    private final java.util.concurrent.atomic.AtomicBoolean acceptingRegistrations =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    // 为玩家登记唯一 PlayerBackpack 任务上下文喵~
    public synchronized boolean register(UUID playerUuid, PlayerBackpackTaskContext context) {
        // 喵~防御：玩家或上下文为空时拒绝登记喵~
        if (playerUuid == null || context == null) {
            // 返回失败避免产生无法释放的资源喵~
            return false;
        }
        // 喵~防御：停服后拒绝迟到 callback 登记新会话喵~
        if (!acceptingRegistrations.get()) {
            return false;
        }
        // CAS 登记防止并发命令覆盖旧会话喵~
        return contexts.putIfAbsent(playerUuid, context) == null;
    }

    // 将已注册 context 原子绑定到指定 AutoChest task 身份喵~
    public synchronized boolean bind(UUID playerUuid, PlayerBackpackTaskContext expectedContext,
                                      long taskToken, int sessionEpoch) {
        // 喵~防御：参数缺失时拒绝绑定，避免产生无法验证的归属喵~
        if (playerUuid == null || expectedContext == null) {
            return false;
        }
        // 仅当前 map 中仍是同一引用时允许绑定，防止迟到 callback 越权喵~
        if (contexts.get(playerUuid) != expectedContext) {
            return false;
        }
        // 由 context 内部锁保证绑定身份不可重复修改喵~
        return expectedContext.bindTask(taskToken, sessionEpoch);
    }

    // 将 pending operation 原子转移为完整 context，禁止停服释放夹在两步之间喵~
    public synchronized boolean adoptPending(UUID playerUuid, BackpackOperation operation,
                                              PlayerBackpackTaskContext context) {
        // 喵~防御：任一身份缺失时拒绝转移，避免产生无法释放资源喵~
        if (playerUuid == null || operation == null || context == null || !acceptingRegistrations.get()) {
            return false;
        }
        // 只有相同 operation 仍处于 pending 才能完成所有权转移喵~
        PendingOperation pendingOperation = pendingOperations.get(playerUuid);
        if (pendingOperation == null || !pendingOperation.operation().equals(operation)) {
            return false;
        }
        // CAS 登记完整 context，拒绝覆盖其他任务已持有资源喵~
        if (contexts.putIfAbsent(playerUuid, context) != null) {
            return false;
        }
        // context 登记成功后在同一锁内移除 pending，生命周期释放无法插入中间喵~
        pendingOperations.remove(playerUuid, pendingOperation);
        return true;
    }

    // 查询玩家当前任务上下文喵~
    public PlayerBackpackTaskContext get(UUID playerUuid) {
        if (playerUuid == null) {
            // 返回空上下文喵~
            return null;
        }
        // 返回当前登记的上下文引用喵~
        return contexts.get(playerUuid);
    }

    // 仅当引用匹配时移除并幂等释放上下文喵~
    public synchronized void release(UUID playerUuid, PlayerBackpackTaskContext expectedContext) {
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

    // 登记尚未创建完整 context 的 v1 operation，停服时统一释放喵~
    public synchronized boolean registerPending(UUID playerUuid, PlayerBackpackAdapter adapter,
                                                  BackpackOperation operation) {
        // 喵~防御：预备资源缺失时拒绝登记，避免产生无法释放的 v1 token 喵~
        if (playerUuid == null || adapter == null || operation == null || !acceptingRegistrations.get()) {
            return false;
        }
        // 使用玩家 UUID 唯一覆盖保护，避免不同预备流程互相释放喵~
        return pendingOperations.putIfAbsent(playerUuid, PendingOperation.forSync(adapter, operation)) == null;
    }

    // 登记尚未创建完整 context 的 v2 operation，停服时统一释放喵~
    public synchronized boolean registerPending(UUID playerUuid, PlayerBackpackAsyncAdapter asyncAdapter,
                                                  BackpackOperation operation) {
        // 喵~防御：预备资源缺失或关闭后拒绝登记，避免产生无法释放的 v2 token 喵~
        if (playerUuid == null || asyncAdapter == null || operation == null || !acceptingRegistrations.get()) {
            return false;
        }
        // 使用玩家 UUID 唯一覆盖保护，避免不同预备流程互相释放喵~
        return pendingOperations.putIfAbsent(playerUuid, PendingOperation.forAsync(asyncAdapter, operation)) == null;
    }

    // 移除指定 operation 的预备登记，供成功注册 context 或异常出口调用喵~
    public synchronized void removePending(UUID playerUuid, BackpackOperation operation) {
        // 喵~防御：空参数不触碰其他玩家资源喵~
        if (playerUuid == null || operation == null) {
            return;
        }
        // 仅引用相同 operation 才允许移除，防止迟到回调误删新预备流程喵~
        pendingOperations.remove(playerUuid, new PendingOperation(null, null, operation));
    }

    // 释放指定玩家所有已登记资源，覆盖离线、停服和 provider 撤销喵~
    public synchronized void releasePlayer(UUID playerUuid) {
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
            // v1 operation 使用同步 finish，v2 operation 使用异步 finish，保持固定 backend 归属喵~
            if (pendingOperation.adapter() != null) {
                pendingOperation.adapter().finish(pendingOperation.operation());
            } else if (pendingOperation.asyncAdapter() != null) {
                pendingOperation.asyncAdapter().finishOperationAsync(pendingOperation.operation());
            }
        }
    }

    // 插件禁用时关闭注册入口并释放所有完整上下文与预备 operation 喵~
    public synchronized void releaseAll() {
        // 先关闭入口，确保弱一致遍历期间不会出现新的 context 或 pending operation 喵~
        acceptingRegistrations.set(false);
        // 循环直到两个并发表为空，覆盖进入关闭闸门前已经开始的登记喵~
        while (!contexts.isEmpty() || !pendingOperations.isEmpty()) {
            // 遍历完整上下文并按引用条件释放喵~
            contexts.forEach(this::release);
            // 遍历预备 operation 并按玩家 UUID 释放喵~
            pendingOperations.forEach((playerUuid, pendingOperation) -> releasePlayer(playerUuid));
        }
    }

    // 封装预备资源，使用 operation 相等性保证条件移除喵~
    private record PendingOperation(PlayerBackpackAdapter adapter,
                                    PlayerBackpackAsyncAdapter asyncAdapter,
                                    BackpackOperation operation) {
        // 创建 v1 同步 adapter 预备资源喵~
        private static PendingOperation forSync(PlayerBackpackAdapter adapter, BackpackOperation operation) {
            return new PendingOperation(adapter, null, operation);
        }

        // 创建 v2 异步 adapter 预备资源喵~
        private static PendingOperation forAsync(PlayerBackpackAsyncAdapter asyncAdapter, BackpackOperation operation) {
            return new PendingOperation(null, asyncAdapter, operation);
        }

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
