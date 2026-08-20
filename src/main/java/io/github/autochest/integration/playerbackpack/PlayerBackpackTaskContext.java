package io.github.autochest.integration.playerbackpack;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PlayerBackpackTaskContext implements AutoCloseable {
    private final PlayerBackpackAdapter adapter;
    private final PlayerBackpackAsyncAdapter asyncAdapter;
    private final BackpackOperation operation;
    private BackpackSnapshot snapshot;
    private final AtomicBoolean closed = new AtomicBoolean(false);

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
                asyncAdapter.finishOperationAsync(operation);
            } else {
                adapter.finish(operation);
            }
        }
    }
}
