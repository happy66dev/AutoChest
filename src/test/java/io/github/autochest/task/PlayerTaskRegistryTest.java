package io.github.autochest.task;

import io.github.autochest.config.AutoChestConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlayerTaskRegistry 任务注册表测试
 * 纯 Java 逻辑，不依赖 Bukkit
 */
class PlayerTaskRegistryTest {

    private PlayerTaskRegistry registry;
    private AutoChestConfig config;
    private UUID playerUuid;
    private UUID worldUuid;

    @BeforeEach
    void setUp() {
        registry = new PlayerTaskRegistry();
        config = new AutoChestConfig(new YamlConfiguration());
        playerUuid = UUID.randomUUID();
        worldUuid = UUID.randomUUID();
    }

    /**
     * 首次 tryAcquire 应成功返回任务
     */
    @Test
    void tryAcquire_firstTime_succeeds() {
        Optional<PlayerTask> result = acquireTask(playerUuid);
        assertTrue(result.isPresent());
    }

    /**
     * 同一玩家第二次 tryAcquire 应失败（任务冲突）
     */
    @Test
    void tryAcquire_samePlayer_secondTime_fails() {
        acquireTask(playerUuid);
        Optional<PlayerTask> second = acquireTask(playerUuid);
        assertTrue(second.isEmpty(), "同一玩家重复获取任务应返回 empty");
    }

    /**
     * release 后应允许再次获取任务
     */
    @Test
    void release_allowsReacquire() {
        PlayerTask task = acquireTask(playerUuid).orElseThrow();
        registry.release(playerUuid, task.getToken());

        Optional<PlayerTask> second = acquireTask(playerUuid);
        assertTrue(second.isPresent(), "release 后应允许再次获取任务");
    }

    /**
     * release 时 token 不匹配，不应影响现有任务
     */
    @Test
    void release_wrongToken_doesNotRemove() {
        PlayerTask task = acquireTask(playerUuid).orElseThrow();
        // 用错误的 token 尝试释放
        registry.release(playerUuid, task.getToken() + 1);

        // 原任务应仍然存在
        assertTrue(registry.hasActiveTask(playerUuid));
        assertTrue(registry.isValid(task));
    }

    /**
     * invalidate 后 isValid 应返回 false
     */
    @Test
    void invalidate_makesTaskInvalid() {
        PlayerTask task = acquireTask(playerUuid).orElseThrow();
        assertTrue(registry.isValid(task));

        registry.invalidate(playerUuid);

        assertFalse(registry.isValid(task), "invalidate 后旧任务应失效");
    }

    /**
     * invalidate 后，重新获取任务（新 epoch）应仍然有效
     */
    @Test
    void afterInvalidate_newTaskIsValid() {
        PlayerTask oldTask = acquireTask(playerUuid).orElseThrow();
        registry.invalidate(playerUuid);
        // 先释放旧任务（release 不依赖 isValid）
        registry.release(playerUuid, oldTask.getToken());

        PlayerTask newTask = acquireTask(playerUuid).orElseThrow();
        assertTrue(registry.isValid(newTask), "新任务应有效");
        assertFalse(registry.isValid(oldTask), "旧任务仍应失效");
    }

    /**
     * disablePlugin 后所有任务应失效
     */
    @Test
    void disablePlugin_invalidatesAllTasks() {
        PlayerTask task1 = acquireTask(playerUuid).orElseThrow();
        UUID player2 = UUID.randomUUID();
        PlayerTask task2 = acquireTask(player2).orElseThrow();

        registry.disablePlugin();

        assertFalse(registry.isValid(task1), "插件禁用后 task1 应失效");
        assertFalse(registry.isValid(task2), "插件禁用后 task2 应失效");
        assertFalse(registry.hasActiveTask(playerUuid), "插件禁用后应清空任务表");
    }

    /**
     * 不同玩家任务互不干扰
     */
    @Test
    void differentPlayers_independentTasks() {
        UUID player2 = UUID.randomUUID();
        PlayerTask t1 = acquireTask(playerUuid).orElseThrow();
        PlayerTask t2 = acquireTask(player2).orElseThrow();

        registry.invalidate(playerUuid);

        assertFalse(registry.isValid(t1), "玩家1的任务应失效");
        assertTrue(registry.isValid(t2), "玩家2的任务不应受影响");
    }

    /** 辅助方法：以测试默认值获取任务 */
    private Optional<PlayerTask> acquireTask(UUID uuid) {
        return registry.tryAcquire(uuid, OperationType.DEPOSIT, config, worldUuid, 0, 64, 0);
    }
}
