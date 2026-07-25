package io.github.autochest.scan;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScanTask 容器材料识别测试。
 * 验证全部原版潜影盒材料都能进入统一潜影盒容器分支。
 */
class ScanTaskTest {

    /**
     * 全部 17 种原版潜影盒材料都应被识别。
     */
    @Test
    void isShulkerBoxMaterial_allVanillaVariantsAreRecognized() {
        // 验证未染色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.SHULKER_BOX));
        // 验证白色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.WHITE_SHULKER_BOX));
        // 验证橙色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.ORANGE_SHULKER_BOX));
        // 验证品红色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.MAGENTA_SHULKER_BOX));
        // 验证淡蓝色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.LIGHT_BLUE_SHULKER_BOX));
        // 验证黄色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.YELLOW_SHULKER_BOX));
        // 验证黄绿色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.LIME_SHULKER_BOX));
        // 验证粉红色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.PINK_SHULKER_BOX));
        // 验证灰色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.GRAY_SHULKER_BOX));
        // 验证淡灰色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.LIGHT_GRAY_SHULKER_BOX));
        // 验证青色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.CYAN_SHULKER_BOX));
        // 验证紫色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.PURPLE_SHULKER_BOX));
        // 验证蓝色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.BLUE_SHULKER_BOX));
        // 验证棕色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.BROWN_SHULKER_BOX));
        // 验证绿色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.GREEN_SHULKER_BOX));
        // 验证红色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.RED_SHULKER_BOX));
        // 验证黑色潜影盒材料。
        assertTrue(ScanTask.isShulkerBoxMaterial(Material.BLACK_SHULKER_BOX));
    }

    /**
     * 非潜影盒或空材料不能被识别为潜影盒。
     */
    @Test
    void isShulkerBoxMaterial_nonShulkerMaterialIsRejected() {
        // 验证普通箱子不是潜影盒材料。
        assertFalse(ScanTask.isShulkerBoxMaterial(Material.CHEST));
        // 验证末影箱不是潜影盒材料。
        assertFalse(ScanTask.isShulkerBoxMaterial(Material.ENDER_CHEST));
        // 验证空材料被保守拒绝。
        assertFalse(ScanTask.isShulkerBoxMaterial(null));
    }
}
