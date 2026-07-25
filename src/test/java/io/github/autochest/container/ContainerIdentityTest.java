package io.github.autochest.container;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContainerIdentity 容器身份测试
 * 验证类型快照、双箱规范化、距离排序和类型安全约束
 */
class ContainerIdentityTest {

    /** 测试世界 UUID */
    private static final UUID WORLD = UUID.randomUUID();

    /**
     * 双箱从不同方向创建时必须保留相同规范键和类型快照
     */
    @Test
    void doubleChest_canonicalKeyIsOrderIndependent() {
        BlockPos firstPosition = new BlockPos(WORLD, 0, 64, 0);
        BlockPos secondPosition = new BlockPos(WORLD, 1, 64, 0);

        ContainerIdentity firstIdentity = new ContainerIdentity(firstPosition, secondPosition,
                ContainerIdentity.ContainerType.CHEST, 100L);
        ContainerIdentity reversedIdentity = new ContainerIdentity(secondPosition, firstPosition,
                ContainerIdentity.ContainerType.CHEST, 100L);

        assertEquals(firstIdentity.canonicalKey(), reversedIdentity.canonicalKey());
        assertEquals(firstIdentity, reversedIdentity);
        assertEquals(ContainerIdentity.ContainerType.CHEST, firstIdentity.getContainerType());
    }

    /**
     * 单容器和双箱应正确暴露双箱状态与扫描类型
     */
    @Test
    void singleAndDouble_chestStateAndTypeAreCorrect() {
        BlockPos position = new BlockPos(WORLD, 0, 64, 0);
        ContainerIdentity barrelIdentity = new ContainerIdentity(position,
                ContainerIdentity.ContainerType.BARREL, 100L);
        ContainerIdentity trappedChestIdentity = new ContainerIdentity(position,
                new BlockPos(WORLD, 1, 64, 0), ContainerIdentity.ContainerType.TRAPPED_CHEST, 100L);

        assertFalse(barrelIdentity.isDoubleChest());
        assertEquals(ContainerIdentity.ContainerType.BARREL, barrelIdentity.getContainerType());
        assertTrue(trappedChestIdentity.isDoubleChest());
        assertEquals(ContainerIdentity.ContainerType.TRAPPED_CHEST, trappedChestIdentity.getContainerType());
    }

    /**
     * 木桶不得构造为双箱，避免非法身份进入提交阶段
     */
    @Test
    void doubleChest_barrelTypeIsRejected() {
        BlockPos firstPosition = new BlockPos(WORLD, 0, 64, 0);
        BlockPos secondPosition = new BlockPos(WORLD, 1, 64, 0);

        assertThrows(IllegalArgumentException.class, () -> new ContainerIdentity(firstPosition, secondPosition,
                ContainerIdentity.ContainerType.BARREL, 100L));
    }

    /**
     * 潜影盒和末影箱均不得构造为双箱，避免错误进入双箱实时校验分支。
     */
    @Test
    void doubleChest_shulkerAndEnderChestTypesAreRejected() {
        BlockPos firstPosition = new BlockPos(WORLD, 0, 64, 0);
        BlockPos secondPosition = new BlockPos(WORLD, 1, 64, 0);

        assertThrows(IllegalArgumentException.class, () -> new ContainerIdentity(firstPosition, secondPosition,
                ContainerIdentity.ContainerType.SHULKER_BOX, 100L));
        assertThrows(IllegalArgumentException.class, () -> new ContainerIdentity(firstPosition, secondPosition,
                ContainerIdentity.ContainerType.ENDER_CHEST, 100L));
    }


    @Test
    void canonicalKey_isIndependentOfContainerType() {
        BlockPos position = new BlockPos(WORLD, 0, 64, 0);
        ContainerIdentity chestIdentity = new ContainerIdentity(position,
                ContainerIdentity.ContainerType.CHEST, 100L);
        ContainerIdentity barrelIdentity = new ContainerIdentity(position,
                ContainerIdentity.ContainerType.BARREL, 100L);

        assertEquals(chestIdentity.canonicalKey(), barrelIdentity.canonicalKey());
    }

    /**
     * 按距离排序时近容器应优先
     */
    @Test
    void sort_byDistanceThenKey_distanceFirst() {
        ContainerIdentity nearIdentity = new ContainerIdentity(new BlockPos(WORLD, 0, 64, 0),
                ContainerIdentity.ContainerType.CHEST, 1L);
        ContainerIdentity middleIdentity = new ContainerIdentity(new BlockPos(WORLD, 5, 64, 0),
                ContainerIdentity.ContainerType.CHEST, 25L);
        ContainerIdentity farIdentity = new ContainerIdentity(new BlockPos(WORLD, 10, 64, 0),
                ContainerIdentity.ContainerType.CHEST, 100L);

        List<ContainerIdentity> identities = new ArrayList<>(List.of(farIdentity, nearIdentity, middleIdentity));
        identities.sort(ContainerIdentity.BY_DISTANCE_THEN_KEY);

        assertEquals(nearIdentity, identities.get(0));
        assertEquals(middleIdentity, identities.get(1));
        assertEquals(farIdentity, identities.get(2));
    }

    /**
     * BlockPos 的平方距离计算必须正确
     */
    @Test
    void blockPos_distanceSquaredIsCorrect() {
        BlockPos firstPosition = new BlockPos(WORLD, 0, 0, 0);
        BlockPos secondPosition = new BlockPos(WORLD, 3, 4, 0);

        assertEquals(25L, firstPosition.distanceSquared(secondPosition));
    }

    /**
     * 双箱几何中心距离必须保留半格精度
     */
    @Test
    void doubleChest_geometricCenterDistanceIsCorrect() {
        BlockPos centerPosition = new BlockPos(WORLD, 0, 64, 0);
        BlockPos firstHalfPosition = new BlockPos(WORLD, 2, 64, 0);
        BlockPos secondHalfPosition = new BlockPos(WORLD, 3, 64, 0);

        assertEquals(25L, ContainerIdentity.computeDistanceSquared(centerPosition,
                firstHalfPosition, secondHalfPosition));
    }
}
