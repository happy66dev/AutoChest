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
        for (var entry : items.entrySet()) {
            if (entry.getKey() != null) {
                copiedItems.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().clone());
            }
        }
        items = Collections.unmodifiableNavigableMap(copiedItems);
    }

    public ItemStack itemAt(int logicalSlot) {
        ItemStack item = items.get(logicalSlot);
        return item == null ? null : item.clone();
    }
}
