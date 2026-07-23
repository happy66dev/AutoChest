package io.github.autochest.task;

import io.github.autochest.config.AutoChestConfig;

import java.util.UUID;

/**
 * 不可变的玩家任务记录，保存任务创建时的所有关键上下文
 * 不持有 Player、World、Block 等 Bukkit 实时对象，防止异步访问违规
 */
public final class PlayerTask {

    /** 执行此任务的玩家 UUID */
    private final UUID playerUuid;

    /** 任务唯一令牌（随机生成），用于迟到回调验证 */
    private final long token;

    /** 玩家 session epoch 快照值，退出/换世界/死亡时递增，旧任务因此失效 */
    private final int sessionEpoch;

    /** 插件 generation 快照值，插件禁用时递增，所有迟到回调因此失效 */
    private final int pluginGeneration;

    /** 任务类型：存入或补货 */
    private final OperationType type;

    /** 创建此任务时的配置快照，reload 不影响运行中任务 */
    private final AutoChestConfig configSnapshot;

    /** 任务开始时玩家所在世界的 UUID，用于检测换世界 */
    private final UUID worldUuid;

    /** 任务开始时玩家的方块坐标 X（扫描中心固定不变） */
    private final int centerX;

    /** 任务开始时玩家的方块坐标 Y */
    private final int centerY;

    /** 任务开始时玩家的方块坐标 Z */
    private final int centerZ;

    /**
     * 创建玩家任务
     *
     * @param playerUuid      玩家 UUID
     * @param token           唯一令牌
     * @param sessionEpoch    当前 session epoch
     * @param pluginGeneration 当前插件 generation
     * @param type            操作类型
     * @param configSnapshot  配置快照
     * @param worldUuid       世界 UUID
     * @param centerX         扫描中心 X
     * @param centerY         扫描中心 Y
     * @param centerZ         扫描中心 Z
     */
    public PlayerTask(
            UUID playerUuid,
            long token,
            int sessionEpoch,
            int pluginGeneration,
            OperationType type,
            AutoChestConfig configSnapshot,
            UUID worldUuid,
            int centerX,
            int centerY,
            int centerZ
    ) {
        this.playerUuid = playerUuid;
        this.token = token;
        this.sessionEpoch = sessionEpoch;
        this.pluginGeneration = pluginGeneration;
        this.type = type;
        this.configSnapshot = configSnapshot;
        this.worldUuid = worldUuid;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public long getToken() { return token; }
    public int getSessionEpoch() { return sessionEpoch; }
    public int getPluginGeneration() { return pluginGeneration; }
    public OperationType getType() { return type; }
    public AutoChestConfig getConfigSnapshot() { return configSnapshot; }
    public UUID getWorldUuid() { return worldUuid; }
    public int getCenterX() { return centerX; }
    public int getCenterY() { return centerY; }
    public int getCenterZ() { return centerZ; }
}
