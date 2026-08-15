package io.github.autochest.integration.playerbackpack;

// 导入 PlayerBackpack 不可变快照模型喵~
import com.playerbackpack.api.BackpackSnapshotView;
// 导入 PlayerBackpack 外部操作句柄喵~
import com.playerbackpack.api.PlayerBackpackOperation;
// 导入原子布尔值以保证会话只释放一次喵~
import java.util.concurrent.atomic.AtomicBoolean;

// 保存一次 AutoChest 任务独占的 PlayerBackpack 会话与最新 revision 快照喵~
public final class PlayerBackpackTaskContext implements AutoCloseable {

    // 保存隔离第三方异常的 API 适配器喵~
    private final PlayerBackpackAdapter adapter;
    // 保存目标背包的独占操作句柄喵~
    private final PlayerBackpackOperation operation;
    // 保存当前已确认提交的最新背包快照喵~
    private BackpackSnapshotView snapshot;
    // 记录会话是否已经释放，防止多个生命周期出口重复调用 provider 喵~
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // 创建任务上下文并冻结初始快照喵~
    public PlayerBackpackTaskContext(PlayerBackpackAdapter adapter,
                                     PlayerBackpackOperation operation,
                                     BackpackSnapshotView snapshot) {
        // 喵~防御：缺少适配器、操作句柄或快照时不能建立跨域任务喵~
        if (adapter == null || operation == null || snapshot == null) {
            // 拒绝不完整上下文，避免后续 mutation 绕过 revision 校验喵~
            throw new IllegalArgumentException("PlayerBackpack 任务上下文参数不能为空喵~");
        }
        // 喵~防御：句柄与快照必须绑定同一目标玩家喵~
        if (!operation.targetId().equals(snapshot.playerId())) {
            // 拒绝跨目标误写喵~
            throw new IllegalArgumentException("PlayerBackpack 操作与快照目标不一致喵~");
        }
        // 保存异常隔离适配器喵~
        this.adapter = adapter;
        // 保存独占操作句柄喵~
        this.operation = operation;
        // 保存初始 revision 快照喵~
        this.snapshot = snapshot;
    }

    // 返回独占操作句柄喵~
    public PlayerBackpackOperation operation() {
        // 返回 API 的不可变 record 喵~
        return operation;
    }

    // 返回当前已确认的快照喵~
    public synchronized BackpackSnapshotView snapshot() {
        // BackpackSnapshotView 会保护 itemAt 返回值，调用方不能修改内部物品喵~
        return snapshot;
    }

    // 在成功 mutation 后推进本任务使用的 revision 与物品视图喵~
    public synchronized boolean advance(BackpackSnapshotView nextSnapshot) {
        // 喵~防御：关闭后或缺少新快照时不能继续写入喵~
        if (closed.get() || nextSnapshot == null) {
            // 返回失败让协调器中止后续跨域写入喵~
            return false;
        }
        // 喵~防御：新快照必须仍属于同一目标且 revision 单调递增或保持幂等版本喵~
        if (!operation.targetId().equals(nextSnapshot.playerId())
                || nextSnapshot.revision() < snapshot.revision()) {
            // 拒绝倒退或跨目标快照喵~
            return false;
        }
        // 保存 provider 返回的已提交快照喵~
        snapshot = nextSnapshot;
        // 表示上下文推进成功喵~
        return true;
    }

    // 判断外部操作会话是否仍可继续使用喵~
    public boolean isOpen() {
        // 只有尚未统一释放时才允许 mutation 喵~
        return !closed.get();
    }

    // 返回运行期异常隔离适配器喵~
    PlayerBackpackAdapter adapter() {
        // 仅供同一集成包中的协调器调用喵~
        return adapter;
    }

    // 幂等释放 PlayerBackpack 外部操作会话喵~
    @Override
    public void close() {
        // 只有首次关闭者负责通知 provider 释放目标锁喵~
        if (closed.compareAndSet(false, true)) {
            // 通过适配器隔离 finishOperation 的第三方运行期异常喵~
            adapter.finish(operation);
        }
    }
}
