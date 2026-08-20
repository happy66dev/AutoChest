package io.github.autochest.scan;

import io.github.autochest.container.BlockPos;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.scan.InventorySnapshotFactory.ContainerDto;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划器确定性规模测试。
 * 这些测试验证 100、1000 和 5000 条快照输入的结果数量与物品守恒，不把不稳定耗时当作正确性条件。
 */
class DeterministicPerformanceTest {

    /**
     * 不同规模快照都必须保留一个有效候选，并排除空容器。
     */
    @Test
    void planner_preservesDeterministicCandidateCountsAtRequiredScales() {
        // 使用固定规模集合覆盖规范要求的三个数据量边界。
        for (int containerCount : List.of(100, 1_000, 5_000)) {
            // 创建本轮规模对应的纯数据容器快照。
            List<ContainerDto> snapshots = createSnapshots(containerCount);
            // 执行纯数据规划，不能访问 Bukkit 世界或数据库。
            CandidatePlanner.PlanResult plan = new CandidatePlanner().plan(snapshots);
            // 规划结果必须保留全部容器的稳定顺序。
            assertEquals(containerCount, plan.sortedContainers.size());
            // 目标物品必须只在第一个非空容器中成为候选。
            String itemKey = "fixed-item";
            ContainerIdentity firstIdentity = snapshots.get(0).identity;
            assertTrue(plan.isSnapshotCandidate(itemKey, firstIdentity));
            // 空容器不能被误判为目标物品候选。
            assertFalse(plan.isSnapshotCandidate(itemKey, snapshots.get(containerCount - 1).identity));
        }
    }

    /**
     * 构造固定世界和固定物品键的快照，确保测试重复运行得到相同结构。
     */
    private List<ContainerDto> createSnapshots(int containerCount) {
        // 预分配列表容量，避免规模测试期间反复扩容。
        List<ContainerDto> snapshots = new ArrayList<>(containerCount);
        // 使用固定 UUID，避免随机输入影响结果复现。
        UUID worldUuid = UUID.nameUUIDFromBytes("deterministic-world".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // 逐个创建距离递增的容器身份。
        for (int containerIndex = 0; containerIndex < containerCount; containerIndex++) {
            // 创建当前容器的唯一坐标。
            ContainerIdentity identity = new ContainerIdentity(
                    new BlockPos(worldUuid, containerIndex, 64, 0),
                    ContainerIdentity.ContainerType.CHEST,
                    (long) containerIndex);
            // 仅首个容器放置固定物品，其余容器保持空快照。
            List<String> itemKeys = containerIndex == 0 ? List.of("fixed-item") : List.of();
            // 保存纯字符串快照，规划阶段无需读取 Bukkit 物品对象。
            snapshots.add(new ContainerDto(identity, itemKeys));
        }
        // 返回固定顺序快照集合。
        return snapshots;
    }
}
