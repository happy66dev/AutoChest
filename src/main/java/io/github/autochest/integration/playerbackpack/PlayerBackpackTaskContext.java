package io.github.autochest.integration.playerbackpack;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PlayerBackpackTaskContext implements AutoCloseable {
    private final PlayerBackpackAdapter adapter;
    private final PlayerBackpackAsyncAdapter asyncAdapter;
    private final BackpackOperation operation;
    private BackpackSnapshot snapshot;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    // 保存绑定的 AutoChest 任务 token，防止旧任务回调释放新任务上下文喵~
    private long taskToken;
    // 保存绑定任务创建时的 session epoch，防止重连后的旧回调越权喵~
    private int taskSessionEpoch;
    // 标记当前 context 是否已经绑定 AutoChest 任务喵~
    private boolean taskBound;

    public PlayerBackpackTaskContext(PlayerBackpackAdapter adapter, BackpackOperation operation, BackpackSnapshot snapshot) {
        this(adapter, null, operation, snapshot);
    }

    // 创建固定 v2 backend 的任务上下文，mutation 生命周期内禁止切换 provider 喵~
    public PlayerBackpackTaskContext(PlayerBackpackAsyncAdapter asyncAdapter, BackpackOperation operation,
                                     BackpackSnapshot snapshot) {
        this(null, asyncAdapter, operation, snapshot);
    }

    // 保存单一 backend，兼容旧 v1 构造器并拒绝空依赖喵~
    private PlayerBackpackTaskContext(PlayerBackpackAdapter adapter, PlayerBackpackAsyncAdapter asyncAdapter,
                                      BackpackOperation operation, BackpackSnapshot snapshot) {
        if ((adapter == null && asyncAdapter == null) || (adapter != null && asyncAdapter != null)
                || operation == null || snapshot == null) {
            throw new IllegalArgumentException("PlayerBackpack 任务上下文参数不能为空喵~");
        }
        if (!operation.targetId().equals(snapshot.playerId())) {
            throw new IllegalArgumentException("PlayerBackpack 操作与快照目标不一致喵~");
        }
        this.adapter = adapter;
        this.asyncAdapter = asyncAdapter;
        this.operation = operation;
        this.snapshot = snapshot;
    }

    public BackpackOperation operation() {
        return operation;
    }

    public synchronized BackpackSnapshot snapshot() {
        return snapshot;
    }

    public synchronized boolean advance(BackpackSnapshot nextSnapshot) {
        if (closed.get() || nextSnapshot == null) {
            return false;
        }
        if (!operation.targetId().equals(nextSnapshot.playerId()) || nextSnapshot.revision() <= snapshot.revision()) {
            return false;
        }
        snapshot = nextSnapshot;
        return true;
    }

    public boolean isOpen() {
        return !closed.get();
    }

    // 将外部 operation 归属到唯一 AutoChest task，重复绑定或关闭后拒绝喵~
    public synchronized boolean bindTask(long expectedTaskToken, int expectedSessionEpoch) {
        // 喵~防御：已关闭或已绑定的 context 不能被迟到 callback 重复归属喵~
        if (closed.get() || taskBound) {
            return false;
        }
        // 保存不可变任务身份，供所有读取和释放路径精确比较喵~
        taskToken = expectedTaskToken;
        taskSessionEpoch = expectedSessionEpoch;
        taskBound = true;
        return true;
    }

    // 检查 context 是否精确属于指定 task，UUID 相同不能视为相同任务喵~
    public synchronized boolean belongsTo(long expectedTaskToken, int expectedSessionEpoch) {
        return taskBound && taskToken == expectedTaskToken && taskSessionEpoch == expectedSessionEpoch;
    }

    PlayerBackpackAdapter adapter() {
        return adapter;
    }

    // 判断此任务是否在开始时固定选中了 v2 异步 backend 喵~
    public boolean usesAsyncBackend() {
        return asyncAdapter != null;
    }

    // 返回固定的 v2 backend，调用方必须先检查 usesAsyncBackend 喵~
    PlayerBackpackAsyncAdapter asyncAdapter() {
        return asyncAdapter;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (asyncAdapter != null) {
                // 异步 backend 自身不阻塞主线程，记录释放失败供 provider 日志诊断喵~
                asyncAdapter.finishOperationAsync(operation)
                        .whenComplete((ignoredResult, releaseFailure) -> {
                            // 喵~防御：释放失败不能伪装成已释放，保留错误日志由 adapter 统一记录喵~
                        });
            } else {
                // v1 finish 可能执行 JDBC，必须移出 Bukkit 主线程喵~
                java.util.concurrent.CompletableFuture.runAsync(() -> adapter.finish(operation));
            }
        }
    }
}
