package io.github.autochest.hook;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Slimefun Hook 适配器。
 * 策略：任意容器方块拥有 Slimefun 方块数据时均拒绝自动访问。
 * 双箱任一半拥有 Slimefun 方块数据时排除整个容器。
 * 通过反射访问 Slimefun API，避免未安装时产生编译期类型依赖。
 */
public class SlimefunHook implements ContainerAccessPolicy {

    /** Slimefun Hook 在日志和消息中使用的名称。 */
    private static final String HOOK_NAME = "Slimefun";

    /** Slimefun 公开方块存储 API 的完整类名。 */
    private static final String BLOCK_STORAGE_CLASS_NAME = "me.mrCookieSlime.Slimefun.api.BlockStorage";

    /** 已安装状态表示服务器插件管理器发现了 Slimefun。 */
    private boolean installed = false;

    /** 可用状态表示反射方法已成功解析且可以安全调用。 */
    private boolean available = false;

    /** 缓存 Slimefun BlockStorage.hasBlockInfo(Block) 的反射方法。 */
    private Method hasBlockInfoMethod;

    /**
     * 尝试初始化 Slimefun 方块数据识别 Hook。
     *
     * @param logger 用于输出 Hook 初始化结果的插件日志记录器。
     */
    public SlimefunHook(Logger logger) {
        // 未安装 Slimefun 时不参与策略判断，保持现有原版容器行为。
        if (Bukkit.getPluginManager().getPlugin(HOOK_NAME) == null) {
            return;
        }
        // 已检测到 Slimefun 插件，后续初始化失败必须由复合策略保守拒绝任务。
        installed = true;
        try {
            // 反射加载公开兼容 API，避免 AutoChest 产生 Slimefun 编译期依赖。
            Class<?> blockStorageClass = Class.forName(BLOCK_STORAGE_CLASS_NAME);
            // 缓存只读的方块数据检测方法，用于扫描和提交前的主线程重验。
            hasBlockInfoMethod = blockStorageClass.getMethod("hasBlockInfo", Block.class);
            // 方法签名正确后将 Hook 标记为可用。
            available = true;
            // 记录启用状态，便于服务器管理员确认保护策略已生效。
            logger.info("[AutoChest] Slimefun Hook 已启用喵~");
        } catch (Exception | LinkageError exception) {
            // 喵~防御：Slimefun API 缺失、签名变化或类链接失败时保持不可用，禁止绕过机器保护。
            logger.severe("[AutoChest] Slimefun Hook 初始化失败，deposit/restock 将被禁用: "
                    + exception.getMessage());
            // 明确保持不可用，供 CompositeAccessPolicy 触发 fail-closed。
            available = false;
        }
    }

    /**
     * 判断容器是否不含任何 Slimefun 方块数据。
     *
     * @param player 当前执行命令的玩家，本策略不依赖玩家身份但保留统一接口。
     * @param blocks 构成目标容器的方块，双箱会传入两个方块。
     * @return true 表示所有方块均不是 Slimefun 管理方块，false 表示必须跳过。
     */
    @Override
    public boolean canAccess(Player player, Block... blocks) {
        // 喵~防御：空数组无法确认目标容器，保守拒绝以避免遗漏 Slimefun 方块。
        if (blocks == null || blocks.length == 0) {
            return false;
        }
        // 逐个检查容器方块，双箱任意一半命中即拒绝整个逻辑容器。
        for (Block block : blocks) {
            // 喵~防御：空方块无法完成安全识别，保守拒绝当前容器。
            if (block == null) {
                return false;
            }
            try {
                // 调用 Slimefun 公开 API；true 表示该方块存在 Slimefun 数据记录。
                Object result = hasBlockInfoMethod.invoke(null, block);
                // 发现 Slimefun 管理方块时立即排除，避免向其库存自动移动物品。
                if (Boolean.TRUE.equals(result)) {
                    return false;
                }
            } catch (Exception exception) {
                // 喵~防御：运行期反射或 Slimefun 存储访问失败时保守跳过容器。
                return false;
            }
        }
        // 所有方块均明确不带 Slimefun 数据时才允许后续策略继续判断。
        return true;
    }

    /**
     * 返回 Slimefun 是否被服务器加载。
     *
     * @return true 表示检测到 Slimefun 插件。
     */
    @Override
    public boolean isInstalled() {
        // 返回构造期记录的插件安装状态。
        return installed;
    }

    /**
     * 返回 Slimefun 反射 API 是否已成功初始化。
     *
     * @return true 表示方块数据识别方法可用。
     */
    @Override
    public boolean isAvailable() {
        // 返回构造期记录的反射初始化状态。
        return available;
    }

    /**
     * 返回 Hook 名称，供复合策略记录日志和反馈消息。
     *
     * @return 固定的 Slimefun 插件名称。
     */
    @Override
    public String hookName() {
        // 返回与 plugin.yml softdepend 一致的插件名称。
        return HOOK_NAME;
    }
}
