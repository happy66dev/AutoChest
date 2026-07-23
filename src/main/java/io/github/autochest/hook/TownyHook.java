package io.github.autochest.hook;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Towny Hook 适配器
 * 策略：按地块容器/开关权限（ActionType.SWITCH）判断玩家是否可访问容器
 * 通过反射隔离 Towny 类型，消除编译期依赖
 */
public class TownyHook implements ContainerAccessPolicy {

    /** Hook 名称 */
    private static final String HOOK_NAME = "Towny";

    /** 是否成功初始化 */
    private boolean available = false;

    /** Towny 调用委托 */
    private SwitchChecker switchChecker;

    /**
     * 尝试通过反射初始化 Towny Hook
     *
     * @param logger 日志记录器
     */
    public TownyHook(Logger logger) {
        if (Bukkit.getPluginManager().getPlugin("Towny") == null) {
            // Towny 未安装，静默跳过
            return;
        }
        try {
            switchChecker = new SwitchChecker();
            available = true;
            logger.info("[AutoChest] Towny Hook 已启用喵~");
        } catch (Exception e) {
            logger.severe("[AutoChest] Towny Hook 初始化失败，deposit/restock 将被禁用: " + e.getMessage());
            available = false;
        }
    }

    @Override
    public boolean canAccess(Player player, Block... blocks) {
        // 双箱检查两半，任一被拒绝则排除整个容器
        for (Block block : blocks) {
            if (!switchChecker.canSwitch(player, block)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String hookName() {
        return HOOK_NAME;
    }

    /**
     * 通过反射调用 Towny PlayerCacheUtil.getCachePermission 的委托类
     */
    private static class SwitchChecker {

        /** 反射缓存：PlayerCacheUtil.getCachePermission 方法 */
        private final Method getCachePermission;

        /** 反射缓存：ActionType.SWITCH 枚举常量 */
        private final Object switchActionType;

        SwitchChecker() throws Exception {
            // 获取 PlayerCacheUtil.getCachePermission 方法
            Class<?> cacheUtilClass = Class.forName(
                    "com.palmergames.bukkit.towny.utils.PlayerCacheUtil");
            Class<?> actionTypeClass = Class.forName(
                    "com.palmergames.bukkit.towny.object.TownBlockPermissions$ActionType");
            getCachePermission = cacheUtilClass.getMethod(
                    "getCachePermission", Player.class, Location.class, Material.class, actionTypeClass);

            // 获取 ActionType.SWITCH 枚举常量
            switchActionType = Enum.valueOf((Class<Enum>) actionTypeClass, "SWITCH");
        }

        /**
         * 使用 Towny PlayerCacheUtil 判断玩家是否有权操作（SWITCH）该方块
         *
         * @param player 执行操作的玩家
         * @param block  目标方块
         * @return true 表示允许操作
         */
        boolean canSwitch(Player player, Block block) {
            try {
                Object result = getCachePermission.invoke(
                        null, player, block.getLocation(), block.getType(), switchActionType);
                return Boolean.TRUE.equals(result);
            } catch (Exception e) {
                // 喵~防御：反射调用失败时保守返回 false（拒绝访问）
                return false;
            }
        }
    }
}

