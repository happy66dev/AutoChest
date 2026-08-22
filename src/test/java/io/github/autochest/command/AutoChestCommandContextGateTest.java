package io.github.autochest.command;

// 导入 JUnit 测试注解喵~
import org.junit.jupiter.api.Test;
// 导入假值断言工具喵~
import static org.junit.jupiter.api.Assertions.assertFalse;
// 导入真值断言工具喵~
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证缺少 PlayerBackpack context 时的取消与释放判定，覆盖无插件回退回归喵~
class AutoChestCommandContextGateTest {

    // 没有 PlayerBackpack 时命令必须继续执行纯原版流程，不得取消喵~
    @Test
    void shouldCancelForMissingContext_vanillaPathWithoutContext_doesNotCancel() {
        // 原版路径未走跨域预备，也没有绑定 context 喵~
        assertFalse(AutoChestCommand.shouldCancelForMissingContext(false, false));
    }

    // 走过 v2 跨域预备但 context 绑定失败时必须 fail-closed 取消喵~
    @Test
    void shouldCancelForMissingContext_crossStoragePathWithoutBoundContext_cancels() {
        // 跨域预备已改动 PlayerBackpack 状态，缺少绑定不能继续写入喵~
        assertTrue(AutoChestCommand.shouldCancelForMissingContext(true, false));
    }

    // 跨域预备成功绑定 context 时应正常继续任务喵~
    @Test
    void shouldCancelForMissingContext_crossStoragePathWithBoundContext_doesNotCancel() {
        // 绑定成功说明双域归属明确，可以继续扫描喵~
        assertFalse(AutoChestCommand.shouldCancelForMissingContext(true, true));
    }

    // 原版路径出现无法绑定的残留 context 时必须主动释放喵~
    @Test
    void shouldReleaseUnboundContext_vanillaPathWithUnboundContext_releases() {
        // 存在 context 却未绑定，必须回收避免 provider operation 永久占用喵~
        assertTrue(AutoChestCommand.shouldReleaseUnboundContext(false, true, false));
    }

    // 原版路径没有 context 时不需要任何释放动作喵~
    @Test
    void shouldReleaseUnboundContext_vanillaPathWithoutContext_doesNotRelease() {
        // 无 PlayerBackpack 环境下不存在需要回收的跨域资源喵~
        assertFalse(AutoChestCommand.shouldReleaseUnboundContext(false, false, false));
    }

    // 已绑定的 context 由任务结束路径释放，不能在此重复释放喵~
    @Test
    void shouldReleaseUnboundContext_boundContext_doesNotRelease() {
        // 绑定成功的资源生命周期归任务所有，重复释放会破坏归属校验喵~
        assertFalse(AutoChestCommand.shouldReleaseUnboundContext(false, true, true));
    }

    // 跨域路径的未绑定 context 由取消分支统一处理，不走原版释放判定喵~
    @Test
    void shouldReleaseUnboundContext_crossStoragePath_doesNotRelease() {
        // 跨域取消分支已负责释放，这里必须返回 false 避免双重释放喵~
        assertFalse(AutoChestCommand.shouldReleaseUnboundContext(true, true, false));
    }
}
