package io.github.autochest.service;

// 导入容器坐标模型以构造可去重的测试身份喵~
import io.github.autochest.container.BlockPos;
// 导入容器身份模型以验证统计去重语义喵~
import io.github.autochest.container.ContainerIdentity;
// 导入 Bukkit 物品材料枚举以构造真实可堆叠物品喵~
import org.bukkit.Material;
// 导入 Bukkit 玩家接口以模拟来源库存喵~
import org.bukkit.entity.Player;
// 导入 Bukkit 玩家背包接口以满足 Player#getInventory 的精确返回类型喵~
import org.bukkit.inventory.PlayerInventory;
// 导入 Bukkit 容器接口以模拟箱子库存喵~
import org.bukkit.inventory.Inventory;
// 导入 Bukkit 物品堆类型喵~
import org.bukkit.inventory.ItemStack;
// 导入 JUnit 测试注解喵~
import org.junit.jupiter.api.Test;
// 导入 UUID 类型以创建独立世界坐标喵~
import java.util.UUID;
// 导入相等断言工具喵~
import static org.junit.jupiter.api.Assertions.assertEquals;
// 导入空值断言工具喵~
import static org.junit.jupiter.api.Assertions.assertNull;
// 导入 Mockito 静态工具以模拟 Bukkit 运行时对象喵~
import static org.mockito.Mockito.mock;
// 导入 Mockito 静态工具以配置读取返回值喵~
import static org.mockito.Mockito.when;

// 验证存入服务的容器统计与第二阶段智能堆叠语义喵~
class DepositServiceTest {

    // 保存每个模拟物品的独立相似度键，模拟材料与 metadata 共同决定的 isSimilar 语义喵~
    private static final java.util.Map<ItemStack, String> SIMILARITY_KEYS =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    // 验证同一规范容器即使被重复标记也只计入一次完成统计喵~
    @Test
    void markContainerUsed_sameContainerAcrossMutations_countsOnce() {
        // 创建独立世界 UUID，避免测试坐标与其他用例共享状态喵~
        UUID worldId = UUID.randomUUID();
        // 创建首次发现的箱子身份喵~
        ContainerIdentity firstIdentity = new ContainerIdentity(
                new BlockPos(worldId, 12, 64, 8), ContainerIdentity.ContainerType.CHEST, 1L);
        // 创建同坐标但不同距离快照的等价容器身份喵~
        ContainerIdentity equivalentIdentity = new ContainerIdentity(
                new BlockPos(worldId, 12, 64, 8), ContainerIdentity.ContainerType.CHEST, 99L);
        // 创建本次存入任务的可变统计对象喵~
        DepositService.DepositStats stats = new DepositService.DepositStats();

        // 模拟 PlayerBackpack 成功写入目标容器后的统计标记喵~
        stats.markContainerUsed(firstIdentity);
        // 模拟原版来源在另一阶段再次写入同一个容器喵~
        stats.markContainerUsed(equivalentIdentity);

        // 断言同一个规范容器不会被重复统计喵~
        assertEquals(1, stats.containersUsed);
    }

    // 验证空身份不会虚增完成消息中的容器数喵~
    @Test
    void markContainerUsed_nullIdentity_doesNotCountContainer() {
        // 创建本次存入任务的可变统计对象喵~
        DepositService.DepositStats stats = new DepositService.DepositStats();

        // 模拟缺少可验证容器身份的异常路径喵~
        stats.markContainerUsed(null);

        // 断言异常路径不会伪造容器使用记录喵~
        assertEquals(0, stats.containersUsed);
    }

    // 验证第二阶段先填充本轮创建的同类堆叠，再使用下一个空槽喵~
    @Test
    void depositInUseEmptyPhase_mergesLaterSourceIntoNewStackBeforeUsingAnotherEmptySlot() {
        // 创建带完整同类行为的来源物品模拟对象喵~
        ItemStack sourceItem = mockItemStack("cobblestone", 8, 64);
        // 创建模拟玩家以提供玩家背包引用喵~
        Player player = mock(Player.class);
        // 创建模拟玩家背包以维护两个来源槽位喵~
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        // 创建模拟容器库存以维护三个箱子槽位喵~
        Inventory containerInventory = mock(Inventory.class);
        // 创建内存化事务执行器，提交时同步更新模拟库存喵~
        InMemoryDepositTransaction transaction = new InMemoryDepositTransaction(playerInventory, containerInventory);
        // 创建不需要其余依赖的存入服务，直接测试第二阶段辅助逻辑喵~
        DepositService depositService = new DepositService(transaction, null, null, null);
        // 创建累计统计对象以检查实际移动数量喵~
        DepositService.DepositStats stats = new DepositService.DepositStats();

        // 配置玩家接口返回可读写的模拟背包喵~
        when(player.getInventory()).thenReturn(playerInventory);
        // 初始化玩家第一个零散来源为五个圆石喵~
        transaction.setPlayerItem(0, mockItemStack("cobblestone", 5, 64));
        // 初始化玩家第二个零散来源为八个圆石喵~
        transaction.setPlayerItem(1, sourceItem);
        // 初始化容器已有六十个圆石和两个空槽喵~
        transaction.setContainerItem(0, mockItemStack("cobblestone", 60, 64));
        // 显式初始化第一个候选空槽喵~
        transaction.setContainerItem(1, null);
        // 显式初始化第二个候选空槽喵~
        transaction.setContainerItem(2, null);

        // 第一份来源先补满六十个圆石，再将一个剩余圆石放入首个空槽喵~
        DepositService.ContainerOutcome firstOutcome = depositService.depositInUseEmptyPhase(
                player, containerInventory, 0, mockItemStack("cobblestone", 5, 64), stats);
        // 第二份来源必须优先填充刚创建的一格圆石，而不是占用第二个空槽喵~
        DepositService.ContainerOutcome secondOutcome = depositService.depositInUseEmptyPhase(
                player, containerInventory, 1, mockItemStack("cobblestone", 8, 64), stats);

        // 断言两次提交均可继续处理后续容器喵~
        assertEquals(DepositService.ContainerOutcome.CONTINUE, firstOutcome);
        // 断言第二次提交也不会因正常堆叠而中止喵~
        assertEquals(DepositService.ContainerOutcome.CONTINUE, secondOutcome);
        // 断言原有堆叠先被补满喵~
        assertEquals(64, transaction.getContainerItem(0).getAmount());
        // 断言第二份来源合并进本轮创建的一格堆叠，结果为九个喵~
        assertEquals(9, transaction.getContainerItem(1).getAmount());
        // 断言未使用第二个空槽喵~
        assertNull(transaction.getContainerItem(2));
        // 断言两个玩家来源槽均已耗尽喵~
        assertNull(transaction.getPlayerItem(0));
        // 断言第二个玩家来源槽也已耗尽喵~
        assertNull(transaction.getPlayerItem(1));
        // 断言所有十三个圆石都已成功移动喵~
        assertEquals(13, stats.itemsMoved);
    }

    // 验证不相似 metadata 物品不能错误合并到已有同材料堆叠喵~
    @Test
    void depositInUseEmptyPhase_differentMetadata_doesNotMergeIntoExistingStack() {
        // 创建 metadata 身份不同的玩家来源物品喵~
        ItemStack namedSourceItem = mockItemStack("named-cobblestone", 3, 64);
        // 创建模拟玩家以提供玩家背包引用喵~
        Player player = mock(Player.class);
        // 创建模拟玩家背包喵~
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        // 创建模拟容器库存喵~
        Inventory containerInventory = mock(Inventory.class);
        // 创建内存化事务执行器喵~
        InMemoryDepositTransaction transaction = new InMemoryDepositTransaction(playerInventory, containerInventory);
        // 创建不需要其余依赖的存入服务喵~
        DepositService depositService = new DepositService(transaction, null, null, null);
        // 创建累计统计对象喵~
        DepositService.DepositStats stats = new DepositService.DepositStats();

        // 配置玩家接口返回模拟背包喵~
        when(player.getInventory()).thenReturn(playerInventory);
        // 初始化玩家来源物品喵~
        transaction.setPlayerItem(0, namedSourceItem);
        // 初始化容器为普通圆石与一个空槽；普通圆石不应为不同 metadata 来源提供候选合并喵~
        transaction.setContainerItem(0, mockItemStack("plain-cobblestone", 60, 64));
        // 初始化第二个槽为空喵~
        transaction.setContainerItem(1, null);

        // 调用第二阶段辅助逻辑；无相似目标时只应继续，不得写入容器喵~
        DepositService.ContainerOutcome outcome = depositService.depositInUseEmptyPhase(
                player, containerInventory, 0, namedSourceItem, stats);

        // 断言无相似堆叠时方法保持继续状态喵~
        assertEquals(DepositService.ContainerOutcome.CONTINUE, outcome);
        // 断言普通圆石数量保持不变，metadata 不同不能合并喵~
        assertEquals(60, transaction.getContainerItem(0).getAmount());
        // 断言空槽仍然为空，资格检查由调用方负责且本辅助方法不创建不相似堆叠喵~
        assertNull(transaction.getContainerItem(1));
        // 断言玩家来源未被移动喵~
        assertEquals(3, transaction.getPlayerItem(0).getAmount());
        // 断言没有统计任何移动数量喵~
        assertEquals(0, stats.itemsMoved);
    }

    // 创建可独立克隆、比较和计数的模拟 ItemStack，避免依赖 MockBukkit 物品注册表版本喵~
    private static ItemStack mockItemStack(String similarityKey, int amount, int maxStackSize) {
        // 创建独立模拟物品实例喵~
        ItemStack mockItem = mock(ItemStack.class);
        // 保存此实例所属的相似度身份键喵~
        String immutableSimilarityKey = similarityKey;
        // 保存可变数量，供 setAmount 与 clone 后续读写喵~
        java.util.concurrent.atomic.AtomicInteger currentAmount =
                new java.util.concurrent.atomic.AtomicInteger(amount);
        // 配置物品类型为非 AIR，供 cloneOrNull 识别为有效来源或目标喵~
        when(mockItem.getType()).thenReturn(Material.DIRT);
        // 配置当前数量读取喵~
        when(mockItem.getAmount()).thenAnswer(invocation -> currentAmount.get());
        // 配置当前数量更新喵~
        org.mockito.Mockito.doAnswer(invocation -> {
            // 记录调用方设置的最新物品数量喵~
            currentAmount.set(invocation.getArgument(0));
            // void 方法无需返回值喵~
            return null;
        }).when(mockItem).setAmount(org.mockito.ArgumentMatchers.anyInt());
        // 配置堆叠上限读取喵~
        when(mockItem.getMaxStackSize()).thenReturn(maxStackSize);
        // 配置同类判断仅比较显式身份键，模拟 metadata 不同即不相似喵~
        when(mockItem.isSimilar(org.mockito.ArgumentMatchers.any(ItemStack.class)))
                .thenAnswer(invocation -> {
                    // 读取待比较物品喵~
                    ItemStack otherItem = invocation.getArgument(0);
                    // 空物品不能与当前物品相似喵~
                    if (otherItem == null) {
                        // 返回不相似结果喵~
                        return false;
                    }
                    // 比较双方注册的模拟身份键喵~
                    return immutableSimilarityKey.equals(SIMILARITY_KEYS.get(otherItem));
                });
        // 注册当前物品的模拟身份键喵~
        SIMILARITY_KEYS.put(mockItem, immutableSimilarityKey);
        // 创建对当前物品的克隆行为；克隆保留相似度身份、数量与堆叠上限喵~
        when(mockItem.clone()).thenAnswer(invocation ->
                mockItemStack(immutableSimilarityKey, currentAmount.get(), maxStackSize));
        // 返回配置完成的模拟物品喵~
        return mockItem;
    }

    private static final class InMemoryDepositTransaction extends ContainerTransaction {
        // 保存模拟玩家库存引用以识别来源侧读写喵~
        private final PlayerInventory playerInventory;
        // 保存模拟容器库存引用以识别目标侧读写喵~
        private final Inventory containerInventory;
        // 保存玩家槽位的独立物品快照喵~
        private final ItemStack[] playerItems = new ItemStack[36];
        // 保存容器槽位的独立物品快照喵~
        private final ItemStack[] containerItems = new ItemStack[3];

        // 使用两个模拟库存创建可预测的内存事务执行器喵~
        private InMemoryDepositTransaction(PlayerInventory playerInventory, Inventory containerInventory) {
            // 父类构造器在本测试中不会被调用，空依赖足够构造子类喵~
            super(null, null, null);
            // 保存玩家库存身份喵~
            this.playerInventory = playerInventory;
            // 保存容器库存身份喵~
            this.containerInventory = containerInventory;
            // 配置玩家库存大小以允许服务遍历槽位喵~
            when(playerInventory.getSize()).thenReturn(playerItems.length);
            // 配置容器库存大小以允许服务遍历三个箱子槽位喵~
            when(containerInventory.getSize()).thenReturn(containerItems.length);
            // 配置玩家库存读取委托到内存数组喵~
            when(playerInventory.getItem(org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(invocation -> getPlayerItem(invocation.getArgument(0)));
            // 配置容器库存读取委托到内存数组喵~
            when(containerInventory.getItem(org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(invocation -> getContainerItem(invocation.getArgument(0)));
            // 配置容器内容读取委托，供第二阶段相似物品资格检查使用喵~
            when(containerInventory.getContents())
                    .thenAnswer(invocation -> getContainerContents());
        }

        // 覆盖实际 Bukkit 事务，以内存数组模拟成功移动并保留同类校验喵~
        @Override
        public CommitResult commitDeposit(Player player, Inventory inventory,
                                          int playerSlot, int containerSlot, int amount) {
            // 读取当前来源物品快照喵~
            ItemStack sourceItem = getPlayerItem(playerSlot);
            // 读取当前目标物品快照喵~
            ItemStack targetItem = getContainerItem(containerSlot);
            // 喵~防御：非法数量、空来源、目标不相似或容量不足时拒绝提交喵~
            if (amount <= 0 || sourceItem == null || sourceItem.getAmount() < amount
                    || (targetItem != null && !targetItem.isSimilar(sourceItem))
                    || (targetItem != null && targetItem.getAmount() + amount > targetItem.getMaxStackSize())) {
                // 返回未提交结果，模拟真实事务的保守行为喵~
                return CommitResult.skipped();
            }
            // 从来源复制出扣除数量后的物品快照喵~
            ItemStack remainingSourceItem = sourceItem.clone();
            // 扣除本次已移动的数量喵~
            remainingSourceItem.setAmount(sourceItem.getAmount() - amount);
            // 将已耗尽来源规范化为空槽喵~
            setPlayerItem(playerSlot, remainingSourceItem.getAmount() <= 0 ? null : remainingSourceItem);
            // 从目标或来源创建目标写入快照喵~
            ItemStack updatedTargetItem = targetItem == null ? sourceItem.clone() : targetItem.clone();
            // 增加目标槽中的移动数量喵~
            updatedTargetItem.setAmount((targetItem == null ? 0 : targetItem.getAmount()) + amount);
            // 保存更新后的目标物品喵~
            setContainerItem(containerSlot, updatedTargetItem);
            // 返回精确成功结果喵~
            return CommitResult.success(amount);
        }

        // 写入玩家内存槽位并防御性复制非空物品喵~
        private void setPlayerItem(int slot, ItemStack item) {
            // 保存独立克隆，避免测试调用方后续改动泄漏进库存状态喵~
            playerItems[slot] = item == null ? null : item.clone();
        }

        // 写入容器内存槽位并防御性复制非空物品喵~
        private void setContainerItem(int slot, ItemStack item) {
            // 保存独立克隆，避免测试调用方后续改动泄漏进库存状态喵~
            containerItems[slot] = item == null ? null : item.clone();
        }

        // 读取玩家内存槽位并返回独立克隆喵~
        private ItemStack getPlayerItem(int slot) {
            // 返回克隆以模拟 Bukkit Inventory 不暴露本测试内部状态喵~
            return playerItems[slot] == null ? null : playerItems[slot].clone();
        }

        // 读取容器内存槽位并返回独立克隆喵~
        private ItemStack getContainerItem(int slot) {
            // 返回克隆以模拟 Bukkit Inventory 不暴露本测试内部状态喵~
            return containerItems[slot] == null ? null : containerItems[slot].clone();
        }

        // 读取全部容器内存槽位并返回独立克隆数组喵~
        private ItemStack[] getContainerContents() {
            // 创建与容器大小一致的内容副本喵~
            ItemStack[] copiedContents = new ItemStack[containerItems.length];
            // 依序复制每个容器槽位，避免调用方修改内部测试状态喵~
            for (int containerSlot = 0; containerSlot < containerItems.length; containerSlot++) {
                // 复制当前槽位的物品或保留空槽喵~
                copiedContents[containerSlot] = getContainerItem(containerSlot);
            }
            // 返回安全的容器内容快照喵~
            return copiedContents;
        }
    }
}
