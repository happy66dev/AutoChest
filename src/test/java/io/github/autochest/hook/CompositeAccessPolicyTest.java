package io.github.autochest.hook;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复合容器访问策略测试。
 * 使用无 Bukkit 运行环境依赖的固定策略，验证可选 Hook 的安全聚合语义。
 */
class CompositeAccessPolicyTest {

    /** 用于测试策略日志输出的记录器。 */
    private static final Logger TEST_LOGGER = Logger.getLogger("CompositeAccessPolicyTest");

    /**
     * 验证 Slimefun 策略拒绝时，复合策略必须拒绝目标容器。
     */
    @Test
    void canAccess_slimefunPolicyDenies_rejectsContainer() {
        // 创建模拟 Slimefun 已安装且可用、但拒绝该容器的策略。
        ContainerAccessPolicy slimefunPolicy = new FixedPolicy("Slimefun", true, true, false);
        // 使用单个模拟策略构造复合访问策略。
        CompositeAccessPolicy accessPolicy = new CompositeAccessPolicy(List.of(slimefunPolicy), TEST_LOGGER);
        // 验证 Slimefun 拒绝会阻止容器被访问。
        assertFalse(accessPolicy.canAccess(null));
    }

    /**
     * 验证已安装但不可用的 Slimefun Hook 会被识别为全局阻断条件。
     */
    @Test
    void unavailableSlimefunHook_blocksTaskAndThrowsOnAccess() {
        // 创建模拟 Slimefun 已安装但初始化失败的策略。
        ContainerAccessPolicy unavailableSlimefunPolicy = new FixedPolicy("Slimefun", true, false, true);
        // 使用不可用策略构造复合访问策略。
        CompositeAccessPolicy accessPolicy = new CompositeAccessPolicy(List.of(unavailableSlimefunPolicy), TEST_LOGGER);
        // 验证命令入口能够报告导致 fail-closed 的 Hook 名称。
        assertEquals("Slimefun", accessPolicy.findUnavailableHook());
        // 验证扫描或提交期访问也会抛出不可用异常，而不是绕过保护。
        assertThrows(HookUnavailableException.class, () -> accessPolicy.canAccess(null));
    }

    /**
     * 验证未安装 Slimefun 时策略会被静默跳过。
     */
    @Test
    void uninstalledSlimefunHook_doesNotBlockAccess() {
        // 创建模拟 Slimefun 未安装的策略，即使其可用状态为 false 也不应参与判断。
        ContainerAccessPolicy uninstalledSlimefunPolicy = new FixedPolicy("Slimefun", false, false, false);
        // 使用未安装策略构造复合访问策略。
        CompositeAccessPolicy accessPolicy = new CompositeAccessPolicy(List.of(uninstalledSlimefunPolicy), TEST_LOGGER);
        // 验证未安装 Hook 不会被报告为不可用。
        assertNull(accessPolicy.findUnavailableHook());
        // 验证未安装 Hook 不会阻断普通容器访问。
        assertTrue(accessPolicy.canAccess(null));
    }

    /**
     * 固定结果策略，用于隔离复合策略的聚合行为。
     */
    private static final class FixedPolicy implements ContainerAccessPolicy {

        /** 测试 Hook 的显示名称。 */
        private final String name;

        /** 测试 Hook 的安装状态。 */
        private final boolean installed;

        /** 测试 Hook 的初始化可用状态。 */
        private final boolean available;

        /** 测试 Hook 对容器访问的固定判定结果。 */
        private final boolean accessAllowed;

        /**
         * 创建具有固定状态与结果的测试策略。
         *
         * @param name 测试 Hook 名称。
         * @param installed 测试 Hook 是否安装。
         * @param available 测试 Hook 是否可用。
         * @param accessAllowed 测试 Hook 是否允许容器访问。
         */
        private FixedPolicy(String name, boolean installed, boolean available, boolean accessAllowed) {
            // 保存测试 Hook 名称。
            this.name = name;
            // 保存测试 Hook 安装状态。
            this.installed = installed;
            // 保存测试 Hook 可用状态。
            this.available = available;
            // 保存测试 Hook 访问结果。
            this.accessAllowed = accessAllowed;
        }

        /**
         * 返回预设的容器访问结果。
         *
         * @param player 当前玩家，本测试策略不读取该参数。
         * @param blocks 容器方块，本测试策略不读取该参数。
         * @return 预设的访问结果。
         */
        @Override
        public boolean canAccess(Player player, Block... blocks) {
            // 返回构造时指定的固定访问结果。
            return accessAllowed;
        }

        /**
         * 返回预设的安装状态。
         *
         * @return 预设安装状态。
         */
        @Override
        public boolean isInstalled() {
            // 返回构造时指定的安装状态。
            return installed;
        }

        /**
         * 返回预设的可用状态。
         *
         * @return 预设可用状态。
         */
        @Override
        public boolean isAvailable() {
            // 返回构造时指定的可用状态。
            return available;
        }

        /**
         * 返回预设 Hook 名称。
         *
         * @return 预设 Hook 名称。
         */
        @Override
        public String hookName() {
            // 返回构造时指定的 Hook 名称。
            return name;
        }
    }
}
