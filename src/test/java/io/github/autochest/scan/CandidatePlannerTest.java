package io.github.autochest.scan;

import io.github.autochest.container.BlockPos;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.scan.InventorySnapshotFactory.ContainerDto;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CandidatePlanner 快照候选资格测试
 * 使用 Mockito 验证同类物品数量归一化及空容器不获得接收资格
 */
class CandidatePlannerTest {

    /**
     * 容器与玩家堆叠数量不同但身份相同时，应拥有同一个候选身份键
     */
    @Test
    void itemKey_normalizesAmountForSimilarStacks() {
        ItemStack containerItem = mockStackWithSerializedIdentity(new byte[]{1, 2, 3});
        ItemStack playerItem = mockStackWithSerializedIdentity(new byte[]{1, 2, 3});

        assertEquals(InventorySnapshotFactory.itemKey(containerItem),
                InventorySnapshotFactory.itemKey(playerItem));
    }

    /**
     * 快照为空的容器不得成为该物品的候选容器
     */
    @Test
    void plan_emptySnapshotContainerIsNotCandidate() {
        UUID worldUuid = UUID.randomUUID();
        ContainerIdentity emptyIdentity = new ContainerIdentity(
                new BlockPos(worldUuid, 0, 64, 0), ContainerIdentity.ContainerType.CHEST, 1L);
        ContainerIdentity filledIdentity = new ContainerIdentity(
                new BlockPos(worldUuid, 1, 64, 0), ContainerIdentity.ContainerType.CHEST, 1L);
        Inventory emptyInventory = mock(Inventory.class);
        Inventory filledInventory = mock(Inventory.class);
        ItemStack cobblestoneItem = mockStackWithSerializedIdentity(new byte[]{4, 5, 6});
        when(emptyInventory.getContents()).thenReturn(new ItemStack[]{null});
        when(filledInventory.getContents()).thenReturn(new ItemStack[]{cobblestoneItem});

        InventorySnapshotFactory snapshotFactory = new InventorySnapshotFactory();
        ContainerDto emptyDto = snapshotFactory.snapshotContainer(emptyIdentity, emptyInventory);
        ContainerDto filledDto = snapshotFactory.snapshotContainer(filledIdentity, filledInventory);
        CandidatePlanner.PlanResult plan = new CandidatePlanner().plan(List.of(emptyDto, filledDto));
        String cobblestoneKey = InventorySnapshotFactory.itemKey(cobblestoneItem);

        assertFalse(plan.isSnapshotCandidate(cobblestoneKey, emptyIdentity));
        assertTrue(plan.isSnapshotCandidate(cobblestoneKey, filledIdentity));
    }

    /**
     * 多个末影箱入口指向同一玩家私有库存时，只能保留距离最近的入口。
     */
    @Test
    void planAndRestockPlan_multipleEnderChestEntrances_keepOnlyNearest() {
        UUID worldUuid = UUID.randomUUID();
        ContainerIdentity nearestEnderChest = new ContainerIdentity(
                new BlockPos(worldUuid, 1, 64, 0), ContainerIdentity.ContainerType.ENDER_CHEST, 1L);
        ContainerIdentity ordinaryChest = new ContainerIdentity(
                new BlockPos(worldUuid, 2, 64, 0), ContainerIdentity.ContainerType.CHEST, 4L);
        ContainerIdentity fartherEnderChest = new ContainerIdentity(
                new BlockPos(worldUuid, 3, 64, 0), ContainerIdentity.ContainerType.ENDER_CHEST, 9L);
        ItemStack enderChestItem = mockStackWithSerializedIdentity(new byte[]{7, 8, 9});
        String itemKey = InventorySnapshotFactory.itemKey(enderChestItem);
        CandidatePlanner planner = new CandidatePlanner();

        CandidatePlanner.PlanResult depositPlan = planner.plan(List.of(
                new ContainerDto(nearestEnderChest, List.of(itemKey)),
                new ContainerDto(ordinaryChest, List.of()),
                new ContainerDto(fartherEnderChest, List.of(itemKey))));
        CandidatePlanner.PlanResult restockPlan = planner.planForRestock(List.of(
                nearestEnderChest, ordinaryChest, fartherEnderChest));

        assertEquals(List.of(nearestEnderChest, ordinaryChest), depositPlan.sortedContainers);
        assertTrue(depositPlan.isSnapshotCandidate(itemKey, nearestEnderChest));
        assertFalse(depositPlan.isSnapshotCandidate(itemKey, fartherEnderChest));
        assertEquals(List.of(nearestEnderChest, ordinaryChest), restockPlan.sortedContainers);
    }
    /**
     * 构造可被数量归一化序列化的物品 Mockito 替身
     *
     * @param serializedIdentity 归一化后的完整物品身份字节
     * @return 可用于快照的物品替身
     */
    private ItemStack mockStackWithSerializedIdentity(byte[] serializedIdentity) {
        Material itemMaterial = mock(Material.class);
        ItemStack originalItem = mock(ItemStack.class);
        ItemStack normalizedItem = mock(ItemStack.class);
        when(itemMaterial.isAir()).thenReturn(false);
        when(originalItem.getType()).thenReturn(itemMaterial);
        when(originalItem.clone()).thenReturn(normalizedItem);
        when(normalizedItem.serializeAsBytes()).thenReturn(serializedIdentity);
        return originalItem;
    }
}
