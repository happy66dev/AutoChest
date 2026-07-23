package io.github.autochest.task;

import io.github.autochest.config.AutoChestConfig;
import io.github.autochest.config.CooldownService;

import java.util.UUID;

/**
 * 操作类型枚举，区分存入和补货两种任务
 */
public enum OperationType {
    /** 存入附近箱子：将主背包 9..35 存入附近已有同类物品的容器 */
    DEPOSIT,
    /** 从附近箱子补货：将 0..35 已有堆叠补满至最大堆叠数 */
    RESTOCK;

    /**
     * 转换为对应的 CooldownService.OperationType
     *
     * @return 对应的冷却操作类型
     */
    public CooldownService.OperationType toCooldownType() {
        return this == DEPOSIT
                ? CooldownService.OperationType.DEPOSIT
                : CooldownService.OperationType.RESTOCK;
    }
}
