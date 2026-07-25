package io.github.autochest.preference;

import io.github.autochest.container.BlockPos;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.scan.ContainerOrdering;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * 容器优先模式应让远处高优先级类型先于近处低优先级类型。
     */
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
