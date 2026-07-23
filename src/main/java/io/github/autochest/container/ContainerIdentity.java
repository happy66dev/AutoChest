package io.github.autochest.container;

import java.util.Comparator;

/**
 * 不可变的容器身份，规范化单箱、双箱和木桶
 * 双箱保存两半坐标，用于跨区块加载检查和去重
 * 不持有 Bukkit Block、Inventory 或 World 引用
 */
public final class ContainerIdentity {

    /** 容器规范化主坐标（双箱取两半中字典序较小者） */
    private final BlockPos primaryPos;

    /**
     * 双箱另一半坐标；单箱或木桶为 null
     * 注意：读取双箱任何一半的方块状态前，必须先确认两半区块均已加载
     */
    private final BlockPos secondaryPos;

    /** 从扫描中心到本容器几何中心的平方欧氏距离，用于排序 */
    private final long distanceSquared;

    /**
     * 按距离升序，距离相同按 canonicalKey 字典序升序排列的比较器
     * 保证多容器处理顺序确定且可重复
     */
    public static final Comparator<ContainerIdentity> BY_DISTANCE_THEN_KEY =
            Comparator.comparingLong(ContainerIdentity::getDistanceSquared)
                    .thenComparing(ContainerIdentity::canonicalKey);

    /**
     * 创建容器身份（单箱或木桶）
     *
     * @param pos              容器坐标
     * @param distanceSquared  到扫描中心的平方距离
     */
    public ContainerIdentity(BlockPos pos, long distanceSquared) {
        this.primaryPos = pos;
        this.secondaryPos = null;
        this.distanceSquared = distanceSquared;
    }

    /**
     * 创建双箱容器身份
     * 内部按 key 字典序自动规范化，保证同一双箱无论从哪一半发现都生成相同 canonicalKey
     *
     * @param posA            双箱一半坐标
     * @param posB            双箱另一半坐标
     * @param distanceSquared 到扫描中心的平方距离（以几何中心计算）
     */
    public ContainerIdentity(BlockPos posA, BlockPos posB, long distanceSquared) {
        // 按字典序决定主副，保证去重时两半顺序一致
        if (posA.toKey().compareTo(posB.toKey()) <= 0) {
            this.primaryPos = posA;
            this.secondaryPos = posB;
        } else {
            this.primaryPos = posB;
            this.secondaryPos = posA;
        }
        this.distanceSquared = distanceSquared;
    }

    /**
     * 判断是否为双箱
     *
     * @return true 表示双箱
     */
    public boolean isDoubleChest() {
        return secondaryPos != null;
    }

    /**
     * 生成规范化的唯一键，用于去重和排序
     * 双箱：primaryKey + "|" + secondaryKey
     * 单箱/木桶：primaryKey
     *
     * @return 规范化唯一键字符串
     */
    public String canonicalKey() {
        if (secondaryPos != null) {
            return primaryPos.toKey() + "|" + secondaryPos.toKey();
        }
        return primaryPos.toKey();
    }

    /**
     * 计算容器几何中心到另一坐标的平方距离
     * 供外部扫描时计算距离（内部已缓存结果）
     *
     * @param center 扫描中心坐标
     * @return 平方距离
     */
    public static long computeDistanceSquared(BlockPos center, BlockPos posA, BlockPos posB) {
        // 双箱几何中心：两半坐标各轴取平均，使用 0.5 精度
        double cx = (posA.getX() + posB.getX()) / 2.0;
        double cy = (posA.getY() + posB.getY()) / 2.0;
        double cz = (posA.getZ() + posB.getZ()) / 2.0;
        double dx = center.getX() - cx;
        double dy = center.getY() - cy;
        double dz = center.getZ() - cz;
        // 返回放大 4 倍的整数值，保留精度又避免浮点排序不一致
        return (long) (dx * dx * 4 + dy * dy * 4 + dz * dz * 4);
    }

    public BlockPos getPrimaryPos() { return primaryPos; }
    public BlockPos getSecondaryPos() { return secondaryPos; }
    public long getDistanceSquared() { return distanceSquared; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContainerIdentity other)) return false;
        return canonicalKey().equals(other.canonicalKey());
    }

    @Override
    public int hashCode() {
        return canonicalKey().hashCode();
    }

    @Override
    public String toString() {
        return "ContainerIdentity{" + canonicalKey() + ", dist²=" + distanceSquared + "}";
    }
}
