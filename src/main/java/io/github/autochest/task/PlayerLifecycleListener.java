package io.github.autochest.task;

// 导入 PlayerBackpack 任务资源注册表以在生命周期变化时释放外部会话喵~
import io.github.autochest.integration.playerbackpack.PlayerBackpackTaskContexts;
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

    /** PlayerBackpack 外部会话资源表，生命周期结束时必须释放 */
    private final PlayerBackpackTaskContexts playerBackpackContexts;

    /**
     * 创建生命周期监听器
     *
     * @param registry 玩家任务注册表
     */
    public PlayerLifecycleListener(PlayerTaskRegistry registry) {
        this(registry, new PlayerBackpackTaskContexts());
    }

    /**
     * 创建可注入资源表的生命周期监听器
     *
     * @param registry 玩家任务注册表
     * @param playerBackpackContexts PlayerBackpack 会话资源表
     */
    public PlayerLifecycleListener(PlayerTaskRegistry registry,
                                   PlayerBackpackTaskContexts playerBackpackContexts) {
        // 喵~防御：生命周期监听器缺少任一资源表时拒绝启动喵~
        if (registry == null || playerBackpackContexts == null) {
            // 抛出明确异常，避免事件触发时静默泄漏会话喵~
            throw new IllegalArgumentException("生命周期监听器依赖不能为空喵~");
        }
        // 保存 AutoChest 任务表喵~
        this.registry = registry;
        // 保存 PlayerBackpack 会话表喵~
        this.playerBackpackContexts = playerBackpackContexts;
    }

    /**
     * 玩家退出服务器时立即使任务失效
     * 使用 MONITOR 优先级确保其他插件处理完毕后才标记失效
     *
     * @param event 退出事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 先使 AutoChest 任务失效，阻止迟到回调继续写入喵~
        registry.invalidate(event.getPlayer().getUniqueId());
        // 释放 PlayerBackpack 独占会话，防止玩家离线后目标背包永久繁忙喵~
        playerBackpackContexts.releasePlayer(event.getPlayer().getUniqueId());
    }

    /**
     * 玩家切换世界时立即使任务失效
     * 扫描中心固定在初始世界，切换世界后任务无法继续
     *
     * @param event 换世界事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        // 先使 AutoChest 任务失效，阻止旧世界回调继续写入喵~
        registry.invalidate(event.getPlayer().getUniqueId());
        // 释放跨域会话，避免换世界后的任务继续占用目标背包喵~
        playerBackpackContexts.releasePlayer(event.getPlayer().getUniqueId());
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
        // 先使 AutoChest 任务失效，阻止死亡处理后的迟到写入喵~
        registry.invalidate(event.getPlayer().getUniqueId());
        // 释放跨域会话，避免死亡后的背包状态继续被任务占用喵~
        playerBackpackContexts.releasePlayer(event.getPlayer().getUniqueId());
    }
}
