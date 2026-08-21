package io.github.autochest.integration.playerbackpack;

// 导入 JUnit 测试注解喵~
import org.junit.jupiter.api.Test;
// 导入 UUID 类型喵~
import java.util.UUID;
// 导入相等断言喵~
import static org.junit.jupiter.api.Assertions.assertEquals;
// 导入假值断言喵~
import static org.junit.jupiter.api.Assertions.assertFalse;
// 导入真值断言喵~
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证异步预备 pending operation 的生命周期所有权查询不误认其他 operation 喵~
class PlayerBackpackTaskContextsTest {

    // 提供具有无操作 finish 方法的反射 provider，避免所有权测试产生无关日志喵~
    private static final class FinishingProvider {
        // 返回已完成 future 模拟成功的异步 provider finish 喵~
        public java.util.concurrent.CompletionStage<Void> finishOperationAsync(Object operation) {
            // 返回无需异步工作的完成结果喵~
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    // 验证登记后仅同一 operation 被认定为 pending owner，释放后不可继续预备喵~
    @Test
    void ownsPending_requiresExactRegisteredOperationAndClearsAfterRelease() {
        // 创建共享 pending context 注册表喵~
        PlayerBackpackTaskContexts taskContexts = new PlayerBackpackTaskContexts();
        // 创建测试玩家身份喵~
        UUID playerId = UUID.randomUUID();
        // 创建已登记 operation喵~
        BackpackOperation registeredOperation = operation(playerId);
        // 创建同一玩家但不同 token 的 operation喵~
        BackpackOperation differentOperation = operation(playerId);
        // 创建可完成 finish 的反射测试 provider喵~
        PlayerBackpackAsyncAdapter adapter = new PlayerBackpackAsyncAdapter(new FinishingProvider(),
                java.util.logging.Logger.getLogger("PlayerBackpackTaskContextsTest"));
        // 登记唯一 pending operation喵~
        assertTrue(taskContexts.registerPending(playerId, adapter, registeredOperation));
        // 断言同一 operation 拥有 pending 所有权喵~
        assertTrue(taskContexts.ownsPending(playerId, registeredOperation));
        // 断言不同 operation 不能冒充当前所有权喵~
        assertFalse(taskContexts.ownsPending(playerId, differentOperation));
        // 释放登记 operation 并取得唯一 finish 所有权喵~
        assertTrue(taskContexts.releasePending(playerId, registeredOperation));
        // 断言释放后不再允许迟到预备链继续喵~
        assertFalse(taskContexts.ownsPending(playerId, registeredOperation));
        // 断言重复释放不能取得第二次所有权喵~
        assertFalse(taskContexts.releasePending(playerId, registeredOperation));
    }

    // 创建最小 operation 句柄，native handle 不会在所有权测试中被调用喵~
    private BackpackOperation operation(UUID playerId) {
        // 创建带随机 token 的独立 operation喵~
        return new BackpackOperation(playerId, playerId, UUID.randomUUID().toString(), 0L, new Object());
    }
}
