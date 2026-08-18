package io.github.autochest.gui;

/**
 * 槽位权限 GUI 与 Bukkit 玩家背包槽位之间的布局映射。
 */
final class InventorySlotLayout {

    /** 槽位权限预览区域在顶部 GUI 中的起始 raw slot。 */
    static final int GRID_START = 18;

    /** 预览区域包含三行主背包和一行快捷栏，共 36 格。 */
    static final int GRID_SIZE = 36;

    /** Bukkit 主背包首槽位。 */
    private static final int MAIN_INVENTORY_START = 9;

    /** Bukkit 主背包槽位数。 */
    private static final int MAIN_INVENTORY_SIZE = 27;

    /** Bukkit 快捷栏首槽位。 */
    private static final int HOTBAR_START = 0;

    /** Bukkit 快捷栏槽位数。 */
    private static final int HOTBAR_SIZE = 9;

    /** 此工具类不允许实例化。 */
    private InventorySlotLayout() {
        // 工具类只提供静态映射方法。
    }

    /**
     * 将权限 GUI raw slot 映射为真实 Bukkit 玩家背包槽位。
     *
     * @param rawSlot 顶部 GUI 的原始槽位。
     * @return 对应 Bukkit 槽位；预览区域外返回 -1。
     */
    static int inventorySlotAt(int rawSlot) {
        // 计算预览区域末尾的排他索引。
        int gridEndExclusive = GRID_START + GRID_SIZE;
        // 喵~防御：控制栏和 GUI 外点击没有对应玩家背包槽位。
        if (rawSlot < GRID_START || rawSlot >= gridEndExclusive) {
            return -1;
        }
        // 计算该格在四行预览中的相对位置。
        int gridOffset = rawSlot - GRID_START;
        // 前三行与实际背包主背包 9..35 保持相同视觉顺序。
        if (gridOffset < MAIN_INVENTORY_SIZE) {
            return MAIN_INVENTORY_START + gridOffset;
        }
        // 最后一行对应实际背包底部快捷栏 0..8。
        return HOTBAR_START + gridOffset - MAIN_INVENTORY_SIZE;
    }

    /**
     * 将真实 Bukkit 玩家背包槽位映射为权限 GUI raw slot。
     *
     * @param inventorySlot Bukkit 玩家背包槽位。
     * @return 对应 GUI raw slot；非 0..35 槽位返回 -1。
     */
    static int rawSlotAt(int inventorySlot) {
        // 主背包 9..35 直接落在预览前三行。
        if (inventorySlot >= MAIN_INVENTORY_START
                && inventorySlot < MAIN_INVENTORY_START + MAIN_INVENTORY_SIZE) {
            return GRID_START + inventorySlot - MAIN_INVENTORY_START;
        }
        // 快捷栏 0..8 固定显示于预览最后一行。
        if (inventorySlot >= HOTBAR_START && inventorySlot < HOTBAR_START + HOTBAR_SIZE) {
            return GRID_START + MAIN_INVENTORY_SIZE + inventorySlot - HOTBAR_START;
        }
        // 喵~防御：盔甲栏、副手栏及范围外槽位不属于此权限页面。
        return -1;
    }
}
