package io.github.autochest.preference;

import io.github.autochest.container.BlockPos;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.scan.ContainerOrdering;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
     * 四态槽位权限必须覆盖完整 0..35 范围并保持快照不可变。
     */
    @Test
    void snapshot_supportsFourSlotModesAcrossFullInventory() {
        // 创建包含边界、非法槽位和四态权限的可变映射。
        HashMap<Integer, InventorySlotMode> configuredModes = new HashMap<>();
        // 配置快捷栏首槽为仅整理。
        configuredModes.put(0, InventorySlotMode.DEPOSIT_ONLY);
        // 配置快捷栏末槽为仅补货。
        configuredModes.put(8, InventorySlotMode.RESTOCK_ONLY);
        // 配置主背包首槽为完全禁止。
        configuredModes.put(9, InventorySlotMode.DISABLED);
        // 配置主背包末槽为显式默认状态。
        configuredModes.put(35, InventorySlotMode.ALLOW_BOTH);
        // 配置越界槽位，验证构造器会过滤。
        configuredModes.put(36, InventorySlotMode.DISABLED);
        // 创建不可变四态快照。
        OperationPreferencesSnapshot snapshot = new OperationPreferencesSnapshot(
                ContainerOrderMode.DISTANCE, Set.of(), List.of(), configuredModes);
        // 修改调用方映射不能污染已创建快照。
        configuredModes.put(1, InventorySlotMode.DISABLED);

        // 验证四态名称和整理权限矩阵。
        assertEquals(InventorySlotMode.DEPOSIT_ONLY, snapshot.getInventorySlotMode(0));
        assertTrue(snapshot.allowsDeposit(0));
        assertFalse(snapshot.allowsRestock(0));
        assertEquals(InventorySlotMode.RESTOCK_ONLY, snapshot.getInventorySlotMode(8));
        assertFalse(snapshot.allowsDeposit(8));
        assertTrue(snapshot.allowsRestock(8));
        assertEquals(InventorySlotMode.DISABLED, snapshot.getInventorySlotMode(9));
        assertFalse(snapshot.allowsDeposit(9));
        assertFalse(snapshot.allowsRestock(9));
        assertEquals(InventorySlotMode.ALLOW_BOTH, snapshot.getInventorySlotMode(35));
        assertTrue(snapshot.allowsDeposit(35));
        assertTrue(snapshot.allowsRestock(35));
        // 验证越界槽位没有权限且调用方新增状态不会进入快照。
        assertFalse(snapshot.allowsDeposit(36));
        assertFalse(snapshot.allowsRestock(36));
        assertEquals(InventorySlotMode.ALLOW_BOTH, snapshot.getInventorySlotMode(1));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getInventorySlotModes().put(1, InventorySlotMode.DISABLED));
    }

    /** 旧四参数锁定集合构造器必须迁移为仅补货。 */
    @Test
    void snapshot_migratesLegacyLockedSlotsToRestockOnly() {
        // 使用旧版锁定集合创建快照。
        OperationPreferencesSnapshot snapshot = new OperationPreferencesSnapshot(
                ContainerOrderMode.DISTANCE, Set.of(), List.of(), Set.of(9));
        // 旧锁定语义禁止整理但保留补货。
        assertEquals(InventorySlotMode.RESTOCK_ONLY, snapshot.getInventorySlotMode(9));
        assertFalse(snapshot.allowsDeposit(9));
        assertTrue(snapshot.allowsRestock(9));
    }

    /** 四态循环必须按 GUI 约定顺序回到默认状态。 */
    @Test
    void inventorySlotMode_cyclesInConfiguredOrder() {
        // 验证允许两种操作后切换为仅整理。
        assertEquals(InventorySlotMode.DEPOSIT_ONLY, InventorySlotMode.ALLOW_BOTH.next());
        // 验证仅整理后切换为仅补货。
        assertEquals(InventorySlotMode.RESTOCK_ONLY, InventorySlotMode.DEPOSIT_ONLY.next());
        // 验证仅补货后切换为完全禁止。
        assertEquals(InventorySlotMode.DISABLED, InventorySlotMode.RESTOCK_ONLY.next());
        // 验证完全禁止后循环回允许两种操作。
        assertEquals(InventorySlotMode.ALLOW_BOTH, InventorySlotMode.DISABLED.next());
    }

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
     * 旧版锁定槽位测试仍验证集合归一化，但范围现在覆盖完整背包。
     */
    @Test
    void snapshot_normalizesAndFreezesLegacyLockedInventorySlots() {
        // 创建包含重复、null 和范围外槽位的可变集合。
        Set<Integer> configuredSlots = new java.util.HashSet<>();
        // 添加合法快捷栏首槽位。
        configuredSlots.add(0);
        // 添加合法主背包末槽位。
        configuredSlots.add(35);
        // 添加负数范围外槽位。
        configuredSlots.add(-1);
        // 添加背包范围外槽位。
        configuredSlots.add(36);
        // 添加空槽位值。
        configuredSlots.add(null);
        // 使用旧版构造器创建快照。
        OperationPreferencesSnapshot snapshot = new OperationPreferencesSnapshot(
                ContainerOrderMode.DISTANCE, Set.of(), List.of(), configuredSlots);
        // 修改原集合不能影响已经创建的任务快照。
        configuredSlots.add(10);

        // 验证仅合法槽位被保留并迁移为仅补货。
        assertEquals(Set.of(0, 35), snapshot.getLockedInventorySlots());
        assertTrue(snapshot.isLockedInventorySlot(0));
        assertTrue(snapshot.isLockedInventorySlot(35));
        assertFalse(snapshot.isLockedInventorySlot(10));
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
