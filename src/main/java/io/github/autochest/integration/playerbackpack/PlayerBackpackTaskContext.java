package io.github.autochest.integration.playerbackpack;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PlayerBackpackTaskContext implements AutoCloseable {
    private final PlayerBackpackAdapter adapter;
    private final BackpackOperation operation;
    private BackpackSnapshot snapshot;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public PlayerBackpackTaskContext(PlayerBackpackAdapter adapter, BackpackOperation operation, BackpackSnapshot snapshot) {
        if (adapter == null || operation == null || snapshot == null) {
            throw new IllegalArgumentException("PlayerBackpack 任务上下文参数不能为空喵~");
        }
        if (!operation.targetId().equals(snapshot.playerId())) {
            throw new IllegalArgumentException("PlayerBackpack 操作与快照目标不一致喵~");
        }
        this.adapter = adapter;
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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            adapter.finish(operation);
        }
    }
}
