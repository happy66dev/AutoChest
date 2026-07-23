package io.github.autochest.container;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContainerIdentity 容器身份测试
 * 验证双箱规范化、去重、距离排序和同距稳定排序
 */
class ContainerIdentityTest {

    private static final UUID WORLD = UUID.randomUUID();

    /**
     * 双箱从两个不同方向创建，canonicalKey 应相同（去重保证）
     */
    @Test
    void doubleChest_canonicalKey_isOrderIndependent() {
        BlockPos posA = new BlockPos(WORLD, 0, 64, 0);
        BlockPos posB = new BlockPos(WORLD, 1, 64, 0);

        ContainerIdentity id1 = new ContainerIdentity(posA, posB, 100L);
        ContainerIdentity id2 = new ContainerIdentity(posB, posA, 100L);

        assertEquals(id1.canonicalKey(), id2.canonicalKey(),
                "双箱从不同方向创建时 canonicalKey 应相同");
        assertEquals(id1, id2, "equals 应基于 canonicalKey");
    }

    /**
     * 单箱 isDoubleChest 应为 false，双箱应为 true
     */
    @Test
    void singleVsDouble_isDoubleChest() {
        BlockPos pos = new BlockPos(WORLD, 0, 64, 0);
        ContainerIdentity single = new ContainerIdentity(pos, 100L);
        ContainerIdentity doubleChest = new ContainerIdentity(
                pos, new BlockPos(WORLD, 1, 64, 0), 100L);

        assertFalse(single.isDoubleChest());
        assertTrue(doubleChest.isDoubleChest());
    }

    /**
     * 按距离排序：近容器应排在前
     */
    @Test
    void sort_byDistanceThenKey_distanceFirst() {
        BlockPos pos1 = new BlockPos(WORLD, 0, 64, 0);
        BlockPos pos2 = new BlockPos(WORLD, 5, 64, 0);
        BlockPos pos3 = new BlockPos(WORLD, 10, 64, 0);

        ContainerIdentity near = new ContainerIdentity(pos1, 1L);
        ContainerIdentity mid = new ContainerIdentity(pos2, 25L);
        ContainerIdentity far = new ContainerIdentity(pos3, 100L);

        List<ContainerIdentity> list = new ArrayList<>(List.of(far, near, mid));
        list.sort(ContainerIdentity.BY_DISTANCE_THEN_KEY);

        assertEquals(near, list.get(0), "最近的容器应排第一");
        assertEquals(mid, list.get(1));
        assertEquals(far, list.get(2), "最远的容器应排最后");
    }

    /**
     * 距离相同时，按 canonicalKey 字典序稳定排序
     */
    @Test
    void sort_sameDistance_stableByKey() {
        // 两个单箱距离相同，但坐标不同
        BlockPos posA = new BlockPos(WORLD, 3, 64, 0);
        BlockPos posB = new BlockPos(WORLD, -3, 64, 0);
        BlockPos center = new BlockPos(WORLD, 0, 64, 0);

        long distA = posA.distanceSquared(center); // 9
        long distB = posB.distanceSquared(center); // 9

        ContainerIdentity idA = new ContainerIdentity(posA, distA);
        ContainerIdentity idB = new ContainerIdentity(posB, distB);

        List<ContainerIdentity> list = new ArrayList<>(List.of(idA, idB));
        list.sort(ContainerIdentity.BY_DISTANCE_THEN_KEY);

        // 排序结果应确定：按 canonicalKey 字典序
        String expectedFirst = idA.canonicalKey().compareTo(idB.canonicalKey()) < 0
                ? idA.canonicalKey() : idB.canonicalKey();
        assertEquals(expectedFirst, list.get(0).canonicalKey());
    }

    /**
     * BlockPos.distanceSquared 计算正确性
     */
    @Test
    void blockPos_distanceSquared_correct() {
        BlockPos a = new BlockPos(WORLD, 0, 0, 0);
        BlockPos b = new BlockPos(WORLD, 3, 4, 0);

        // 3² + 4² = 9 + 16 = 25
        assertEquals(25L, a.distanceSquared(b));
    }

    /**
     * 双箱几何中心距离计算正确性
     */
    @Test
    void doubleChest_geometricCenter_distanceSquared() {
        BlockPos center = new BlockPos(WORLD, 0, 64, 0);
        BlockPos half1 = new BlockPos(WORLD, 2, 64, 0);
        BlockPos half2 = new BlockPos(WORLD, 3, 64, 0);

        // 几何中心为 (2.5, 64, 0)，到 (0,64,0) 的距离平方 × 4 = 25
        long dist = ContainerIdentity.computeDistanceSquared(center, half1, half2);
        assertEquals(25L, dist);
    }
}
