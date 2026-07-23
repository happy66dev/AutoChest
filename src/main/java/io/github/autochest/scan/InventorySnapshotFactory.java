package io.github.autochest.scan;

import io.github.autochest.container.ContainerIdentity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存快照工厂，在主线程将容器库存转换为 Bukkit-free DTO
 * 异步规划只保存容器在任务开始时拥有的完整物品身份，最终匹配仍在主线程完成
 */
public class InventorySnapshotFactory {

    /**
     * 容器库存快照 DTO，包含容器身份及其任务开始时已有的物品身份键
     */
    public static final class ContainerDto {
        /** 容器身份（不可变） */
        public final ContainerIdentity identity;
        /** 任务开始时容器非空物品的数量归一化身份键 */
        public final List<String> itemKeys;

        ContainerDto(ContainerIdentity identity, List<String> itemKeys) {
            this.identity = identity;
            this.itemKeys = Collections.unmodifiableList(itemKeys);
        }
    }

    /**
     * 快照容器库存，保存每种非空物品的数量归一化完整身份键
     * 必须在主线程调用
     *
     * @param identity  容器身份
     * @param inventory 容器库存
     * @return 容器快照 DTO
     */
    public ContainerDto snapshotContainer(ContainerIdentity identity,
                                          org.bukkit.inventory.Inventory inventory) {
        // 喵~防御：空容器引用只产生无物品候选，避免扫描阶段异常中断。
        if (identity == null || inventory == null) {
            return new ContainerDto(identity, List.of());
        }
        Map<String, Boolean> uniqueItemKeys = new LinkedHashMap<>();
        for (ItemStack item : inventory.getContents()) {
            String itemKey = itemKey(item);
            if (itemKey != null) {
                uniqueItemKeys.put(itemKey, Boolean.TRUE);
            }
        }
        return new ContainerDto(identity, new ArrayList<>(uniqueItemKeys.keySet()));
    }

    /**
     * 为物品创建数量归一化后的完整身份键
     * 此键只用于快照候选资格，实时写入前仍必须用 isSimilar 复验
     *
     * @param item 原始物品，可为空
     * @return 稳定的 Base64 身份键，空物品或序列化失败时返回 null
     */
    public static String itemKey(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        try {
            ItemStack normalizedItem = item.clone();
            normalizedItem.setAmount(1);
            return Arrays.toString(normalizedItem.serializeAsBytes());
        } catch (RuntimeException exception) {
            // 喵~防御：不可信的物品序列化不能让容器获得候选资格。
            return null;
        }
    }
}
