package io.github.autochest.hook;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * 容器访问策略接口，屏蔽第三方保护插件的具体类型
 * 实现类不得在此接口方法签名中出现任何第三方类型
 */
public interface ContainerAccessPolicy {

    /**
     * 判断指定玩家是否可以通过 AutoChest 访问给定方块组成的容器
     * 对于双箱，blocks 数组包含两个方块，任一被拒绝则整个容器被拒绝
     * 此方法必须在主线程调用
     *
     * @param player 执行操作的玩家
     * @param blocks 容器包含的方块（单箱/木桶传 1 个，双箱传 2 个）
     * @return true 表示允许访问，false 表示拒绝
     */
    boolean canAccess(Player player, Block... blocks);

    /**
     * 判断对应插件是否已安装在服务器上
     * 未安装时 Hook 静默跳过，不参与任何策略检查
     *
     * @return true 表示插件已安装
     */
    boolean isInstalled();

    /**
     * 判断此 Hook 是否可用（已安装且初始化成功）
     * 插件未安装时返回 false；已安装但初始化失败时也返回 false
     * 调用前应先检查 isInstalled()：未安装时 isAvailable() 无意义
     *
     * @return true 表示插件已安装且 Hook 初始化成功
     */
    boolean isAvailable();

    /**
     * 返回此 Hook 对应的保护插件名称，用于日志和消息显示
     *
     * @return 插件名称，如 "WorldGuard"
     */
    String hookName();
}
