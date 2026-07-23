package io.github.autochest.hook;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * WorldGuard Hook 适配器
 * 策略：目标位置存在任意非 __global__ 区域时排除容器，不检查玩家权限
 * 通过反射隔离 WorldGuard 具体类型，未安装时不影响插件加载
 */
public class WorldGuardHook implements ContainerAccessPolicy {

    /** Hook 名称，用于日志和消息 */
    private static final String HOOK_NAME = "WorldGuard";

    /** 是否已安装（插件存在于服务器） */
    private boolean installed = false;

    /** 是否成功初始化 */
    private boolean available = false;

    /** 反射缓存：isInAnyNonGlobalRegion 的委托方法 */
    private RegionChecker regionChecker;

    /**
     * 尝试通过反射初始化 WorldGuard Hook
     * 若插件未安装则静默跳过；已安装但反射失败则标记不可用
     *
     * @param logger 日志记录器
     */
    public WorldGuardHook(Logger logger) {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            // WorldGuard 未安装，静默不启用
            return;
        }
        installed = true;
        try {
            regionChecker = new RegionChecker();
            available = true;
            logger.info("[AutoChest] WorldGuard Hook 已启用喵~");
        } catch (Exception e) {
            // 喵~防御：初始化失败，标记不可用
            logger.severe("[AutoChest] WorldGuard Hook 初始化失败，deposit/restock 将被禁用: " + e.getMessage());
            available = false;
        }
    }

    @Override
    public boolean canAccess(Player player, Block... blocks) {
        // 对每个方块检查是否存在非全局区域，任一方块在区域内即排除
        for (Block block : blocks) {
            if (regionChecker.isInAnyNonGlobalRegion(block.getLocation())) {
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
     * 通过反射调用 WorldGuard API 的区域检查逻辑
     * 彻底隔离 WorldGuard 类型，消除编译期依赖
     */
    private static class RegionChecker {

        /** 反射缓存：WorldGuard.getInstance() */
        private final Object worldGuardInstance;

        /** 反射缓存：getRegionContainer() 方法链 */
        private final Method getRegionContainer;
        private final Method getPlatform;
        private final Method getRegionManager;
        private final Method getApplicableRegions;
        private final Method adaptWorld;
        private final Method asBlockVector;
        private final Method regionIterator;
        private final Method regionGetId;

        RegionChecker() throws Exception {
            // 获取 WorldGuard 单例
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Method getInstance = wgClass.getMethod("getInstance");
            worldGuardInstance = getInstance.invoke(null);

            // 缓存平台和区域容器方法
            getPlatform = wgClass.getMethod("getPlatform");
            Object platform = getPlatform.invoke(worldGuardInstance);
            getRegionContainer = platform.getClass().getMethod("getRegionContainer");

            // 缓存 BukkitAdapter 的 adapt(World) 和 asBlockVector(Location) 方法
            Class<?> adapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            adaptWorld = adapterClass.getMethod("adapt", org.bukkit.World.class);
            asBlockVector = adapterClass.getMethod("asBlockVector", Location.class);

            // 缓存 RegionContainer.get(World) 方法
            Class<?> containerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionContainer");
            Class<?> worldClass = Class.forName("com.sk89q.worldguard.LocalWorld");
            getRegionManager = containerClass.getMethod("get", worldClass);

            // 缓存 RegionManager.getApplicableRegions 方法
            Class<?> managerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager");
            Class<?> blockVectorClass = Class.forName("com.sk89q.math.geom.BlockVector3");
            getApplicableRegions = managerClass.getMethod("getApplicableRegions", blockVectorClass);

            // 缓存 ApplicableRegionSet 迭代器和 ProtectedRegion.getId()
            regionIterator = Class.forName("com.sk89q.worldguard.protection.ApplicableRegionSet")
                    .getMethod("iterator");
            regionGetId = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion")
                    .getMethod("getId");
        }

        /**
         * 判断给定位置是否存在任意非 __global__ 区域
         *
         * @param location Bukkit 位置
         * @return true 表示位置在受保护区域内
         */
        boolean isInAnyNonGlobalRegion(Location location) {
            try {
                Object platform = getPlatform.invoke(worldGuardInstance);
                Object container = getRegionContainer.invoke(platform);
                Object adaptedWorld = adaptWorld.invoke(null, location.getWorld());
                Object manager = getRegionManager.invoke(container, adaptedWorld);
                if (manager == null) {
                    return false;
                }
                Object blockVec = asBlockVector.invoke(null, location);
                Object regionSet = getApplicableRegions.invoke(manager, blockVec);
                java.util.Iterator<?> iter = (java.util.Iterator<?>) regionIterator.invoke(regionSet);
                while (iter.hasNext()) {
                    Object region = iter.next();
                    String id = (String) regionGetId.invoke(region);
                    if (!"__global__".equals(id)) {
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                // 喵~防御：反射调用失败时保守返回 true（视为受保护，排除容器）
                return true;
            }
        }
    }
}

