package io.github.autochest.task;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restock 目标槽位变化监听器
 * 监听可能改变玩家背包的事件，标记受影响的槽位为已失效
 * 提交时仍以实时 isSimilar 为最终判定，此监听器是优化提示
 */
public class RestockTargetListener implements Listener {

    /** 任务注册表，用于判断玩家是否有运行中的 restock 任务 */
    private final PlayerTaskRegistry registry;

    /**
     * 每名玩家已失效的目标槽位集合：UUID → Set<Integer>
     * ConcurrentHashSet 用 ConcurrentHashMap 模拟
     */
    private final ConcurrentHashMap<UUID, Set<Integer>> invalidatedSlots = new ConcurrentHashMap<>();

    /**
     * 创建 restock 目标槽位监听器
     *
     * @param registry 任务注册表
     */
    public RestockTargetListener(PlayerTaskRegistry registry) {
        this.registry = registry;
    }

    /**
     * 注册玩家的 restock 任务开始追踪槽位变化
     *
     * @param playerUuid 玩家 UUID
     */
    public void startTracking(UUID playerUuid) {
        invalidatedSlots.put(playerUuid, ConcurrentHashMap.newKeySet());
    }

    /**
     * 停止追踪并清理指定玩家的失效槽位数据
     *
     * @param playerUuid 玩家 UUID
     */
    public void stopTracking(UUID playerUuid) {
        invalidatedSlots.remove(playerUuid);
    }

    /**
     * 查询指定玩家的指定槽位是否已被标记失效
     * 提交时若实时 isSimilar 也不匹配，则跳过该槽位
     *
     * @param playerUuid 玩家 UUID
     * @param slot       槽位编号
     * @return true 表示已被标记失效
     */
    public boolean isSlotInvalidated(UUID playerUuid, int slot) {
        Set<Integer> slots = invalidatedSlots.get(playerUuid);
        return slots != null && slots.contains(slot);
    }

    /**
     * 玩家在库存界面点击时，标记受影响的槽位
     *
     * @param event 库存点击事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        // 仅处理有 restock 任务的玩家
        if (!registry.hasActiveTask(uuid) || !invalidatedSlots.containsKey(uuid)) {
            return;
        }
        // 标记被点击的原始槽位和 shift-click 可能影响的当前槽位
        markPlayerSlot(uuid, event.getRawSlot(), player);
        markPlayerSlot(uuid, event.getSlot(), player);
    }

    /**
     * 玩家拖拽物品时，标记所有参与槽位
     *
     * @param event 库存拖拽事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!registry.hasActiveTask(uuid) || !invalidatedSlots.containsKey(uuid)) {
            return;
        }
        // 标记所有被拖拽影响的槽位
        for (int slot : event.getRawSlots()) {
            markPlayerSlot(uuid, slot, player);
        }
    }

    /**
     * 玩家丢弃物品时，标记手持槽位
     *
     * @param event 丢弃物品事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!registry.hasActiveTask(uuid) || !invalidatedSlots.containsKey(uuid)) {
            return;
        }
        // 丢弃时手持槽即受影响
        markPlayerSlot(uuid, player.getInventory().getHeldItemSlot(), player);
    }

    /**
     * 玩家拾取物品时，标记可能被填充的所有槽位（保守处理）
     *
     * @param event 捡起物品事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!registry.hasActiveTask(uuid) || !invalidatedSlots.containsKey(uuid)) {
            return;
        }
        // 拾取可能影响任意背包槽位，保守地标记全部 0..35
        Set<Integer> slots = invalidatedSlots.get(uuid);
        if (slots != null) {
            for (int i = 0; i <= 35; i++) {
                slots.add(i);
            }
        }
    }

    /**
     * 将指定槽位标记为玩家背包失效槽（只处理背包范围内的槽位）
     *
     * @param uuid   玩家 UUID
     * @param slot   原始槽位编号
     * @param player 玩家对象，用于判断槽位是否在背包范围内
     */
    private void markPlayerSlot(UUID uuid, int slot, Player player) {
        // 只追踪背包 0..35 范围内的槽位
        if (slot < 0 || slot > 35) {
            return;
        }
        // 确认是玩家主库存（非其他容器的槽位）
        if (!(player.getOpenInventory().getTopInventory() instanceof PlayerInventory)
                && slot < player.getOpenInventory().getTopInventory().getSize()) {
            return;
        }
        Set<Integer> slots = invalidatedSlots.get(uuid);
        if (slots != null) {
            slots.add(slot);
        }
    }
}
