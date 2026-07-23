package io.github.autochest.hook;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;

/**
 * Towny Hook 适配器
 * 策略：按地块容器/开关权限（ActionType.SWITCH）判断玩家是否可访问容器
 * 通过反射隔离 Towny 类型，消除编译期依赖
 */
public class TownyHook implements ContainerAccessPolicy {

    /** Hook 名称 */
    private static final String HOOK_NAME = "Towny";

    /** 当前 Towny API 首选权限动作枚举类 */
    private static final String CURRENT_ACTION_TYPE_CLASS =
            "com.palmergames.bukkit.towny.object.TownyPermission$ActionType";

    /** 历史或非标准 Towny 分支的权限动作枚举类回退路径 */
    private static final String LEGACY_ACTION_TYPE_CLASS =
            "com.palmergames.bukkit.towny.object.TownBlockPermissions$ActionType";

    /** 是否已安装（插件存在于服务器） */
    private boolean installed = false;

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
            return;
        }
        installed = true;
        try {
            switchChecker = new SwitchChecker();
            available = true;
            logger.info("[AutoChest] Towny Hook 已启用喵~ ActionType="
                    + switchChecker.getActionTypeClassName());
        } catch (Exception exception) {
            logger.severe("[AutoChest] Towny Hook 初始化失败，deposit/restock 将被禁用: "
                    + exception.getMessage());
            available = false;
        }
    }

    @Override
    public boolean canAccess(Player player, Block... blocks) {
        for (Block block : blocks) {
            if (!switchChecker.canSwitch(player, block)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isInstalled() {
        return installed;
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

        /** 实际命中的 Towny 权限动作枚举类名 */
        private final String actionTypeClassName;

        SwitchChecker() throws Exception {
            Class<?> cacheUtilClass = Class.forName(
                    "com.palmergames.bukkit.towny.utils.PlayerCacheUtil");
            Exception lastFailure = null;
            Method resolvedPermissionMethod = null;
            Object resolvedSwitchActionType = null;
            String resolvedActionTypeClassName = null;
            for (String candidateClassName : List.of(CURRENT_ACTION_TYPE_CLASS, LEGACY_ACTION_TYPE_CLASS)) {
                try {
                    Class<?> candidateActionTypeClass = Class.forName(candidateClassName);
                    resolvedPermissionMethod = cacheUtilClass.getMethod(
                            "getCachePermission", Player.class, Location.class, Material.class,
                            candidateActionTypeClass);
                    resolvedSwitchActionType = Enum.valueOf(
                            (Class<Enum>) candidateActionTypeClass, "SWITCH");
                    resolvedActionTypeClassName = candidateClassName;
                    break;
                } catch (Exception exception) {
                    lastFailure = exception;
                }
            }
            if (resolvedPermissionMethod == null || resolvedSwitchActionType == null
                    || resolvedActionTypeClassName == null) {
                throw new IllegalStateException("未找到兼容的 Towny ActionType 与 getCachePermission 签名", lastFailure);
            }
            getCachePermission = resolvedPermissionMethod;
            switchActionType = resolvedSwitchActionType;
            actionTypeClassName = resolvedActionTypeClassName;
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
            } catch (Exception exception) {
                // 喵~防御：反射调用失败时保守返回 false，拒绝访问。
                return false;
            }
        }

        /**
         * 获取实际命中的权限动作枚举类名
         *
         * @return Towny 反射兼容路径
         */
        String getActionTypeClassName() {
            return actionTypeClassName;
        }
    }
}
