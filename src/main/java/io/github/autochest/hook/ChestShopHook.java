package io.github.autochest.hook;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * ChestShop Hook 适配器
 * 策略：商店箱一律排除，不考虑店主身份
 * 双箱任意一半是商店箱即排除整个容器
 * 通过反射访问 ChestShop API，避免直接类型依赖导致未安装时启动失败
 */
public class ChestShopHook implements ContainerAccessPolicy {

    /** Hook 名称 */
    private static final String HOOK_NAME = "ChestShop";

    /** 是否成功初始化 */
    private boolean available = false;

    /** 通过反射缓存的商店识别方法 */
    private Method isShopMethod;

    /**
     * 尝试通过反射初始化 ChestShop Hook
     *
     * @param logger 日志记录器
     */
    public ChestShopHook(Logger logger) {
        if (Bukkit.getPluginManager().getPlugin("ChestShop") == null) {
            // ChestShop 未安装，静默跳过
            return;
        }
        try {
            // 通过反射获取 ChestShop 的商店识别方法，隔离具体 API 依赖
            Class<?> shopClass = Class.forName("com.Acrobot.ChestShop.Signs.ChestShopSign");
            isShopMethod = shopClass.getMethod("isShop", Block.class);
            available = true;
            logger.info("[AutoChest] ChestShop Hook 已启用喵~");
        } catch (Exception e) {
            // 喵~防御：反射失败，可能是 ChestShop API 变更，标记不可用
            logger.severe("[AutoChest] ChestShop Hook 初始化失败，deposit/restock 将被禁用: " + e.getMessage());
            available = false;
        }
    }

    @Override
    public boolean canAccess(Player player, Block... blocks) {
        for (Block block : blocks) {
            try {
                // 反射调用 ChestShopSign.isShop(block)，返回 true 表示是商店箱
                Object result = isShopMethod.invoke(null, block);
                if (Boolean.TRUE.equals(result)) {
                    // 商店箱一律排除
                    return false;
                }
            } catch (Exception e) {
                // 喵~防御：反射调用失败时保守排除该容器
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
}
