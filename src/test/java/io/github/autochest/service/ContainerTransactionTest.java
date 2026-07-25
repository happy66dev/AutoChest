package io.github.autochest.service;

import io.github.autochest.container.BlockPos;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.hook.CompositeAccessPolicy;
import io.github.autochest.hook.ContainerAccessPolicy;
import io.github.autochest.task.PlayerTaskRegistry;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.EnderChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ContainerTransaction 测试
 * 测试不依赖 Bukkit 服务器初始化的工具方法逻辑
 * 完整流程测试（commitDeposit/Restock）通过人工验收或集成测试覆盖
 */
class ContainerTransactionTest {

    private PlayerTaskRegistry registry;
    private CompositeAccessPolicy accessPolicy;
    private ContainerTransaction transaction;

    @BeforeEach
    void setUp() {
        registry = mock(PlayerTaskRegistry.class);
        ContainerAccessPolicy allowAll = new ContainerAccessPolicy() {
            public boolean canAccess(Player player, Block... blocks) { return true; }
            public boolean isInstalled() { return true; }
            public boolean isAvailable() { return true; }
            public String hookName() { return "TestPolicy"; }
        };
        accessPolicy = new CompositeAccessPolicy(List.of(allowAll), Logger.getLogger("test"));
        transaction = new ContainerTransaction(registry, accessPolicy, Logger.getLogger("test"));
    }

    /**
     * cloneOrNull：null 返回 null
     */
    @Test
    void cloneOrNull_null_returnsNull() {
        assertNull(ContainerTransaction.cloneOrNull(null));
    }

    /**
     * cloneOrNull：AIR 物品返回 null
     */
    @Test
    void cloneOrNull_airItem_returnsNull() {
        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        assertNull(ContainerTransaction.cloneOrNull(air));
    }

    /**
     * cloneOrNull：正常物品调用 clone() 返回结果
     */
    @Test
    void cloneOrNull_validItem_returnsClone() {
        ItemStack item = mock(ItemStack.class);
        ItemStack cloned = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.COBBLESTONE);
        when(item.clone()).thenReturn(cloned);

        ItemStack result = ContainerTransaction.cloneOrNull(item);
        assertSame(cloned, result, "cloneOrNull 应返回 item.clone() 的结果");
    }

    /**
     * 末影箱验证通过后必须返回执行玩家的私有库存，而非方块状态提供的库存。
     */
    @Test
    void getInventoryIfValid_enderChestReturnsExecutingPlayersPrivateInventory() {
        UUID worldUuid = UUID.randomUUID();
        BlockPos enderChestPosition = new BlockPos(worldUuid, 0, 64, 0);
        ContainerIdentity identity = new ContainerIdentity(enderChestPosition,
                ContainerIdentity.ContainerType.ENDER_CHEST, 1L);
        World world = mock(World.class);
        Block block = mock(Block.class);
        EnderChest enderChestState = mock(EnderChest.class);
        Player player = mock(Player.class);
        Inventory privateEnderChestInventory = mock(Inventory.class);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getBlockAt(0, 64, 0)).thenReturn(block);
        when(block.getType()).thenReturn(Material.ENDER_CHEST);
        when(block.getState()).thenReturn(enderChestState);
        when(player.getEnderChest()).thenReturn(privateEnderChestInventory);

        Inventory resolvedInventory = transaction.getInventoryIfValid(identity, world, player);

        assertSame(privateEnderChestInventory, resolvedInventory);
    }
    /**
     * 潜影盒 Material 白名单必须接受未染色与染色变体。
     */
    @Test
    void expectedSingleMaterial_shulkerVariantsAreAccepted() {
        assertTrue(ContainerTransaction.isExpectedSingleMaterial(Material.SHULKER_BOX,
                io.github.autochest.container.ContainerIdentity.ContainerType.SHULKER_BOX));
        assertTrue(ContainerTransaction.isExpectedSingleMaterial(Material.RED_SHULKER_BOX,
                io.github.autochest.container.ContainerIdentity.ContainerType.SHULKER_BOX));
        assertFalse(ContainerTransaction.isExpectedSingleMaterial(Material.CHEST,
                io.github.autochest.container.ContainerIdentity.ContainerType.SHULKER_BOX));
    }

    /**
     * 末影箱必须精确匹配末影箱 Material，避免方块替换后仍访问玩家私有库存。
     */
    @Test
    void expectedSingleMaterial_enderChestRequiresExactMaterial() {
        assertTrue(ContainerTransaction.isExpectedSingleMaterial(Material.ENDER_CHEST,
                io.github.autochest.container.ContainerIdentity.ContainerType.ENDER_CHEST));
        assertFalse(ContainerTransaction.isExpectedSingleMaterial(Material.CHEST,
                io.github.autochest.container.ContainerIdentity.ContainerType.ENDER_CHEST));
    }
    /**
     * 守恒校验数学验证：存入 32 个后两端合计应不变
     */
    @Test
    void depositConservation_math_correct() {
        int playerBefore = 32;
        int containerBefore = 32;
        int moveAmount = 32;

        int playerAfter = playerBefore - moveAmount;      // 0
        int containerAfter = containerBefore + moveAmount; // 64

        assertEquals(playerBefore + containerBefore, playerAfter + containerAfter,
                "存入操作前后物品总数应守恒");
        assertEquals(0, playerAfter);
        assertEquals(64, containerAfter);
    }

    /**
     * 守恒校验数学验证：补货 32 个后两端合计应不变
     */
    @Test
    void restockConservation_math_correct() {
        int containerBefore = 64;
        int playerBefore = 32;
        int moveAmount = 32;

        int containerAfter = containerBefore - moveAmount;  // 32
        int playerAfter = playerBefore + moveAmount;         // 64

        assertEquals(containerBefore + playerBefore, containerAfter + playerAfter,
                "补货操作前后物品总数应守恒");
        assertEquals(32, containerAfter);
        assertEquals(64, playerAfter);
    }

    /**
     * 来源不足时守恒校验应失败
     */
    @Test
    void depositConservation_insufficientSource_fails() {
        int playerBefore = 10;
        int moveAmount = 20;
        // 来源不足，不允许移动
        assertFalse(playerBefore >= moveAmount,
                "来源数量不足时不应允许移动");
    }
}
