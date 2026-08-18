package io.github.autochest.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 槽位权限 GUI 的真实背包布局映射测试。
 */
class InventorySlotLayoutTest {

    /** 前三行必须按主背包视觉顺序映射到 Bukkit 9..35。 */
    @Test
    void inventorySlotAt_mapsMainInventoryAcrossFirstThreeRows() {
        // 验证主背包首槽映射到预览左上角。
        assertEquals(9, InventorySlotLayout.inventorySlotAt(18));
        // 验证主背包第一行末槽映射正确。
        assertEquals(17, InventorySlotLayout.inventorySlotAt(26));
        // 验证主背包第二行首槽映射正确。
        assertEquals(18, InventorySlotLayout.inventorySlotAt(27));
        // 验证主背包末槽映射到第三行末尾。
        assertEquals(35, InventorySlotLayout.inventorySlotAt(44));
    }

    /** 最后一行必须按快捷栏视觉顺序映射到 Bukkit 0..8。 */
    @Test
    void inventorySlotAt_mapsHotbarAcrossLastRow() {
        // 验证快捷栏首槽映射到预览最后一行左侧。
        assertEquals(0, InventorySlotLayout.inventorySlotAt(45));
        // 验证快捷栏末槽映射到预览最后一行右侧。
        assertEquals(8, InventorySlotLayout.inventorySlotAt(53));
    }

    /** 预览区域外的控制栏与无效槽位不能映射到真实背包。 */
    @Test
    void inventorySlotAt_rejectsSlotsOutsidePreviewGrid() {
        // 验证预览前一格不接受点击。
        assertEquals(-1, InventorySlotLayout.inventorySlotAt(17));
        // 验证关闭按钮不接受点击。
        assertEquals(-1, InventorySlotLayout.inventorySlotAt(8));
        // 验证预览后一格不接受点击。
        assertEquals(-1, InventorySlotLayout.inventorySlotAt(54));
    }

    /** 正反映射必须在完整 0..35 与预览 18..53 范围内互为逆运算。 */
    @Test
    void layoutMappings_roundTripEveryPlayerInventorySlot() {
        // 遍历真实玩家背包全部 36 个槽位。
        for (int inventorySlot = 0; inventorySlot <= 35; inventorySlot++) {
            // 将真实槽位转换为 GUI raw slot。
            int rawSlot = InventorySlotLayout.rawSlotAt(inventorySlot);
            // 验证反向映射恢复原始 Bukkit 槽位。
            assertEquals(inventorySlot, InventorySlotLayout.inventorySlotAt(rawSlot));
        }
        // 验证范围外 Bukkit 槽位不会生成 GUI 坐标。
        assertEquals(-1, InventorySlotLayout.rawSlotAt(-1));
        assertEquals(-1, InventorySlotLayout.rawSlotAt(36));
    }
}
