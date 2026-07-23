package io.github.autochest.config;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冷却时间服务，分别管理 deposit 和 restock 两种操作的冷却
 * 使用单调时间源（System.nanoTime）避免系统时间调整影响结果
 * 线程安全，可从任意线程查询
 */
public class CooldownService {

    /** 操作类型枚举 */
    public enum OperationType {
        /** 存入附近箱子操作 */
        DEPOSIT,
        /** 从附近箱子补货操作 */
        RESTOCK
    }

    /** 存储每个玩家 deposit 操作的最后触发时间（纳秒），UUID → 时间戳 */
    private final ConcurrentHashMap<UUID, Long> depositTimestamps = new ConcurrentHashMap<>();

    /** 存储每个玩家 restock 操作的最后触发时间（纳秒），UUID → 时间戳 */
    private final ConcurrentHashMap<UUID, Long> restockTimestamps = new ConcurrentHashMap<>();

    /** 当前使用的配置快照，含冷却时长 */
    private volatile AutoChestConfig cfg;

    /**
     * 创建冷却服务
     *
     * @param cfg 初始配置快照
     */
    public CooldownService(AutoChestConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * 更新配置快照（reload 后调用），不影响已记录的时间戳
     *
     * @param newCfg 新配置快照
     */
    public void updateConfig(AutoChestConfig newCfg) {
        this.cfg = newCfg;
    }

    /**
     * 检查指定玩家的指定操作是否正在冷却中
     *
     * @param uuid 玩家 UUID
     * @param type 操作类型
     * @return true 表示仍在冷却，false 表示可以执行
     */
    public boolean isOnCooldown(UUID uuid, OperationType type) {
        Long lastTime = getTimestamps(type).get(uuid);
        if (lastTime == null) {
            // 从未执行过，不在冷却中
            return false;
        }
        // 计算已经过去的纳秒数，转换为毫秒后与冷却时长比较
        long elapsedMs = (System.nanoTime() - lastTime) / 1_000_000L;
        return elapsedMs < getCooldownMs(type, cfg);
    }

    /**
     * 获取指定玩家指定操作的剩余冷却毫秒数
     * 若未在冷却中则返回 0
     *
     * @param uuid 玩家 UUID
     * @param type 操作类型
     * @return 剩余冷却毫秒数，0 表示不在冷却
     */
    public long getRemainingMs(UUID uuid, OperationType type) {
        Long lastTime = getTimestamps(type).get(uuid);
        if (lastTime == null) {
            return 0L;
        }
        long elapsedMs = (System.nanoTime() - lastTime) / 1_000_000L;
        long remaining = getCooldownMs(type, cfg) - elapsedMs;
        return Math.max(0L, remaining);
    }

    /**
     * 记录指定玩家指定操作的当前时间戳，从此刻开始计算冷却
     * 命令接受即消费，不因后续任何原因退还
     *
     * @param uuid 玩家 UUID
     * @param type 操作类型
     */
    public void record(UUID uuid, OperationType type) {
        getTimestamps(type).put(uuid, System.nanoTime());
    }

    /**
     * 清空所有冷却记录，插件禁用时调用以释放内存
     */
    public void clear() {
        depositTimestamps.clear();
        restockTimestamps.clear();
    }

    /**
     * 根据操作类型返回对应的时间戳 Map
     *
     * @param type 操作类型
     * @return 对应的时间戳 ConcurrentHashMap
     */
    private ConcurrentHashMap<UUID, Long> getTimestamps(OperationType type) {
        return type == OperationType.DEPOSIT ? depositTimestamps : restockTimestamps;
    }

    /**
     * 根据操作类型和配置获取冷却时长（毫秒）
     *
     * @param type 操作类型
     * @param config 配置快照
     * @return 冷却时长毫秒数
     */
    private static long getCooldownMs(OperationType type, AutoChestConfig config) {
        return type == OperationType.DEPOSIT ? config.getDepositCooldownMs() : config.getRestockCooldownMs();
    }
}
