package io.github.autochest.integration.playerbackpack;

// 导入 UUID 作为玩家任务资源键喵~
import java.util.UUID;
// 导入并发映射支持生命周期事件与任务回调安全竞争喵~
import java.util.concurrent.ConcurrentHashMap;

// 管理每名玩家当前 AutoChest 任务持有的 PlayerBackpack 会话喵~
public final class PlayerBackpackTaskContexts {

    // 保存玩家 UUID 到唯一任务上下文的映射喵~
    private final ConcurrentHashMap<UUID, PlayerBackpackTaskContext> contexts = new ConcurrentHashMap<>();

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

    // 移除并释放玩家当前任意上下文，供生命周期事件调用喵~
    public void releasePlayer(UUID playerUuid) {
        // 喵~防御：空 UUID 不执行映射操作喵~
        if (playerUuid == null) {
            // 直接返回喵~
            return;
        }
        // 原子移除当前上下文喵~
        PlayerBackpackTaskContext context = contexts.remove(playerUuid);
        // 有资源时执行幂等关闭喵~
        if (context != null) {
            // 释放 PlayerBackpack 目标锁喵~
            context.close();
        }
    }

    // 插件禁用时释放所有仍存活的 PlayerBackpack 会话喵~
    public void releaseAll() {
        // 遍历弱一致并发映射，逐个按引用条件释放喵~
        contexts.forEach(this::release);
    }

    // 返回当前资源数量供生命周期测试断言喵~
    public int size() {
        // 返回并发映射的当前近实时大小喵~
        return contexts.size();
    }
}
