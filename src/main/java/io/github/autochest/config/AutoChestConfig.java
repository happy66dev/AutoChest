package io.github.autochest.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * AutoChest 插件配置类，所有配置项在此集中读取和校验
 * 每次 reload 生成新实例，运行中任务持有旧快照不受影响
 */
public class AutoChestConfig {

    // ===== 扫描范围配置 =====

    /** 扫描半径 X 轴格数，默认 8，最小 1，最大 64 */
    private final int scanRadiusX;

    /** 扫描半径 Y 轴格数，默认 8，最小 1，最大 64 */
    private final int scanRadiusY;

    /** 扫描半径 Z 轴格数，默认 8，最小 1，最大 64 */
    private final int scanRadiusZ;

    /** 每 tick 最多扫描的方块数量，防止主线程单 tick 卡顿，默认 512 */
    private final int scanBlocksPerTick;

    /** 每 tick 扫描最多占用纳秒数，超过立即让出，默认 3ms */
    private final long scanNanosPerTick;

    // ===== 提交预算配置 =====

    /** 每 tick 最多提交的容器事务数，默认 16 */
    private final int submitContainersPerTick;

    /** 每 tick 提交阶段最多占用纳秒数，默认 3ms */
    private final long submitNanosPerTick;

    // ===== 冷却时间配置 =====

    /** 存入命令冷却时间（毫秒），默认 5000ms */
    private final long depositCooldownMs;

    /** 补货命令冷却时间（毫秒），默认 3000ms */
    private final long restockCooldownMs;

    // ===== 线程池配置 =====

    /** 异步规划线程池大小，默认 2 */
    private final int executorPoolSize;

    /** 异步规划有界队列容量，满时拒绝并提示服务器繁忙，默认 64 */
    private final int executorQueueSize;

    // ===== 消息配置 =====

    /** 消息前缀，支持颜色代码 */
    private final String msgPrefix;

    /** 扫描开始消息 */
    private final String msgScanStarted;

    /** 存入完成消息，含占位符 {items} {containers} {skipped} */
    private final String msgDepositDone;

    /** 补货完成消息，含占位符 {items} {containers} {skipped} */
    private final String msgRestockDone;

    /** 无匹配物品消息 */
    private final String msgNoMatch;

    /** 冷却中消息，含占位符 {seconds} */
    private final String msgCooldown;

    /** 任务冲突消息 */
    private final String msgTaskConflict;

    /** 服务器繁忙消息 */
    private final String msgServerBusy;

    /** Hook 不可用消息，含占位符 {hook} */
    private final String msgHookUnavailable;

    /** 操作取消消息 */
    private final String msgCancelled;

    /** 内部错误消息 */
    private final String msgInternalError;

    // ===== 音效配置 =====

    /** 扫描开始音效名称，空字符串表示不播放 */
    private final String soundScanStarted;

    /** 操作成功音效名称，空字符串表示不播放 */
    private final String soundSuccess;

    /** 无匹配音效名称，空字符串表示不播放 */
    private final String soundNoMatch;

    /** 拒绝/冷却音效名称，空字符串表示不播放 */
    private final String soundDenied;

    /** 内部错误音效名称，空字符串表示不播放 */
    private final String soundError;

    /**
     * 从 FileConfiguration 读取所有配置项，非法值自动使用安全默认值
     *
     * @param cfg Bukkit 读取到的配置对象
     */
    public AutoChestConfig(FileConfiguration cfg) {
        // 喵~防御：扫描半径范围限制 1..64，防止负数或极大值造成主线程卡顿
        this.scanRadiusX = clampInt(cfg.getInt("scan.radius-x", 8), 1, 64);
        this.scanRadiusY = clampInt(cfg.getInt("scan.radius-y", 8), 1, 64);
        this.scanRadiusZ = clampInt(cfg.getInt("scan.radius-z", 8), 1, 64);

        // 喵~防御：每 tick 扫描方块数最小 16，防止永远无法推进
        this.scanBlocksPerTick = clampInt(cfg.getInt("scan.blocks-per-tick", 512), 16, 8192);

        // 喵~防御：纳秒预算最小 500000(0.5ms)，最大 10ms
        this.scanNanosPerTick = clampLong(cfg.getLong("scan.nanos-per-tick", 3_000_000L), 500_000L, 10_000_000L);

        // 喵~防御：提交容器数最小 1
        this.submitContainersPerTick = clampInt(cfg.getInt("submit.containers-per-tick", 16), 1, 512);
        this.submitNanosPerTick = clampLong(cfg.getLong("submit.nanos-per-tick", 3_000_000L), 500_000L, 10_000_000L);

        // 喵~防御：冷却时间最小 500ms，防止设置为 0 造成无冷却刷屏
        this.depositCooldownMs = clampLong(cfg.getLong("cooldown.deposit-ms", 5000L), 500L, 300_000L);
        this.restockCooldownMs = clampLong(cfg.getLong("cooldown.restock-ms", 3000L), 500L, 300_000L);

        // 喵~防御：线程池大小最小 1，队列大小最小 4
        this.executorPoolSize = clampInt(cfg.getInt("executor.pool-size", 2), 1, 16);
        this.executorQueueSize = clampInt(cfg.getInt("executor.queue-size", 64), 4, 1024);

        // 消息配置读取，空字符串使用占位符替代
        this.msgPrefix = defaultIfBlank(cfg.getString("messages.prefix"), "&7[&bAutoChest&7] ");
        this.msgScanStarted = defaultIfBlank(cfg.getString("messages.scan-started"), "&7正在扫描附近容器...");
        this.msgDepositDone = defaultIfBlank(cfg.getString("messages.deposit-done"),
                "&a已将 &e{items} &a个物品存入 &e{containers} &a个容器（跳过 &e{skipped} &a个）");
        this.msgRestockDone = defaultIfBlank(cfg.getString("messages.restock-done"),
                "&a已从 &e{containers} &a个容器补充 &e{items} &a个物品（跳过 &e{skipped} &a个）");
        this.msgNoMatch = defaultIfBlank(cfg.getString("messages.no-match"), "&7附近没有可匹配的容器");
        this.msgCooldown = defaultIfBlank(cfg.getString("messages.cooldown"), "&c操作冷却中，还需等待 &e{seconds} &c秒");
        this.msgTaskConflict = defaultIfBlank(cfg.getString("messages.task-conflict"), "&c当前有任务正在执行，请稍候");
        this.msgServerBusy = defaultIfBlank(cfg.getString("messages.server-busy"), "&c服务器繁忙，请稍候再试");
        this.msgHookUnavailable = defaultIfBlank(cfg.getString("messages.hook-unavailable"),
                "&c保护插件 &e{hook} &c检查不可用，操作已拒绝");
        this.msgCancelled = defaultIfBlank(cfg.getString("messages.cancelled"), "&7操作已取消");
        this.msgInternalError = defaultIfBlank(cfg.getString("messages.internal-error"), "&c操作失败，请联系管理员");

        // 音效配置读取，只存名称字符串，实际播放时再通过 Bukkit 解析枚举
        // 这样 AutoChestConfig 本身不依赖 Bukkit Sound 枚举，测试无需初始化 MockBukkit
        this.soundScanStarted = cfg.getString("sounds.scan-started", "BLOCK_CHEST_OPEN");
        this.soundSuccess = cfg.getString("sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP");
        this.soundNoMatch = cfg.getString("sounds.no-match", "BLOCK_NOTE_BLOCK_BASS");
        this.soundDenied = cfg.getString("sounds.denied", "ENTITY_VILLAGER_NO");
        this.soundError = cfg.getString("sounds.error", "ENTITY_ITEM_BREAK");
    }

    // ===== 访问器 =====

    public int getScanRadiusX() { return scanRadiusX; }
    public int getScanRadiusY() { return scanRadiusY; }
    public int getScanRadiusZ() { return scanRadiusZ; }
    public int getScanBlocksPerTick() { return scanBlocksPerTick; }
    public long getScanNanosPerTick() { return scanNanosPerTick; }
    public int getSubmitContainersPerTick() { return submitContainersPerTick; }
    public long getSubmitNanosPerTick() { return submitNanosPerTick; }
    public long getDepositCooldownMs() { return depositCooldownMs; }
    public long getRestockCooldownMs() { return restockCooldownMs; }
    public int getExecutorPoolSize() { return executorPoolSize; }
    public int getExecutorQueueSize() { return executorQueueSize; }
    public String getMsgPrefix() { return msgPrefix; }
    public String getMsgScanStarted() { return msgScanStarted; }
    public String getMsgDepositDone() { return msgDepositDone; }
    public String getMsgRestockDone() { return msgRestockDone; }
    public String getMsgNoMatch() { return msgNoMatch; }
    public String getMsgCooldown() { return msgCooldown; }
    public String getMsgTaskConflict() { return msgTaskConflict; }
    public String getMsgServerBusy() { return msgServerBusy; }
    public String getMsgHookUnavailable() { return msgHookUnavailable; }
    public String getMsgCancelled() { return msgCancelled; }
    public String getMsgInternalError() { return msgInternalError; }
    public String getSoundScanStarted() { return soundScanStarted; }
    public String getSoundSuccess() { return soundSuccess; }
    public String getSoundNoMatch() { return soundNoMatch; }
    public String getSoundDenied() { return soundDenied; }
    public String getSoundError() { return soundError; }

    // ===== 私有工具方法 =====

    /**
     * 将 int 值限制在 [min, max] 范围内
     *
     * @param value 原始值
     * @param min   最小值（含）
     * @param max   最大值（含）
     * @return 限制后的值
     */
    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 将 long 值限制在 [min, max] 范围内
     *
     * @param value 原始值
     * @param min   最小值（含）
     * @param max   最大值（含）
     * @return 限制后的值
     */
    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 空字符串或 null 时返回默认值
     *
     * @param value        配置读取的字符串
     * @param defaultValue 安全默认值
     * @return 非空字符串
     */
    private static String defaultIfBlank(String value, String defaultValue) {
        // 喵~防御：null 或全空白都用默认值代替
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
