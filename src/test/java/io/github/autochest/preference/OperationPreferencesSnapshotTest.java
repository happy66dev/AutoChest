package io.github.autochest.preference;

import io.github.autochest.container.BlockPos;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.scan.ContainerOrdering;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家容器偏好快照与排序测试。
 */
class OperationPreferencesSnapshotTest {

    /**
     * 黑名单应覆盖优先级，且缺失种类会自动追加到优先级末尾。
     */
    @Test
    void snapshot_normalizesPriorityAndBlacklistOverridesPriority() {
        OperationPreferencesSnapshot snapshot = new OperationPreferencesSnapshot(
                ContainerOrderMode.CONTAINER_PRIORITY,
                Set.of(ContainerIdentity.ContainerType.BARREL),
                List.of(ContainerIdentity.ContainerType.ENDER_CHEST,
                        ContainerIdentity.ContainerType.ENDER_CHEST));

        assertFalse(snapshot.allows(ContainerIdentity.ContainerType.BARREL));
        assertTrue(snapshot.allows(ContainerIdentity.ContainerType.ENDER_CHEST));
        assertEquals(ContainerIdentity.ContainerType.ENDER_CHEST,
                snapshot.getContainerTypePriority().getFirst());
        assertEquals(5, snapshot.getContainerTypePriority().size());
    }

    /**
     * 锁定槽位必须只保留主背包范围并冻结调用方集合。
     */
    @Test
    void snapshot_normalizesAndFreezesLockedInventorySlots() {
        // 创建包含重复、null 和范围外槽位的可变集合。
        Set<Integer> configuredSlots = new HashSet<>();
        // 添加合法主背包首槽位。
        configuredSlots.add(9);
        // 添加合法主背包末槽位。
        configuredSlots.add(35);
        // 添加快捷栏范围外槽位。
        configuredSlots.add(8);
        // 添加主背包范围外槽位。
        configuredSlots.add(36);
        // 添加空槽位值。
        configuredSlots.add(null);
        // 使用完整构造器创建快照。
        OperationPreferencesSnapshot snapshot = new OperationPreferencesSnapshot(
                ContainerOrderMode.DISTANCE, Set.of(), List.of(), configuredSlots);
        // 修改原集合不能影响已经创建的任务快照。
        configuredSlots.add(10);

        // 验证仅合法主背包槽位被保留。
        assertEquals(Set.of(9, 35), snapshot.getLockedInventorySlots());
        // 验证首槽位可被查询为锁定。
        assertTrue(snapshot.isLockedInventorySlot(9));
        // 验证末槽位可被查询为锁定。
        assertTrue(snapshot.isLockedInventorySlot(35));
        // 验证调用方后续新增槽位不会污染快照。
        assertFalse(snapshot.isLockedInventorySlot(10));
        // 验证快捷栏不属于可锁定范围。
        assertFalse(snapshot.isLockedInventorySlot(8));
        // 验证返回集合不可修改。
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getLockedInventorySlots().add(10));
    }


    @Test
    void ordering_containerPriorityUsesTypeThenDistanceThenKey() {
        UUID worldUuid = UUID.randomUUID();
        ContainerIdentity nearBarrel = new ContainerIdentity(
                new BlockPos(worldUuid, 1, 64, 0), ContainerIdentity.ContainerType.BARREL, 1L);
        ContainerIdentity farEnderChest = new ContainerIdentity(
                new BlockPos(worldUuid, 9, 64, 0), ContainerIdentity.ContainerType.ENDER_CHEST, 81L);
        ContainerIdentity nearerEnderChest = new ContainerIdentity(
                new BlockPos(worldUuid, 8, 64, 0), ContainerIdentity.ContainerType.ENDER_CHEST, 64L);
        OperationPreferencesSnapshot snapshot = new OperationPreferencesSnapshot(
                ContainerOrderMode.CONTAINER_PRIORITY,
                Set.of(),
                List.of(ContainerIdentity.ContainerType.ENDER_CHEST,
                        ContainerIdentity.ContainerType.BARREL));

        List<ContainerIdentity> ordered = ContainerOrdering.order(
                List.of(nearBarrel, farEnderChest, nearerEnderChest), snapshot);

        assertEquals(List.of(nearerEnderChest, farEnderChest, nearBarrel), ordered);
    }

    /**
     * 距离模式必须保持既有距离优先语义。
     */
    @Test
    void ordering_distanceModeKeepsDistancePriority() {
        UUID worldUuid = UUID.randomUUID();
        ContainerIdentity nearBarrel = new ContainerIdentity(
                new BlockPos(worldUuid, 1, 64, 0), ContainerIdentity.ContainerType.BARREL, 1L);
        ContainerIdentity farEnderChest = new ContainerIdentity(
                new BlockPos(worldUuid, 9, 64, 0), ContainerIdentity.ContainerType.ENDER_CHEST, 81L);

        List<ContainerIdentity> ordered = ContainerOrdering.order(
                List.of(farEnderChest, nearBarrel), OperationPreferencesSnapshot.defaults());

        assertEquals(List.of(nearBarrel, farEnderChest), ordered);
    }
}
