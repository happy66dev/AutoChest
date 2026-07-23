package io.github.autochest.task;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家生命周期事件监听器
 * 监听退出、换世界、死亡三种事件，立即使对应玩家的当前任务失效
 * 不持有 Player 引用超过事件处理方法的作用域
 */
public class PlayerLifecycleListener implements Listener {

    /** 任务注册表，用于递增 session epoch */
    private final PlayerTaskRegistry registry;

    /**
     * 创建生命周期监听器
     *
     * @param registry 玩家任务注册表
     */
    public PlayerLifecycleListener(PlayerTaskRegistry registry) {
        this.registry = registry;
    }

    /**
     * 玩家退出服务器时立即使任务失效
     * 使用 MONITOR 优先级确保其他插件处理完毕后才标记失效
     *
     * @param event 退出事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 只取 UUID，不保存 Player 对象引用
        registry.invalidate(event.getPlayer().getUniqueId());
    }

    /**
     * 玩家切换世界时立即使任务失效
     * 扫描中心固定在初始世界，切换世界后任务无法继续
     *
     * @param event 换世界事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        registry.invalidate(event.getPlayer().getUniqueId());
    }

    /**
     * 玩家死亡时立即使任务失效
     * 防止死亡后的背包状态变化（keepInventory 等）与任务产生竞态
     * 使用 MONITOR 优先级，在其他插件处理死亡事件后才标记失效
     *
     * @param event 死亡事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        registry.invalidate(event.getPlayer().getUniqueId());
    }
}
