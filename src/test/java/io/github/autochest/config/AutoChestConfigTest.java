package io.github.autochest.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AutoChestConfig 配置读取和安全默认值测试
 */
class AutoChestConfigTest {

    /**
     * 空配置时应全部返回安全默认值
     */
    @Test
    void emptyConfig_returnsDefaults() {
        YamlConfiguration cfg = new YamlConfiguration();
        AutoChestConfig config = new AutoChestConfig(cfg);

        // 验证默认扫描半径
        assertEquals(8, config.getScanRadiusX());
        assertEquals(8, config.getScanRadiusY());
        assertEquals(8, config.getScanRadiusZ());
        // 验证默认每 tick 扫描方块数
        assertEquals(512, config.getScanBlocksPerTick());
        // 验证默认冷却时间（毫秒）
        assertEquals(5000L, config.getDepositCooldownMs());
        assertEquals(3000L, config.getRestockCooldownMs());
    }

    /**
     * 负数半径应被纠正为最小值 1
     */
    @Test
    void negativeRadius_clampsToMin() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("scan.radius-x", -5);
        cfg.set("scan.radius-y", 0);
        cfg.set("scan.radius-z", -100);

        AutoChestConfig config = new AutoChestConfig(cfg);

        // 喵~防御验证：负数被纠正为 1
        assertEquals(1, config.getScanRadiusX());
        assertEquals(1, config.getScanRadiusY());
        assertEquals(1, config.getScanRadiusZ());
    }

    /**
     * 超大半径应被纠正为最大值 64
     */
    @Test
    void oversizeRadius_clampsToMax() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("scan.radius-x", 999);

        AutoChestConfig config = new AutoChestConfig(cfg);

        assertEquals(64, config.getScanRadiusX());
    }

    /**
     * 无效音效名称应保留原始字符串（由 MessageService 播放时静默忽略），不抛出异常
     */
    @Test
    void invalidSound_storesRawString() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("sounds.success", "INVALID_SOUND_XYZ_123");

        AutoChestConfig config = new AutoChestConfig(cfg);

        // 直接存储原始字符串，不在此处验证有效性
        assertEquals("INVALID_SOUND_XYZ_123", config.getSoundSuccess());
    }

    /**
     * 空字符串消息应使用默认占位文本，不为 blank
     */
    @Test
    void blankMessage_usesDefault() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("messages.no-match", "   ");

        AutoChestConfig config = new AutoChestConfig(cfg);

        // 空白字符串应被默认值替换
        assertFalse(config.getMsgNoMatch().isBlank());
    }

    /**
     * reload 应生成独立的新实例，不影响原实例
     */
    @Test
    void reload_returnsIndependentInstance() {
        YamlConfiguration cfg1 = new YamlConfiguration();
        cfg1.set("scan.radius-x", 8);
        AutoChestConfig config1 = new AutoChestConfig(cfg1);

        YamlConfiguration cfg2 = new YamlConfiguration();
        cfg2.set("scan.radius-x", 16);
        AutoChestConfig config2 = new AutoChestConfig(cfg2);

        // 两个实例应该互相独立
        assertEquals(8, config1.getScanRadiusX());
        assertEquals(16, config2.getScanRadiusX());
        assertNotSame(config1, config2);
    }
}
