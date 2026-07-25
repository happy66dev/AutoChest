package io.github.autochest.scan;

import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.preference.ContainerOrderMode;
import io.github.autochest.preference.OperationPreferencesSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 容器排序工具。
 * 只处理 Bukkit-free 容器身份与任务偏好快照，可安全在规划边界复用。
 */
public final class ContainerOrdering {

    /** 私有构造器防止工具类被实例化。 */
    private ContainerOrdering() {
    }

    /**
     * 过滤黑名单并按任务快照规定的模式稳定排序。
     *
     * @param identities 扫描到的容器身份列表。
     * @param preferences 本次操作的不可变偏好快照。
     * @return 新建的已过滤、已排序容器列表。
     */
    public static List<ContainerIdentity> order(List<ContainerIdentity> identities,
                                                OperationPreferencesSnapshot preferences) {
        // 空候选或空快照无法执行可靠排序，返回安全空列表。
        if (identities == null || preferences == null) {
            return List.of();
        }
        // 创建独立结果列表，避免改写扫描器的发现顺序。
        List<ContainerIdentity> orderedIdentities = new ArrayList<>();
        // 复制所有未被黑名单排除的有效容器身份。
        for (ContainerIdentity identity : identities) {
            if (identity != null && preferences.allows(identity.getContainerType())) {
                orderedIdentities.add(identity);
            }
        }
        // 距离模式完全复用既有稳定排序规则。
        if (preferences.getOrderMode() == ContainerOrderMode.DISTANCE) {
            orderedIdentities.sort(ContainerIdentity.BY_DISTANCE_THEN_KEY);
            return orderedIdentities;
        }
        // 容器优先模式先比较种类排名，再沿用距离与规范键作为稳定兜底。
        Comparator<ContainerIdentity> comparator = Comparator
                .comparingInt((ContainerIdentity identity) -> preferences.priorityRank(identity.getContainerType()))
                .thenComparing(ContainerIdentity.BY_DISTANCE_THEN_KEY);
        // 应用稳定容器优先排序。
        orderedIdentities.sort(comparator);
        // 返回按本次任务偏好得到的容器列表。
        return orderedIdentities;
    }
}
