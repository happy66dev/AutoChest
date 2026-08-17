package io.github.autochest.service;

import io.github.autochest.preference.OperationPreferencesSnapshot;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Restock 目标槽位不可变白名单
 * 在命令接受时生成，记录各槽位的初始物品身份和最大堆叠数
 * 槽位一旦变化（物品变更或置空）即永久失去本次任务的补货资格
 */
public class RestockTargetWhitelist {

    /**
     * 目标槽位信息，不可变
     */
    private static final class SlotEntry {
        /** 初始物品深拷贝，用于 isSimilar 比较 */
        final ItemStack expectedItem;
        /** 该物品的最大堆叠数 */
        final int maxStackSize;

        SlotEntry(ItemStack expectedItem, int maxStackSize) {
            this.expectedItem = expectedItem;
            this.maxStackSize = maxStackSize;
        }
    }

    /**
     * 合格槽位初始快照，槽位编号 → 槽位信息
     * 只包含任务开始时非满的非空槽位（0..35）
     */
    private final Map<Integer, SlotEntry> entries;

    /**
     * 已永久失效的槽位集合
     * 由主线程库存事件和实时提交校验共同写入
     */
    private final Set<Integer> invalidated = Collections.synchronizedSet(new HashSet<>());

    /**
     * 在命令接受时创建白名单快照
     * 必须在主线程调用
     *
     * @param player 执行 restock 的玩家
     */
    public RestockTargetWhitelist(org.bukkit.entity.Player player,
                                  OperationPreferencesSnapshot preferencesSnapshot) {
        Map<Integer, SlotEntry> map = new LinkedHashMap<>();
        // 遍历完整玩家背包 0..35，记录任务快照允许补货的目标。
        for (int slot = 0; slot <= 35; slot++) {
            // 喵~防御：缺少权限快照或槽位禁止 restock 时跳过目标。
            if (preferencesSnapshot == null || !preferencesSnapshot.allowsRestock(slot)) {
                continue;
            }
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (item.getAmount() >= item.getMaxStackSize()) {
                continue;
            }
            map.put(slot, new SlotEntry(item.clone(), item.getMaxStackSize()));
        }
        this.entries = Collections.unmodifiableMap(map);
    }

    /** 兼容旧调用方，默认所有槽位允许补货。 */
    public RestockTargetWhitelist(org.bukkit.entity.Player player) {
        this(player, OperationPreferencesSnapshot.defaults());
    }

    /**
     * 检查指定槽位是否仍具有本次补货资格
     * 若当前物品与快照不相似，则永久标记失效
     *
     * @param slot        槽位编号
     * @param currentItem 当前实时物品（可为 null，表示空槽）
     * @return true 表示仍有资格
     */
    public boolean isEligible(int slot, ItemStack currentItem) {
        // 不在白名单中的槽位直接不合格
        SlotEntry entry = entries.get(slot);
        if (entry == null) {
            return false;
        }
        // 已被标记失效
        if (invalidated.contains(slot)) {
            return false;
        }
        // 当前为空或不相似，永久标记失效
        if (currentItem == null || currentItem.getType().isAir()
                || !currentItem.isSimilar(entry.expectedItem)) {
            invalidated.add(slot);
            return false;
        }
        return true;
    }

    /**
     * 手动标记槽位永久失效（由 RestockTargetListener 提示调用）
     * 提交时 isEligible 仍会做最终实时判断
     *
     * @param slot 槽位编号
     */
    public void invalidateSlot(int slot) {
        if (entries.containsKey(slot)) {
            invalidated.add(slot);
        }
    }

    /**
     * 使本次任务全部目标槽位永久失效
     * 用于无法可靠定位所有受影响玩家槽位的库存交互事件
     */
    public void invalidateAll() {
        invalidated.addAll(entries.keySet());
    }

    /**
     * 获取按槽位升序排列的合格槽位列表
     * 只返回当前尚未失效的槽位
     *
     * @return 合格槽位编号列表（按升序）
     */
    public List<Integer> eligibleSlotsSorted() {
        List<Integer> result = new ArrayList<>();
        for (int slot : entries.keySet()) {
            // 跳过已明确失效的槽位
            if (!invalidated.contains(slot)) {
                result.add(slot);
            }
        }
        // entries 是 LinkedHashMap 按插入顺序（槽位升序）维护，无需额外排序
        return result;
    }

    /**
     * 获取指定槽位的初始期望物品（用于补货量计算）
     *
     * @param slot 槽位编号
     * @return 期望物品的 clone，若不存在则返回 null
     */
    public ItemStack getExpectedItem(int slot) {
        SlotEntry entry = entries.get(slot);
        return entry != null ? entry.expectedItem.clone() : null;
    }

    /**
     * 获取指定槽位的最大堆叠数
     *
     * @param slot 槽位编号
     * @return 最大堆叠数，若不存在则返回 64
     */
    public int getMaxStackSize(int slot) {
        SlotEntry entry = entries.get(slot);
        return entry != null ? entry.maxStackSize : 64;
    }
}
