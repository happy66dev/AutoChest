package io.github.autochest.gui;

import io.github.autochest.task.OperationType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * AutoChest 容器偏好 GUI 的专属库存 Holder。
 * 使用 Holder 而非标题识别页面，避免与其他插件同名库存冲突。
 */
public final class PreferencesGuiHolder implements InventoryHolder {

    /** GUI 页面类型。 */
    public enum PageType {
        /** 存入与补货选择主菜单。 */
        MAIN,
        /** deposit 或 restock 的独立配置页面。 */
        OPERATION
    }

    /** 当前页面所属的玩家 UUID。 */
    private final UUID playerUuid;

    /** 当前页面固化的会话令牌。 */
    private final UUID sessionToken;

    /** 当前 GUI 页面类型。 */
    private final PageType pageType;

    /** 操作页面对应的操作类型，主菜单为 null。 */
    private final OperationType operation;

    /** Bukkit 创建后绑定的库存实例。 */
    private Inventory inventory;

    /**
     * 创建 GUI Holder。
     *
     * @param playerUuid 页面所属玩家 UUID。
     * @param sessionToken 当前会话令牌。
     * @param pageType 页面类型。
     * @param operation 操作类型，主菜单可为空。
     */
    public PreferencesGuiHolder(UUID playerUuid, UUID sessionToken, PageType pageType, OperationType operation) {
        // 喵~防御：缺少玩家、令牌或页面类型时不能创建可交互 GUI。
        if (playerUuid == null || sessionToken == null || pageType == null) {
            throw new IllegalArgumentException("GUI Holder 参数不能为空");
        }
        // 保存页面归属玩家。
        this.playerUuid = playerUuid;
        // 保存页面会话令牌。
        this.sessionToken = sessionToken;
        // 保存页面类型。
        this.pageType = pageType;
        // 保存独立操作页面的操作类型。
        this.operation = operation;
    }

    /**
     * 绑定 Bukkit 创建的库存实例。
     *
     * @param inventory 当前 Holder 对应的库存。
     */
    public void bindInventory(Inventory inventory) {
        // 喵~防御：空库存不绑定，避免后续事件错误引用。
        if (inventory != null) {
            this.inventory = inventory;
        }
    }

    /** 获取页面所属玩家 UUID。 */
    public UUID getPlayerUuid() {
        // 返回不可变页面归属。
        return playerUuid;
    }

    /** 获取当前页面会话令牌。 */
    public UUID getSessionToken() {
        // 返回不可变会话令牌。
        return sessionToken;
    }

    /** 获取页面类型。 */
    public PageType getPageType() {
        // 返回不可变页面类型。
        return pageType;
    }

    /** 获取操作页面的操作类型。 */
    public OperationType getOperation() {
        // 返回主菜单为空或操作页面对应类型。
        return operation;
    }

    /** 返回 Holder 绑定的库存。 */
    @Override
    public Inventory getInventory() {
        // 返回由 GUI 创建阶段绑定的库存实例。
        return inventory;
    }
}
