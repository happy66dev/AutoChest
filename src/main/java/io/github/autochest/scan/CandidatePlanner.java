package io.github.autochest.scan;

import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.scan.InventorySnapshotFactory.ContainerDto;
import io.github.autochest.scan.InventorySnapshotFactory.PlayerInventoryDto;
import io.github.autochest.scan.InventorySnapshotFactory.SlotDto;

import java.util.*;

/**
 * 异步候选规划器，在插件私有线程池中运行
 * 只处理 Bukkit-free DTO，不访问任何实时 Bukkit 对象
 * 输出候选索引供主线程逐容器实时重算使用，不作为最终移动量依据
 */
public class CandidatePlanner {

    /**
     * 规划结果，包含按距离排序的容器列表和物品候选索引
     * 所有字段均为 Bukkit-free，可安全在任意线程传递
     */
    public static final class PlanResult {
        /** 按距离和坐标键稳定排序的容器列表 */
        public final List<ContainerDto> sortedContainers;
        /** 物品键（字节数组） 到 候选容器列表 的映射，用于快速定位可能的目标容器 */
        public final Map<String, List<ContainerDto>> itemKeyToCandidates;

        PlanResult(List<ContainerDto> sortedContainers, Map<String, List<ContainerDto>> itemKeyToCandidates) {
            this.sortedContainers = Collections.unmodifiableList(sortedContainers);
            this.itemKeyToCandidates = Collections.unmodifiableMap(itemKeyToCandidates);
        }
    }

    /**
     * 在异步线程中执行规划
     * 完成排序并建立物品候选索引
     *
     * @param playerDto  玩家库存快照（Bukkit-free）
     * @param containers 扫描到的容器快照列表（已由扫描阶段按距离排序）
     * @return 规划结果
     */
    public PlanResult plan(PlayerInventoryDto playerDto, List<ContainerDto> containers) {
        // 容器列表已由 ScanTask 按距离排序，此处直接复制保持顺序
        List<ContainerDto> sorted = new ArrayList<>(containers);

        // 建立物品键 → 候选容器映射
        // 对每个容器的每个非空槽位，将物品键归入对应候选列表
        Map<String, List<ContainerDto>> index = new LinkedHashMap<>();

        for (ContainerDto container : sorted) {
            for (SlotDto slot : container.slots) {
                if (!slot.isEmpty && slot.itemKey.length > 0) {
                    // 使用字节数组转 Base64 或哈希作为 Map 键
                    // 主线程最终仍用 isSimilar() 重验，此处只需粗粒度索引
                    String key = toIndexKey(slot.itemKey);
                    index.computeIfAbsent(key, k -> new ArrayList<>()).add(container);
                }
            }
        }

        // 去重：同一容器可能因多个槽位相同物品而被重复添加，保留首次出现
        for (Map.Entry<String, List<ContainerDto>> entry : index.entrySet()) {
            List<ContainerDto> deduped = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ContainerDto container : entry.getValue()) {
                String id = container.identity.canonicalKey();
                if (seen.add(id)) {
                    deduped.add(container);
                }
            }
            entry.setValue(deduped);
        }

        return new PlanResult(sorted, index);
    }

    /**
     * 将物品序列化字节转为索引键字符串
     * 仅用于异步候选索引，不用于最终物品匹配
     *
     * @param bytes 物品序列化字节
     * @return 索引键字符串
     */
    private static String toIndexKey(byte[] bytes) {
        // 使用简单哈希作为索引键，碰撞时主线程 isSimilar() 会过滤
        return Integer.toHexString(Arrays.hashCode(bytes));
    }
}
