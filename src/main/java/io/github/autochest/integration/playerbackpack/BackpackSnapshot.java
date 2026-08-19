package io.github.autochest.integration.playerbackpack;

import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public record BackpackSnapshot(UUID playerId, int capacity, long revision, NavigableMap<Integer, ItemStack> items) {
    public BackpackSnapshot {
        if (playerId == null || capacity < 0 || revision < 0 || items == null) {
            throw new IllegalArgumentException("背包快照参数非法喵~");
        }
        NavigableMap<Integer, ItemStack> copiedItems = new TreeMap<>();
        // 遍历 provider 返回的逻辑槽位并执行容量与物品边界校验喵~
        for (var entry : items.entrySet()) {
            // 喵~防御：忽略空逻辑槽位键，避免构造无效索引喵~
            if (entry.getKey() == null) {
                continue;
            }
            // 喵~防御：拒绝容量外槽位，防止 overflow 写入错误目标喵~
            if (entry.getKey() <= 0 || entry.getKey() > capacity) {
                throw new IllegalArgumentException("背包逻辑槽位超出容量喵~");
            }
            // 读取 provider 返回的物品镜像喵~
            ItemStack item = entry.getValue();
            // 喵~防御：拒绝 AIR、非正数量和超过堆叠上限的物品喵~
            if (item != null && (item.getType().isAir() || item.getAmount() <= 0
                    || item.getAmount() > item.getMaxStackSize())) {
                throw new IllegalArgumentException("背包物品数量或类型非法喵~");
            }
            // 深复制物品，避免外部修改快照内部状态喵~
            copiedItems.put(entry.getKey(), item == null ? null : item.clone());
        }
        // 发布不可变逻辑槽位映射喵~
        items = Collections.unmodifiableNavigableMap(copiedItems);
    }

    public ItemStack itemAt(int logicalSlot) {
        ItemStack item = items.get(logicalSlot);
        return item == null ? null : item.clone();
    }
}
