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
        // 只传入 rawSlot，由 markPlayerSlot 负责换算为玩家背包槽位
        markPlayerSlot(uuid, event.getRawSlot(), player);
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
        // 丢弃时手持槽（快捷栏 0..8）直接标记失效
        int heldSlot = player.getInventory().getHeldItemSlot();
        Set<Integer> slots = invalidatedSlots.get(uuid);
        if (slots != null) {
            slots.add(heldSlot);
        }
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
     * 将 rawSlot 转换为玩家背包槽位后标记失效
     * rawSlot 是整个库存视图的全局索引，玩家背包在视图底部从 topInventorySize 开始
     * 需要减去顶部容器大小才能得到真正的玩家背包槽位（0..35）
     *
     * @param uuid    玩家 UUID
     * @param rawSlot InventoryEvent 中的 rawSlot
     * @param player  玩家对象
     */
    private void markPlayerSlot(UUID uuid, int rawSlot, Player player) {
        org.bukkit.inventory.InventoryView view = player.getOpenInventory();
        int topSize = view.getTopInventory().getSize();

        // rawSlot 在顶部容器范围内，不是玩家背包槽位
        if (rawSlot < topSize) {
            return;
        }

        // 换算为玩家背包本地槽位
        int playerSlot = rawSlot - topSize;

        // 玩家背包视图中 0..26 = 主背包，27..35 = 快捷栏
        // 对应 PlayerInventory 的实际槽位：9..35（主背包）和 0..8（快捷栏）
        // Bukkit 的 InventoryView 中玩家背包部分布局：行0..2=主背包(9..35)，行3=快捷栏(0..8)
        int inventorySlot;
        if (playerSlot < 27) {
            // 主背包 row 0..2 对应 PlayerInventory 的槽位 9..35
            inventorySlot = playerSlot + 9;
        } else if (playerSlot < 36) {
            // 快捷栏 row 3 对应 PlayerInventory 的槽位 0..8
            inventorySlot = playerSlot - 27;
        } else {
            // 超出范围（盔甲/副手等），不追踪
            return;
        }

        // 确认在 0..35 范围内
        if (inventorySlot < 0 || inventorySlot > 35) {
            return;
        }

        Set<Integer> slots = invalidatedSlots.get(uuid);
        if (slots != null) {
            slots.add(inventorySlot);
        }
    }
}
