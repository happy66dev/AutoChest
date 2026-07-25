package io.github.autochest.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 玩家容器偏好 GUI 安全事件监听器。
 * 阻止全部库存物品移动路径，并验证 Holder、玩家 UUID 与会话令牌。
 */
public final class PreferencesGuiListener implements Listener {

    /** GUI 渲染器与动作分发器。 */
    private final PreferencesGui preferencesGui;

    /** GUI 会话注册表。 */
    private final PreferencesGuiSessionRegistry sessionRegistry;

    /** 创建 GUI 监听器。 */
    public PreferencesGuiListener(PreferencesGui preferencesGui,
                                  PreferencesGuiSessionRegistry sessionRegistry) {
        // 喵~防御：缺失 GUI 或会话注册表时不能安全处理库存事件。
        if (preferencesGui == null || sessionRegistry == null) {
            throw new IllegalArgumentException("GUI 和会话注册表不能为空");
        }
        // 保存 GUI 分发器。
        this.preferencesGui = preferencesGui;
        // 保存会话注册表。
        this.sessionRegistry = sessionRegistry;
    }

    /** 拦截 GUI 打开期间的全部点击路径。 */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // 从顶部库存 Holder 精确识别本插件 GUI。
        PreferencesGuiHolder holder = getHolder(event.getView().getTopInventory());
        // 非本插件 GUI 不干预，避免影响其他插件库存。
        if (holder == null) {
            return;
        }
        // 无论点击顶部还是玩家背包都取消，阻断 shift/数字键/双击等所有路径。
        event.setCancelled(true);
        // 只有玩家可触发偏好 GUI 动作。
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // 仅顶部原始槽位会触发 GUI 按钮。
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        // 玩家 UUID、当前会话 token 和真实顶部库存都必须精确匹配。
        if (!player.getUniqueId().equals(holder.getPlayerUuid())
                || !sessionRegistry.isCurrent(player.getUniqueId(), holder.getSessionToken())
                || holder.getInventory() != event.getView().getTopInventory()) {
            return;
        }
        // 将已验证点击交给 GUI 动作分发器。
        preferencesGui.handleTopClick(player, holder, rawSlot);
    }

    /** 拦截覆盖 GUI 顶部槽位的拖拽。 */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        // 从顶部库存 Holder 精确识别本插件 GUI。
        PreferencesGuiHolder holder = getHolder(event.getView().getTopInventory());
        // 非本插件 GUI 不干预。
        if (holder == null) {
            return;
        }
        // 检查任意拖拽 raw slot 是否落入顶部 GUI。
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
                // 取消拖拽以防止玩家放入或取走展示物品。
                event.setCancelled(true);
                return;
            }
        }
    }

    /** 条件清理关闭页面的会话。 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // 从关闭顶部库存识别本插件 GUI。
        PreferencesGuiHolder holder = getHolder(event.getInventory());
        // 非本插件 GUI 不干预。
        if (holder == null) {
            return;
        }
        // 仅 token 仍匹配时清理，避免旧关闭事件删除新会话。
        sessionRegistry.clearIfCurrent(holder.getPlayerUuid(), holder.getSessionToken());
    }

    /** 玩家退出时清理 GUI 会话。 */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 使退出玩家的当前 GUI 会话失效。
        sessionRegistry.invalidate(event.getPlayer().getUniqueId());
    }

    /** 玩家死亡时关闭 GUI 并清理会话。 */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        // 关闭本插件 GUI 前先使会话失效。
        closeAndInvalidate(event.getPlayer());
    }

    /** 玩家切世界时关闭 GUI 并清理会话。 */
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        // 防止库存界面与世界切换流程交错。
        closeAndInvalidate(event.getPlayer());
    }

    /** 插件停用时使全部 GUI 会话失效。 */
    public void disable() {
        // 清空所有会话使后续迟到事件无法修改偏好。
        sessionRegistry.clear();
    }

    /** 关闭指定玩家当前的本插件 GUI。 */
    private void closeAndInvalidate(Player player) {
        // 喵~防御：空玩家没有可关闭库存。
        if (player == null) {
            return;
        }
        // 使当前 token 先失效。
        sessionRegistry.invalidate(player.getUniqueId());
        // 仅当顶部确为本插件 GUI 时关闭，避免关闭其他插件库存。
        if (getHolder(player.getOpenInventory().getTopInventory()) != null) {
            player.closeInventory();
        }
    }

    /** 从库存 Holder 安全获取本插件 GUI Holder。 */
    private PreferencesGuiHolder getHolder(Inventory inventory) {
        // 空库存不是本插件 GUI。
        if (inventory == null) {
            return null;
        }
        // 读取 Holder，不依赖库存标题。
        InventoryHolder holder = inventory.getHolder(false);
        // 仅接受本插件精确 Holder 类型。
        return holder instanceof PreferencesGuiHolder preferencesHolder ? preferencesHolder : null;
    }
}
