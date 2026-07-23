package io.github.autochest.service;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RestockTargetWhitelist 目标槽位白名单测试
 * 使用 Mockito 验证永久失效语义，不依赖服务器初始化
 */
class RestockTargetWhitelistTest {

    /** 被测玩家对象 */
    private Player player;

    /** 被测玩家的背包对象 */
    private PlayerInventory inventory;

    /** 任务开始时的非满物品 */
    private ItemStack expectedItem;

    /**
     * 创建仅含一个合格目标槽位的白名单测试环境
     */
    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        expectedItem = mock(ItemStack.class);
        ItemStack expectedSnapshot = mock(ItemStack.class);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItem(0)).thenReturn(expectedItem);
        when(expectedItem.getType()).thenReturn(Material.COBBLESTONE);
        when(expectedItem.getAmount()).thenReturn(1);
        when(expectedItem.getMaxStackSize()).thenReturn(64);
        when(expectedItem.clone()).thenReturn(expectedSnapshot);
    }

    /**
     * 外部库存事件失效后，即使槽位恢复相似物品也不得重新获得资格
     */
    @Test
    void invalidateSlot_makesSimilarItemPermanentlyIneligible() {
        RestockTargetWhitelist whitelist = new RestockTargetWhitelist(player);
        ItemStack restoredSimilarItem = mock(ItemStack.class);
        when(restoredSimilarItem.getType()).thenReturn(Material.COBBLESTONE);
        when(restoredSimilarItem.isSimilar(any(ItemStack.class))).thenReturn(true);

        whitelist.invalidateSlot(0);

        assertFalse(whitelist.isEligible(0, restoredSimilarItem));
    }

    /**
     * 实时槽位变为空后，即使随后恢复相似物品也不得重新获得资格
     */
    @Test
    void changedSlot_staysPermanentlyIneligibleAfterSimilarItemReturns() {
        RestockTargetWhitelist whitelist = new RestockTargetWhitelist(player);
        ItemStack restoredSimilarItem = mock(ItemStack.class);
        when(restoredSimilarItem.getType()).thenReturn(Material.COBBLESTONE);
        when(restoredSimilarItem.isSimilar(any(ItemStack.class))).thenReturn(true);

        assertFalse(whitelist.isEligible(0, null));
        assertFalse(whitelist.isEligible(0, restoredSimilarItem));
    }

    /**
     * 全量失效只影响任务开始时进入白名单的目标槽位
     */
    @Test
    void invalidateAll_invalidatesTrackedSlotsButNotUntrackedSlots() {
        RestockTargetWhitelist whitelist = new RestockTargetWhitelist(player);
        ItemStack similarItem = mock(ItemStack.class);
        when(similarItem.getType()).thenReturn(Material.COBBLESTONE);
        when(similarItem.isSimilar(any(ItemStack.class))).thenReturn(true);

        assertTrue(whitelist.isEligible(0, similarItem));
        whitelist.invalidateAll();

        assertFalse(whitelist.isEligible(0, similarItem));
        assertFalse(whitelist.isEligible(1, similarItem));
    }
}
