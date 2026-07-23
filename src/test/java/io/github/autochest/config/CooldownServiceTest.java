package io.github.autochest.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CooldownService 冷却服务测试
 * 不依赖 Bukkit，纯 Java 测试
 */
class CooldownServiceTest {

    private CooldownService service;
    private UUID playerA;
    private UUID playerB;

    @BeforeEach
    void setUp() {
        // 创建默认配置（deposit=5000ms, restock=3000ms）
        org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
        service = new CooldownService(new AutoChestConfig(cfg));
        playerA = UUID.randomUUID();
        playerB = UUID.randomUUID();
    }

    /**
     * 未记录过的玩家不应处于冷却中
     */
    @Test
    void newPlayer_notOnCooldown() {
        assertFalse(service.isOnCooldown(playerA, CooldownService.OperationType.DEPOSIT));
        assertFalse(service.isOnCooldown(playerA, CooldownService.OperationType.RESTOCK));
    }

    /**
     * 记录后立即检查，应处于冷却中
     */
    @Test
    void afterRecord_isOnCooldown() {
        service.record(playerA, CooldownService.OperationType.DEPOSIT);
        assertTrue(service.isOnCooldown(playerA, CooldownService.OperationType.DEPOSIT));
    }

    /**
     * deposit 和 restock 冷却互不干扰
     */
    @Test
    void depositAndRestockCooldowns_areIndependent() {
        service.record(playerA, CooldownService.OperationType.DEPOSIT);

        // deposit 在冷却，restock 不应受影响
        assertTrue(service.isOnCooldown(playerA, CooldownService.OperationType.DEPOSIT));
        assertFalse(service.isOnCooldown(playerA, CooldownService.OperationType.RESTOCK));
    }

    /**
     * 不同玩家的冷却互不干扰
     */
    @Test
    void differentPlayers_independentCooldowns() {
        service.record(playerA, CooldownService.OperationType.RESTOCK);

        assertTrue(service.isOnCooldown(playerA, CooldownService.OperationType.RESTOCK));
        assertFalse(service.isOnCooldown(playerB, CooldownService.OperationType.RESTOCK));
    }

    /**
     * 剩余冷却时间应在 0 到冷却时长之间
     */
    @Test
    void getRemainingMs_withinExpectedRange() {
        service.record(playerA, CooldownService.OperationType.DEPOSIT);
        long remaining = service.getRemainingMs(playerA, CooldownService.OperationType.DEPOSIT);

        // 剩余时间应在合理范围内（0 < remaining <= 5000ms）
        assertTrue(remaining > 0 && remaining <= 5000L,
                "Remaining: " + remaining + " should be in (0, 5000]");
    }

    /**
     * 未记录时剩余冷却时间应为 0
     */
    @Test
    void getRemainingMs_neverRecorded_returnsZero() {
        assertEquals(0L, service.getRemainingMs(playerA, CooldownService.OperationType.DEPOSIT));
    }

    /**
     * clear 后不再处于冷却中
     */
    @Test
    void clear_removesAllCooldowns() {
        service.record(playerA, CooldownService.OperationType.DEPOSIT);
        service.record(playerB, CooldownService.OperationType.RESTOCK);

        service.clear();

        assertFalse(service.isOnCooldown(playerA, CooldownService.OperationType.DEPOSIT));
        assertFalse(service.isOnCooldown(playerB, CooldownService.OperationType.RESTOCK));
    }
}
