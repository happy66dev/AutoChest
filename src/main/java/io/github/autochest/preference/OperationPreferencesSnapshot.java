package io.github.autochest.preference;

import io.github.autochest.container.ContainerIdentity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 不可变的单操作容器偏好快照。
 * 只保存 Bukkit-free 数据，可安全随玩家任务进入异步规划阶段。
 */
public final class OperationPreferencesSnapshot {

    /** 插件当前支持的容器种类默认顺序。 */
    private static final List<ContainerIdentity.ContainerType> DEFAULT_PRIORITY = List.of(
            ContainerIdentity.ContainerType.CHEST,
            ContainerIdentity.ContainerType.TRAPPED_CHEST,
            ContainerIdentity.ContainerType.BARREL,
            ContainerIdentity.ContainerType.SHULKER_BOX,
            ContainerIdentity.ContainerType.ENDER_CHEST
    );

    /** 本操作使用的稳定容器排序模式。 */
    private final ContainerOrderMode orderMode;

    /** 本操作不允许参与扫描和排序的容器种类。 */
    private final Set<ContainerIdentity.ContainerType> blacklistedContainerTypes;

    /** 本操作的完整容器种类优先级列表。 */
    private final List<ContainerIdentity.ContainerType> containerTypePriority;

    /**
     * 创建并规范化操作偏好快照。
     *
     * @param orderMode 容器排序模式，可为空。
     * @param blacklistedContainerTypes 黑名单种类，可为空。
     * @param containerTypePriority 玩家配置优先级，可为空。
     */
    public OperationPreferencesSnapshot(ContainerOrderMode orderMode,
                                        Set<ContainerIdentity.ContainerType> blacklistedContainerTypes,
                                        List<ContainerIdentity.ContainerType> containerTypePriority) {
        // 空模式使用距离优先，兼容缺失或损坏的 JSON 字段。
        this.orderMode = orderMode == null ? ContainerOrderMode.DISTANCE : orderMode;
        // 创建独立枚举集合，防止调用方后续修改黑名单影响任务快照。
        EnumSet<ContainerIdentity.ContainerType> normalizedBlacklist = EnumSet.noneOf(ContainerIdentity.ContainerType.class);
        // 仅复制非空的合法黑名单种类。
        if (blacklistedContainerTypes != null) {
            normalizedBlacklist.addAll(blacklistedContainerTypes);
        }
        // 冻结黑名单集合，禁止运行中任务被外部修改。
        this.blacklistedContainerTypes = Set.copyOf(normalizedBlacklist);
        // 规范优先级，去重并补齐所有当前支持的容器种类。
        this.containerTypePriority = List.copyOf(normalizePriority(containerTypePriority));
    }

    /**
     * 创建默认距离优先快照。
     *
     * @return 默认操作偏好快照。
     */
    public static OperationPreferencesSnapshot defaults() {
        // 默认不排除任何容器并保持旧版距离排序语义。
        return new OperationPreferencesSnapshot(ContainerOrderMode.DISTANCE, Set.of(), DEFAULT_PRIORITY);
    }

    /**
     * 规范玩家给出的优先级列表。
     *
     * @param configuredPriority 玩家配置的列表，可为空。
     * @return 去重、过滤空值并补齐默认种类后的完整列表。
     */
    private static List<ContainerIdentity.ContainerType> normalizePriority(
            List<ContainerIdentity.ContainerType> configuredPriority) {
        // 创建结果列表以保持玩家有效条目的原有相对顺序。
        List<ContainerIdentity.ContainerType> normalizedPriority = new ArrayList<>();
        // 创建集合以消除重复容器种类。
        EnumSet<ContainerIdentity.ContainerType> seenTypes = EnumSet.noneOf(ContainerIdentity.ContainerType.class);
        // 有配置时依次保留首次出现的有效种类。
        if (configuredPriority != null) {
            for (ContainerIdentity.ContainerType containerType : configuredPriority) {
                if (containerType != null && seenTypes.add(containerType)) {
                    normalizedPriority.add(containerType);
                }
            }
        }
        // 将遗漏的当前支持种类按默认顺序追加到列表末尾。
        for (ContainerIdentity.ContainerType defaultType : DEFAULT_PRIORITY) {
            if (seenTypes.add(defaultType)) {
                normalizedPriority.add(defaultType);
            }
        }
        // 返回完整的稳定优先级列表。
        return normalizedPriority;
    }

    /**
     * 判断容器种类是否允许参与本操作。
     *
     * @param containerType 待检查的容器种类。
     * @return true 表示容器类型未被黑名单排除。
     */
    public boolean allows(ContainerIdentity.ContainerType containerType) {
        // 空类型无法安全归类，保守排除。
        return containerType != null && !blacklistedContainerTypes.contains(containerType);
    }

    /**
     * 获取容器种类在优先级列表中的稳定排名。
     *
     * @param containerType 待查询的容器种类。
     * @return 数字越小表示优先级越高。
     */
    public int priorityRank(ContainerIdentity.ContainerType containerType) {
        // 返回列表下标；未知类型固定排在所有已知类型之后。
        int rank = containerTypePriority.indexOf(containerType);
        return rank < 0 ? containerTypePriority.size() : rank;
    }

    /**
     * 获取本操作使用的排序模式。
     *
     * @return 当前排序模式。
     */
    public ContainerOrderMode getOrderMode() {
        // 返回不可变排序模式。
        return orderMode;
    }

    /**
     * 获取不可变黑名单集合。
     *
     * @return 黑名单容器种类集合。
     */
    public Set<ContainerIdentity.ContainerType> getBlacklistedContainerTypes() {
        // 返回已冻结的黑名单集合。
        return blacklistedContainerTypes;
    }

    /**
     * 获取不可变容器种类优先级列表。
     *
     * @return 完整容器种类优先级列表。
     */
    public List<ContainerIdentity.ContainerType> getContainerTypePriority() {
        // 返回已冻结的优先级列表。
        return containerTypePriority;
    }
}
