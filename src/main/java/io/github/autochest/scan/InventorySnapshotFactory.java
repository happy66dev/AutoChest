package io.github.autochest.scan;

import io.github.autochest.container.BlockPos;
import io.github.autochest.container.ContainerIdentity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * 库存快照工厂，在主线程将玩家背包和容器库存转为 Bukkit-free DTO
 * 所有 ItemStack 均深拷贝，不保留指向实时库存的引用
 */
public class InventorySnapshotFactory {

    /**
     * 单个槽位的 DTO，仅含基本数值和序列化后的物品身份字节
     * 可安全在异步线程中使用
     */
    public static final class SlotDto {
        /** 槽位编号 */
        public final int slot;
        /** 当前物品数量，0 表示空槽 */
        public final int amount;
        /** 该物品类型的最大堆叠数 */
        public final int maxStackSize;
        /** 是否为空槽 */
        public final boolean isEmpty;
        /**
         * 物品序列化身份字节，用于异步候选索引
         * 注意：最终物品相似性判断必须在主线程使用实时 isSimilar()，不能只比较此字段
         */
        public final byte[] itemKey;

        SlotDto(int slot, int amount, int maxStackSize, boolean isEmpty, byte[] itemKey) {
            this.slot = slot;
            this.amount = amount;
            this.maxStackSize = maxStackSize;
            this.isEmpty = isEmpty;
            this.itemKey = itemKey;
        }
    }

    /**
     * 玩家库存快照 DTO，含指定槽位范围的深拷贝
     */
    public static final class PlayerInventoryDto {
        /** 玩家 UUID */
        public final UUID playerUuid;
        /** 各槽位的 DTO，槽位编号为 key */
        public final Map<Integer, SlotDto> slots;

        PlayerInventoryDto(UUID playerUuid, Map<Integer, SlotDto> slots) {
            this.playerUuid = playerUuid;
            this.slots = Collections.unmodifiableMap(slots);
        }
    }

    /**
     * 容器库存快照 DTO，含容器身份和所有槽位的深拷贝
     */
    public static final class ContainerDto {
        /** 容器身份（不可变） */
        public final ContainerIdentity identity;
        /** 所有槽位的 DTO */
        public final List<SlotDto> slots;

        ContainerDto(ContainerIdentity identity, List<SlotDto> slots) {
            this.identity = identity;
            this.slots = Collections.unmodifiableList(slots);
        }
    }

    /**
     * 快照玩家指定槽位范围，生成 Bukkit-free DTO
     * 必须在主线程调用
     *
     * @param player    玩家
     * @param slotFrom  起始槽位（含）
     * @param slotTo    结束槽位（含）
     * @return 玩家库存快照 DTO
     */
    public PlayerInventoryDto snapshotPlayer(Player player, int slotFrom, int slotTo) {
        Map<Integer, SlotDto> slots = new LinkedHashMap<>();
        for (int i = slotFrom; i <= slotTo; i++) {
            // 读取后立即 clone，不保留原始引用
            ItemStack raw = player.getInventory().getItem(i);
            slots.put(i, toSlotDto(i, raw));
        }
        return new PlayerInventoryDto(player.getUniqueId(), slots);
    }

    /**
     * 快照容器库存，生成 Bukkit-free DTO
     * 必须在主线程调用
     *
     * @param identity  容器身份
     * @param inventory 容器库存
     * @return 容器快照 DTO
     */
    public ContainerDto snapshotContainer(ContainerIdentity identity,
                                           org.bukkit.inventory.Inventory inventory) {
        List<SlotDto> slots = new ArrayList<>();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack raw = inventory.getItem(i);
            slots.add(toSlotDto(i, raw));
        }
        return new ContainerDto(identity, slots);
    }

    /**
     * 将单个 ItemStack 转为 SlotDto
     * 空槽或 null 转为 isEmpty=true 的 DTO
     *
     * @param slot  槽位编号
     * @param item  物品（可为 null）
     * @return 对应的 SlotDto
     */
    private SlotDto toSlotDto(int slot, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            // 空槽
            return new SlotDto(slot, 0, 64, true, new byte[0]);
        }
        // 序列化物品身份，仅用于异步候选索引，不用于最终匹配
        byte[] key;
        try {
            key = item.serializeAsBytes();
        } catch (Exception e) {
            // 喵~防御：序列化失败时使用空字节数组，不影响主线程实时匹配
            key = new byte[0];
        }
        return new SlotDto(slot, item.getAmount(), item.getMaxStackSize(), false, key);
    }
}
