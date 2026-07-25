package io.github.autochest.preference;

/**
 * 玩家容器排序模式。
 */
public enum ContainerOrderMode {
    /** 按容器距离和稳定身份键排序。 */
    DISTANCE,
    /** 先按玩家容器种类优先级，再按距离和稳定身份键排序。 */
    CONTAINER_PRIORITY
}
