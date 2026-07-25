package io.github.autochest.task;

import io.github.autochest.config.AutoChestConfig;
import io.github.autochest.preference.OperationPreferencesSnapshot;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 玩家任务注册表，保证每名玩家同时最多运行一个任务
 * 通过 session epoch 和 plugin generation 保证迟到异步回调不操作已废弃的任务
 * 所有方法均线程安全
 */
public class PlayerTaskRegistry {

    /** 每名玩家的 session epoch，退出/换世界/死亡时递增 */
    private final ConcurrentHashMap<UUID, AtomicInteger> sessionEpochs = new ConcurrentHashMap<>();

    /** 当前运行中的任务，UUID → PlayerTask */
    private final ConcurrentHashMap<UUID, PlayerTask> activeTasks = new ConcurrentHashMap<>();

    /** 插件 generation，插件禁用时递增至 Integer.MAX_VALUE */
    private volatile int pluginGeneration = 1;

    /**
     * 使用默认玩家容器偏好创建任务，兼容未显式传入偏好快照的调用方。
     *
     * @param playerUuid 玩家 UUID
     * @param type 操作类型
     * @param configSnapshot 配置快照
     * @param worldUuid 世界 UUID
     * @param centerX 扫描中心 X
     * @param centerY 扫描中心 Y
     * @param centerZ 扫描中心 Z
     * @return 成功则返回新任务，失败返回空
     */
    public Optional<PlayerTask> tryAcquire(UUID playerUuid, OperationType type,
                                           AutoChestConfig configSnapshot, UUID worldUuid,
                                           int centerX, int centerY, int centerZ) {
        // 使用默认距离优先偏好，保持旧调用方的行为不变。
        return tryAcquire(playerUuid, type, configSnapshot, OperationPreferencesSnapshot.defaults(),
                worldUuid, centerX, centerY, centerZ);
    }
    /**
     * 尝试为指定玩家创建并注册新任务
     * 若该玩家已有运行中任务，返回 Optional.empty()
     *
     * @param playerUuid     玩家 UUID
     * @param type           操作类型
     * @param configSnapshot 配置快照
     * @param preferencesSnapshot 玩家容器偏好快照
     * @param worldUuid      世界 UUID
     * @param centerX        扫描中心 X
     * @param centerY        扫描中心 Y
     * @param centerZ        扫描中心 Z
     * @return 成功则返回包含新任务的 Optional，失败返回 Optional.empty()
     */
    public Optional<PlayerTask> tryAcquire(
            UUID playerUuid,
            OperationType type,
            AutoChestConfig configSnapshot,
            OperationPreferencesSnapshot preferencesSnapshot,
            UUID worldUuid,
            int centerX,
            int centerY,
            int centerZ
    ) {
        // 获取当前 session epoch，玩家若从未有过则初始化为 0
        int epoch = sessionEpochs.computeIfAbsent(playerUuid, k -> new AtomicInteger(0)).get();
        long token = ThreadLocalRandom.current().nextLong();

        PlayerTask newTask = new PlayerTask(
                playerUuid, token, epoch, pluginGeneration,
                type, configSnapshot, preferencesSnapshot, worldUuid, centerX, centerY, centerZ
        );

        // CAS 插入：若已存在任务则插入失败，返回 empty
        PlayerTask existing = activeTasks.putIfAbsent(playerUuid, newTask);
        if (existing != null) {
            // 喵~防御：已有运行中任务，拒绝新任务
            return Optional.empty();
        }
        return Optional.of(newTask);
    }

    /**
     * 释放指定玩家的任务，仅当 token 匹配时才移除
     * 防止新任务被旧任务的延迟回调误释放
     *
     * @param playerUuid 玩家 UUID
     * @param token      任务令牌
     */
    public void release(UUID playerUuid, long token) {
        activeTasks.compute(playerUuid, (k, existing) -> {
            if (existing != null && existing.getToken() == token) {
                // token 匹配才移除
                return null;
            }
            // token 不匹配，保留现有任务不动
            return existing;
        });
    }

    /**
     * 使指定玩家的当前任务立即失效（不移除，由任务自身在下次检查时释放）
     * 在玩家退出、换世界或死亡事件中调用
     *
     * @param playerUuid 玩家 UUID
     */
    public void invalidate(UUID playerUuid) {
        // 递增 session epoch，使当前所有以旧 epoch 创建的任务 isValid 返回 false
        sessionEpochs.computeIfAbsent(playerUuid, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * 检查任务是否仍然有效
     * 任务必须满足：token 匹配、session epoch 一致、plugin generation 一致
     *
     * @param task 要验证的任务
     * @return true 表示有效，false 表示已失效
     */
    public boolean isValid(PlayerTask task) {
        // 检查 plugin generation，插件禁用后所有任务失效
        if (task.getPluginGeneration() != pluginGeneration) {
            return false;
        }
        // 检查 session epoch，玩家退出/换世界/死亡后失效
        AtomicInteger epochAtomic = sessionEpochs.get(task.getPlayerUuid());
        if (epochAtomic == null || epochAtomic.get() != task.getSessionEpoch()) {
            return false;
        }
        // 检查 token 是否仍为当前活跃任务
        PlayerTask active = activeTasks.get(task.getPlayerUuid());
        return active != null && active.getToken() == task.getToken();
    }

    /**
     * 查询指定玩家是否有运行中任务
     *
     * @param playerUuid 玩家 UUID
     * @return true 表示有任务
     */
    public boolean hasActiveTask(UUID playerUuid) {
        return activeTasks.containsKey(playerUuid);
    }

    /**
     * 插件禁用时调用：递增 generation 使所有迟到回调失效，清空任务表
     */
    public void disablePlugin() {
        // 使用极大值确保所有现有任务的 generation 校验均失败
        pluginGeneration = Integer.MAX_VALUE;
        activeTasks.clear();
    }

    /**
     * 获取当前插件 generation，供创建任务时快照
     *
     * @return 当前 generation 值
     */
    public int getPluginGeneration() {
        return pluginGeneration;
    }

    /**
     * 获取指定玩家的当前 session epoch，供创建任务时快照
     *
     * @param playerUuid 玩家 UUID
     * @return 当前 epoch 值
     */
    public int getSessionEpoch(UUID playerUuid) {
        return sessionEpochs.computeIfAbsent(playerUuid, k -> new AtomicInteger(0)).get();
    }
}
