package io.github.autochest.hook;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 复合访问策略，聚合 WorldGuard、Towny、ChestShop 三个可选 Hook
 * 任一已安装且初始化失败的 Hook 将导致整个任务被拒绝
 */
public class CompositeAccessPolicy {

    /** 所有已注册的 Hook 列表 */
    private final List<ContainerAccessPolicy> policies;

    /** 插件日志，用于记录 Hook 查询异常 */
    private final Logger logger;

    /** 上次记录日志的时间（用于限频，避免刷日志） */
    private long lastLogTime = 0L;

    /** 限频间隔：每 5 秒最多记录一次 Hook 异常 */
    private static final long LOG_INTERVAL_MS = 5000L;

    /**
     * 创建复合访问策略
     *
     * @param policies 已初始化的 Hook 列表（含不可用的）
     * @param logger   日志记录器
     */
    public CompositeAccessPolicy(List<ContainerAccessPolicy> policies, Logger logger) {
        this.policies = new ArrayList<>(policies);
        this.logger = logger;
    }

    /**
     * 检查是否有已安装但不可用的 Hook
     * 用于任务创建前的整体检查；若有则应拒绝任务
     *
     * @param installedPlugins 服务器已安装的插件列表（由主类提供）
     * @return 不可用的 Hook 名称，若所有 Hook 均可用则返回 null
     */
    public String findUnavailableHook() {
        for (ContainerAccessPolicy policy : policies) {
            // 若 Hook 注册了但不可用（初始化失败），则报告
            if (!policy.isAvailable()) {
                return policy.hookName();
            }
        }
        return null;
    }

    /**
     * 判断指定玩家是否可以访问给定容器
     * 任一 Hook 拒绝则返回 false；已安装但不可用则抛出 HookUnavailableException
     * 此方法必须在主线程调用
     *
     * @param player 执行操作的玩家
     * @param blocks 容器方块（单箱/木桶 1 个，双箱 2 个）
     * @return true 表示所有 Hook 均允许访问
     * @throws HookUnavailableException 若某个 Hook 已安装但不可用
     */
    public boolean canAccess(Player player, Block... blocks) {
        for (ContainerAccessPolicy policy : policies) {
            // 喵~防御：Hook 不可用时拒绝整个任务而不是静默放行
            if (!policy.isAvailable()) {
                throw new HookUnavailableException(policy.hookName());
            }
            boolean allowed;
            try {
                allowed = policy.canAccess(player, blocks);
            } catch (Exception e) {
                // 喵~防御：Hook 查询异常时保守排除该容器，限频记录日志
                logRateLimited(Level.WARNING,
                        "[AutoChest] Hook " + policy.hookName() + " 查询异常，跳过容器: " + e.getMessage());
                return false;
            }
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * 限频日志记录，避免频繁异常导致控制台刷屏
     *
     * @param level   日志级别
     * @param message 日志消息
     */
    private void logRateLimited(Level level, String message) {
        long now = System.currentTimeMillis();
        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            lastLogTime = now;
            logger.log(level, message);
        }
    }
}
