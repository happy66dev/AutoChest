package io.github.autochest.task;

import io.github.autochest.service.RestockTargetWhitelist;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restock 目标槽位变化监听器
 * 监听可能改变玩家背包的事件，并直接使对应白名单槽位永久失效
 * 提交时仍以实时 isSimilar 为最终判定
 */
public class RestockTargetListener implements Listener {

    /** 正在追踪的补货白名单：UUID → 当前任务的唯一白名单实例 */
    private final ConcurrentHashMap<UUID, RestockTargetWhitelist> trackedWhitelists = new ConcurrentHashMap<>();

    /**
     * 创建 restock 目标槽位监听器
     */
    public RestockTargetListener() {
    }

    /**
     * 注册玩家的 restock 任务开始追踪槽位变化
     *
     * @param playerUuid 玩家 UUID
     * @param whitelist  当前任务唯一的目标槽位白名单
     */
    public void startTracking(UUID playerUuid, RestockTargetWhitelist whitelist) {
        // 喵~防御：无效参数不建立追踪，避免事件线程空引用失败。
        if (playerUuid == null || whitelist == null) {
            return;
        }
        trackedWhitelists.put(playerUuid, whitelist);
    }

    /**
     * 停止追踪指定任务的白名单引用
     * 仅当引用与当前追踪对象相同才移除，防止迟到回调清理新任务
     *
     * @param playerUuid 玩家 UUID
     * @param whitelist  即将结束任务的目标槽位白名单
     */
    public void stopTracking(UUID playerUuid, RestockTargetWhitelist whitelist) {
        // 喵~防御：无效参数不执行删除，避免迟到回调错误清理新任务。
        if (playerUuid == null || whitelist == null) {
            return;
        }
        trackedWhitelists.remove(playerUuid, whitelist);
    }

    /**
     * 玩家在库存界面点击时，使全部目标槽位失效
     * 点击可能通过 shift-click、热键或双击影响未知数量的玩家槽位
     *
     * @param event 库存点击事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        // 喵~防御：只有玩家库存操作才可能影响本插件的补货目标。
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        invalidateAll(player.getUniqueId());
    }

    /**
     * 玩家拖拽物品时，使全部目标槽位失效
     * 拖拽可同时影响顶部容器和多个玩家槽位，无法稳定枚举完整影响范围
     *
     * @param event 库存拖拽事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        // 喵~防御：只有玩家拖拽才可能影响本插件的补货目标。
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        invalidateAll(player.getUniqueId());
    }

    /**
     * 玩家丢弃物品时，精确使手持快捷栏目标槽位失效
     *
     * @param event 丢弃物品事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        invalidateSlot(player.getUniqueId(), player.getInventory().getHeldItemSlot());
    }

    /**
     * 玩家拾取物品时，使全部目标槽位失效
     * 拾取会优先合并堆叠或填入空槽，无法可靠确定最终受影响槽位
     *
     * @param event 捡起物品事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        // 喵~防御：非玩家实体没有本插件追踪的背包目标槽位。
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        invalidateAll(player.getUniqueId());
    }

    /**
     * 使一个明确受影响的目标槽位永久失效
     *
     * @param playerUuid 玩家 UUID
     * @param slot       玩家背包槽位编号
     */
    private void invalidateSlot(UUID playerUuid, int slot) {
        RestockTargetWhitelist whitelist = trackedWhitelists.get(playerUuid);
        if (whitelist != null) {
            whitelist.invalidateSlot(slot);
        }
    }

    /**
     * 使本次任务全部目标槽位永久失效
     * 宁可跳过本次补货，也不能向玩家手动调整后的槽位写入物品
     *
     * @param playerUuid 玩家 UUID
     */
    private void invalidateAll(UUID playerUuid) {
        RestockTargetWhitelist whitelist = trackedWhitelists.get(playerUuid);
        if (whitelist != null) {
            whitelist.invalidateAll();
        }
    }
}
