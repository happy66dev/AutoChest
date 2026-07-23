package io.github.autochest.container;

import java.util.UUID;

/**
 * 不可变的方块坐标值对象，包含世界 UUID 和整数坐标
 * 不持有 Bukkit World 或 Block 引用，可安全在异步线程传递
 */
public final class BlockPos {

    /** 所在世界的 UUID */
    private final UUID worldUuid;

    /** 方块 X 坐标 */
    private final int x;

    /** 方块 Y 坐标 */
    private final int y;

    /** 方块 Z 坐标 */
    private final int z;

    /**
     * 创建方块坐标
     *
     * @param worldUuid 世界 UUID
     * @param x         X 坐标
     * @param y         Y 坐标
     * @param z         Z 坐标
     */
    public BlockPos(UUID worldUuid, int x, int y, int z) {
        this.worldUuid = worldUuid;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * 计算与另一个坐标的平方欧氏距离（忽略世界 UUID，调用方保证同世界）
     * 使用 long 防止坐标差值较大时发生溢出
     *
     * @param other 目标坐标
     * @return 平方欧氏距离（long）
     */
    public long distanceSquared(BlockPos other) {
        long dx = (long) this.x - other.x;
        long dy = (long) this.y - other.y;
        long dz = (long) this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 生成用于去重和排序的字符串键，格式：worldUuid:x:y:z
     *
     * @return 坐标键字符串
     */
    public String toKey() {
        return worldUuid + ":" + x + ":" + y + ":" + z;
    }

    public UUID getWorldUuid() { return worldUuid; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockPos other)) return false;
        return x == other.x && y == other.y && z == other.z && worldUuid.equals(other.worldUuid);
    }

    @Override
    public int hashCode() {
        int result = worldUuid.hashCode();
        result = 31 * result + x;
        result = 31 * result + y;
        result = 31 * result + z;
        return result;
    }

    @Override
    public String toString() {
        return "BlockPos{" + toKey() + "}";
    }
}
