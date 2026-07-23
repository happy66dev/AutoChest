package io.github.autochest.scan;

import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.scan.InventorySnapshotFactory.ContainerDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 异步候选规划器，在插件私有线程池中运行
 * 只处理 Bukkit-free DTO，不访问任何实时 Bukkit 对象
 * 输出快照物品身份到候选容器身份的映射，供 deposit 提交阶段强制验证资格
 */
public class CandidatePlanner {

    /**
     * 规划结果，包含稳定容器顺序与快照物品候选资格
     */
    public static final class PlanResult {
        /** 按距离和坐标键稳定排序的容器身份 */
        public final List<ContainerIdentity> sortedContainers;
        /** 物品身份键到快照时含有该物品的容器规范键集合 */
        public final Map<String, Set<String>> itemKeyToCandidateKeys;

        PlanResult(List<ContainerIdentity> sortedContainers,
                   Map<String, Set<String>> itemKeyToCandidateKeys) {
            this.sortedContainers = Collections.unmodifiableList(sortedContainers);
            this.itemKeyToCandidateKeys = Collections.unmodifiableMap(itemKeyToCandidateKeys);
        }

        /**
         * 判断容器是否在任务快照时拥有该完整物品身份
         *
         * @param itemKey 物品身份键
         * @param identity 容器身份
         * @return true 表示可继续执行实时库存复验
         */
        public boolean isSnapshotCandidate(String itemKey, ContainerIdentity identity) {
            if (itemKey == null || identity == null) {
                return false;
            }
            Set<String> candidateKeys = itemKeyToCandidateKeys.get(itemKey);
            return candidateKeys != null && candidateKeys.contains(identity.canonicalKey());
        }
    }

    /**
     * 为 restock 创建不受快照物品资格限制的稳定容器计划
     *
     * @param identities 扫描到的距离排序容器身份
     * @return restock 规划结果
     */
    public PlanResult planForRestock(List<ContainerIdentity> identities) {
        if (identities == null || identities.isEmpty()) {
            return new PlanResult(List.of(), Map.of());
        }
        return new PlanResult(new ArrayList<>(identities), Map.of());
    }


    public PlanResult plan(List<ContainerDto> containers) {
        if (containers == null || containers.isEmpty()) {
            return new PlanResult(List.of(), Map.of());
        }

        List<ContainerIdentity> sortedContainers = new ArrayList<>();
        Map<String, Set<String>> mutableCandidateKeys = new LinkedHashMap<>();
        for (ContainerDto container : containers) {
            if (container == null || container.identity == null) {
                continue;
            }
            sortedContainers.add(container.identity);
            for (String itemKey : container.itemKeys) {
                mutableCandidateKeys.computeIfAbsent(itemKey, ignored -> new LinkedHashSet<>())
                        .add(container.identity.canonicalKey());
            }
        }

        Map<String, Set<String>> immutableCandidateKeys = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : mutableCandidateKeys.entrySet()) {
            immutableCandidateKeys.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        return new PlanResult(sortedContainers, immutableCandidateKeys);
    }
}
