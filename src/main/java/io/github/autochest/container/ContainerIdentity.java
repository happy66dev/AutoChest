package io.github.autochest.container;

import java.util.Comparator;

/**
 * 不可变的容器身份，规范化单箱、双箱和木桶
 * 保存扫描时的容器类型，用于提交前拒绝方块被替换后的其他库存
 * 不持有 Bukkit Block、Inventory 或 World 引用
 */
public final class ContainerIdentity {

    /** 扫描时允许参与任务的容器类型 */
    public enum ContainerType {
        /** 普通箱子 */
        CHEST,
        /** 陷阱箱 */
        TRAPPED_CHEST,
        /** 木桶 */
        BARREL,
        /** 未染色或染色潜影盒 */
        SHULKER_BOX,
        /** 作为玩家私有末影箱库存入口的末影箱方块 */
        ENDER_CHEST;

        /**
         * 判断该类型能否组成双箱
         *
         * @return true 表示普通箱或陷阱箱可组成双箱
         */
        public boolean supportsDoubleChest() {
            return this == CHEST || this == TRAPPED_CHEST;
        }
    }

    /** 容器规范化主坐标，双箱取两半中字典序较小者 */
    private final BlockPos primaryPos;

    /** 双箱另一半坐标；单容器为 null */
    private final BlockPos secondaryPos;

    /** 扫描时记录的精确容器类型 */
    private final ContainerType containerType;

    /** 从扫描中心到本容器几何中心的平方欧氏距离，用于排序 */
    private final long distanceSquared;

    /** 按距离和规范坐标键确定排序，保证容器处理顺序稳定 */
    public static final Comparator<ContainerIdentity> BY_DISTANCE_THEN_KEY =
            Comparator.comparingLong(ContainerIdentity::getDistanceSquared)
                    .thenComparing(ContainerIdentity::canonicalKey);

    /**
     * 创建单容器身份
     *
     * @param position        容器坐标
     * @param containerType   扫描时容器类型
     * @param distanceSquared 到扫描中心的平方距离
     */
    public ContainerIdentity(BlockPos position, ContainerType containerType, long distanceSquared) {
        // 喵~防御：单容器身份不能缺少坐标或类型快照。
        if (position == null || containerType == null) {
            throw new IllegalArgumentException("容器坐标和类型不能为空");
        }
        this.primaryPos = position;
        this.secondaryPos = null;
        this.containerType = containerType;
        this.distanceSquared = distanceSquared;
    }

    /**
     * 创建双箱容器身份
     * 内部按 key 字典序规范化两半位置，保证从任意一半发现时键相同
     *
     * @param positionA       双箱一半坐标
     * @param positionB       双箱另一半坐标
     * @param containerType   扫描时两半共同的箱子类型
     * @param distanceSquared 到扫描中心的平方距离
     */
    public ContainerIdentity(BlockPos positionA, BlockPos positionB,
                             ContainerType containerType, long distanceSquared) {
        // 喵~防御：双箱必须包含两半坐标且只能由箱子类型组成。
        if (positionA == null || positionB == null || containerType == null
                || !containerType.supportsDoubleChest()) {
            throw new IllegalArgumentException("双箱坐标无效或容器类型不支持双箱");
        }
        if (positionA.toKey().compareTo(positionB.toKey()) <= 0) {
            this.primaryPos = positionA;
            this.secondaryPos = positionB;
        } else {
            this.primaryPos = positionB;
            this.secondaryPos = positionA;
        }
        this.containerType = containerType;
        this.distanceSquared = distanceSquared;
    }

    /**
     * 判断是否为双箱
     *
     * @return true 表示身份包含双箱另一半坐标
     */
    public boolean isDoubleChest() {
        return secondaryPos != null;
    }

    /**
     * 生成规范化唯一键，仅由坐标组成以保持去重和排序稳定
     *
     * @return 单容器坐标键或双箱坐标对键
     */
    public String canonicalKey() {
        if (secondaryPos != null) {
            return primaryPos.toKey() + "|" + secondaryPos.toKey();
        }
        return primaryPos.toKey();
    }

    /**
     * 计算双箱几何中心到扫描中心的平方距离
     *
     * @param center 扫描中心坐标
     * @param positionA 双箱一半坐标
     * @param positionB 双箱另一半坐标
     * @return 放大四倍以保留半格精度的平方距离
     */
    public static long computeDistanceSquared(BlockPos center, BlockPos positionA, BlockPos positionB) {
        double centerX = (positionA.getX() + positionB.getX()) / 2.0;
        double centerY = (positionA.getY() + positionB.getY()) / 2.0;
        double centerZ = (positionA.getZ() + positionB.getZ()) / 2.0;
        double deltaX = center.getX() - centerX;
        double deltaY = center.getY() - centerY;
        double deltaZ = center.getZ() - centerZ;
        return (long) (deltaX * deltaX * 4 + deltaY * deltaY * 4 + deltaZ * deltaZ * 4);
    }

    public BlockPos getPrimaryPos() { return primaryPos; }
    public BlockPos getSecondaryPos() { return secondaryPos; }
    public ContainerType getContainerType() { return containerType; }
    public long getDistanceSquared() { return distanceSquared; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ContainerIdentity identity)) return false;
        return canonicalKey().equals(identity.canonicalKey());
    }

    @Override
    public int hashCode() {
        return canonicalKey().hashCode();
    }

    @Override
    public String toString() {
        return "ContainerIdentity{" + canonicalKey() + ", type=" + containerType
                + ", dist²=" + distanceSquared + "}";
    }
}
