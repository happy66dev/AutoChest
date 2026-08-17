package io.github.autochest.gui;

import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.preference.ContainerOrderMode;
import io.github.autochest.preference.InventorySlotMode;
import io.github.autochest.preference.OperationPreferencesSnapshot;
import io.github.autochest.preference.PlayerPreferencesService;
import io.github.autochest.task.OperationType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 玩家容器偏好 GUI 渲染器与点击动作分发器。
 * 所有方法必须由 Bukkit 主线程调用。
 */
public final class PreferencesGui {

    /** 主菜单中 deposit 入口的槽位。 */
    private static final int MAIN_DEPOSIT_SLOT = 11;

    /** 主菜单中 restock 入口的槽位。 */
    private static final int MAIN_RESTOCK_SLOT = 15;

    /** 主菜单中背包槽位权限入口的槽位。 */
    private static final int MAIN_LOCKED_SLOTS_SLOT = 13;

    /** 主菜单中关闭按钮的槽位。 */
    private static final int MAIN_CLOSE_SLOT = 22;

    /** 操作页面中返回主菜单按钮的槽位。 */
    private static final int OPERATION_BACK_SLOT = 0;

    /** 操作页面中排序模式切换按钮的槽位。 */
    private static final int OPERATION_MODE_SLOT = 4;

    /** 操作页面中重置优先级按钮的槽位。 */
    private static final int OPERATION_RESET_SLOT = 8;

    /** 操作页面中关闭按钮的槽位。 */
    private static final int OPERATION_CLOSE_SLOT = 53;

    /** 展示五种容器黑名单状态的槽位。 */
    private static final int[] TYPE_SLOTS = {19, 21, 23, 25, 27};

    /** 展示优先级种类的槽位。 */
    private static final int[] PRIORITY_SLOTS = {36, 39, 42, 45, 48};

    /** 展示上移优先级按钮的槽位。 */
    private static final int[] MOVE_UP_SLOTS = {37, 40, 43, 46, 49};

    /** 展示下移优先级按钮的槽位。 */
    private static final int[] MOVE_DOWN_SLOTS = {38, 41, 44, 47, 50};

    /** 锁定格页面中返回主菜单按钮的槽位。 */
    private static final int LOCKED_SLOTS_BACK_SLOT = 0;

    /** 锁定格页面中关闭按钮的槽位。 */
    private static final int LOCKED_SLOTS_CLOSE_SLOT = 8;

    /** 锁定格页面中主背包预览网格的起始槽位。 */
    private static final int LOCKED_SLOTS_GRID_START = 18;

    /** 槽位权限页面中玩家背包预览槽位数量。 */
    private static final int LOCKED_SLOTS_GRID_SIZE = 36;

    /** 固定显示与配置的五种容器类型。 */
    private static final List<ContainerIdentity.ContainerType> DISPLAY_TYPES = List.of(
            ContainerIdentity.ContainerType.CHEST,
            ContainerIdentity.ContainerType.TRAPPED_CHEST,
            ContainerIdentity.ContainerType.BARREL,
            ContainerIdentity.ContainerType.SHULKER_BOX,
            ContainerIdentity.ContainerType.ENDER_CHEST
    );

    /** 玩家偏好服务，GUI 与文本命令共享同一实例。 */
    private final PlayerPreferencesService preferencesService;

    /** GUI 会话注册表。 */
    private final PreferencesGuiSessionRegistry sessionRegistry;

    /**
     * 创建容器偏好 GUI。
     *
     * @param preferencesService 玩家偏好服务。
     * @param sessionRegistry GUI 会话注册表。
     */
    public PreferencesGui(PlayerPreferencesService preferencesService,
                          PreferencesGuiSessionRegistry sessionRegistry) {
        // 喵~防御：GUI 依赖偏好服务与会话注册表，缺少任一项都无法安全运行。
        if (preferencesService == null || sessionRegistry == null) {
            throw new IllegalArgumentException("玩家偏好服务和 GUI 会话注册表不能为空");
        }
        // 保存共享偏好服务。
        this.preferencesService = preferencesService;
        // 保存会话注册表。
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * 打开 deposit/restock 选择主菜单。
     *
     * @param player 要打开页面的玩家。
     */
    public void openMain(Player player) {
        // 喵~防御：离线或空玩家不能打开库存 GUI。
        if (player == null || !player.isOnline()) {
            return;
        }
        // 为新页面生成令旧页面失效的会话令牌。
        UUID sessionToken = sessionRegistry.begin(player.getUniqueId());
        // 创建专属 Holder，避免使用库存标题识别页面。
        PreferencesGuiHolder holder = new PreferencesGuiHolder(
                player.getUniqueId(), sessionToken, PreferencesGuiHolder.PageType.MAIN, null);
        // 创建三行主菜单库存。
        Inventory inventory = Bukkit.createInventory(holder, 27, "§8AutoChest 容器偏好");
        // 将库存实例绑定给 Holder。
        holder.bindInventory(inventory);
        // 放入 deposit 设置入口。
        inventory.setItem(MAIN_DEPOSIT_SLOT, createItem(Material.CHEST, "§a📥 存入设置",
                List.of("§7配置 deposit 的容器黑名单", "§7排序模式与种类优先级", "§e点击打开")));
        // 放入 restock 设置入口。
        inventory.setItem(MAIN_RESTOCK_SLOT, createItem(Material.ENDER_CHEST, "§b📤 补货设置",
                List.of("§7配置 restock 的容器黑名单", "§7排序模式与种类优先级", "§e点击打开")));
        // 放入同时控制整理和补货的背包槽位权限入口。
        inventory.setItem(MAIN_LOCKED_SLOTS_SLOT, createItem(Material.TRIPWIRE_HOOK, "§e🎛 槽位权限",
                List.of("§7配置快捷栏与主背包格", "§7整理和补货的四态权限", "§e点击打开")));
        // 放入关闭按钮。
        inventory.setItem(MAIN_CLOSE_SLOT, createItem(Material.BARRIER, "§c关闭菜单",
                List.of("§7不修改任何配置")));
        // 打开渲染完成的主菜单。
        player.openInventory(inventory);
    }

    /**
     * 打开指定操作的独立偏好页面。
     *
     * @param player 要打开页面的玩家。
     * @param operation 要配置的操作类型。
     */
    public void openOperation(Player player, OperationType operation) {
        // 喵~防御：空、离线玩家或未知操作不能打开配置页面。
        if (player == null || !player.isOnline() || operation == null) {
            return;
        }
        // 获取此操作的最新不可变偏好快照。
        OperationPreferencesSnapshot snapshot = preferencesService.snapshot(player.getUniqueId(), operation);
        // 创建新会话使之前任意页面失效。
        UUID sessionToken = sessionRegistry.begin(player.getUniqueId());
        // 创建携带操作类型的专属 Holder。
        PreferencesGuiHolder holder = new PreferencesGuiHolder(
                player.getUniqueId(), sessionToken, PreferencesGuiHolder.PageType.OPERATION, operation);
        // 创建六行操作配置库存。
        Inventory inventory = Bukkit.createInventory(holder, 54,
                operation == OperationType.DEPOSIT ? "§8AutoChest 存入设置" : "§8AutoChest 补货设置");
        // 绑定库存给 Holder。
        holder.bindInventory(inventory);
        // 绘制操作页面全部组件。
        renderOperation(inventory, operation, snapshot);
        // 打开完成后的配置页面。
        player.openInventory(inventory);
    }

    /**
     * 打开仅影响 deposit 的主背包锁定格配置页面。
     *
     * @param player 要打开页面的玩家。
     */
    public void openLockedInventorySlots(Player player) {
        // 喵~防御：离线或空玩家不能打开库存 GUI。
        if (player == null || !player.isOnline()) {
            return;
        }
        // 获取 deposit 的最新锁定槽位快照。
        OperationPreferencesSnapshot snapshot = preferencesService.snapshot(
                player.getUniqueId(), OperationType.DEPOSIT);
        // 创建新会话使之前任意页面失效。
        UUID sessionToken = sessionRegistry.begin(player.getUniqueId());
        // 创建专属 Holder，锁定页不需要操作类型上下文。
        PreferencesGuiHolder holder = new PreferencesGuiHolder(player.getUniqueId(), sessionToken,
                PreferencesGuiHolder.PageType.LOCKED_INVENTORY_SLOTS, null);
        // 创建六行库存，为控制栏和完整 36 格背包预览留出独立空间。
        Inventory inventory = Bukkit.createInventory(holder, 54, "§8AutoChest 槽位权限");
        // 将库存实例绑定给 Holder。
        holder.bindInventory(inventory);
        // 绘制槽位权限控制栏与完整玩家背包预览。
        renderLockedInventorySlots(inventory, player, snapshot);
        // 打开完成后的锁定格页面。
        player.openInventory(inventory);
    }

    /**
     * 处理通过安全校验后的 GUI 顶部槽位点击。
     *
     * @param player 点击玩家。
     * @param holder 当前顶部库存 Holder。
     * @param rawSlot 顶部库存原始槽位。
     */
    public void handleTopClick(Player player, PreferencesGuiHolder holder, int rawSlot) {
        // 喵~防御：无效会话或负槽位绝不修改偏好。
        if (player == null || holder == null || rawSlot < 0
                || !sessionRegistry.isCurrent(player.getUniqueId(), holder.getSessionToken())) {
            return;
        }
        // 主菜单根据入口槽位切换到对应操作页面。
        if (holder.getPageType() == PreferencesGuiHolder.PageType.MAIN) {
            handleMainClick(player, rawSlot);
            return;
        }
        // 锁定格页面处理主背包预览与控制按钮。
        if (holder.getPageType() == PreferencesGuiHolder.PageType.LOCKED_INVENTORY_SLOTS) {
            handleLockedInventorySlotsClick(player, rawSlot);
            return;
        }
        // 操作页面按操作类型处理配置修改。
        handleOperationClick(player, holder.getOperation(), rawSlot);
    }

    /** 处理主菜单按钮。 */
    private void handleMainClick(Player player, int rawSlot) {
        // deposit 入口打开存入配置页。
        if (rawSlot == MAIN_DEPOSIT_SLOT) {
            openOperation(player, OperationType.DEPOSIT);
            return;
        }
        // restock 入口打开补货配置页。
        if (rawSlot == MAIN_RESTOCK_SLOT) {
            openOperation(player, OperationType.RESTOCK);
            return;
        }
        // 锁定格入口打开 deposit 专属主背包锁定页。
        if (rawSlot == MAIN_LOCKED_SLOTS_SLOT) {
            openLockedInventorySlots(player);
            return;
        }
        // 关闭按钮关闭库存。
        if (rawSlot == MAIN_CLOSE_SLOT) {
            player.closeInventory();
        }
    }

    /** 处理锁定格页面按钮与主背包预览格。 */
    private void handleLockedInventorySlotsClick(Player player, int rawSlot) {
        // 返回按钮打开新主菜单会话。
        if (rawSlot == LOCKED_SLOTS_BACK_SLOT) {
            openMain(player);
            return;
        }
        // 关闭按钮关闭当前库存。
        if (rawSlot == LOCKED_SLOTS_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        // 将有效预览 raw slot 映射为主背包 Bukkit 槽位。
        int inventorySlot = lockedInventorySlotAt(rawSlot);
        // 喵~防御：控制栏与网格外点击不能改变锁定配置。
        if (inventorySlot < 0) {
            return;
        }
        // 获取最新的共享槽位权限快照，保证页面显示两个操作的真实状态。
        OperationPreferencesSnapshot snapshot = preferencesService.snapshot(
                player.getUniqueId(), OperationType.DEPOSIT);
        // 获取当前槽位状态并轮换到下一个四态。
        InventorySlotMode nextMode = snapshot.getInventorySlotMode(inventorySlot).next();
        // 持久化新的共享槽位权限。
        preferencesService.setInventorySlotMode(player.getUniqueId(), inventorySlot, nextMode);
        // 新页面生成 token，确保旧点击无法继续修改配置。
        openLockedInventorySlots(player);
    }

    /** 将锁定页预览 raw slot 转换为玩家主背包 Bukkit 槽位。 */
    private int lockedInventorySlotAt(int rawSlot) {
        // 计算预览网格末尾的排他槽位。
        int gridEndExclusive = LOCKED_SLOTS_GRID_START + LOCKED_SLOTS_GRID_SIZE;
        // 喵~防御：仅连续 27 格预览区域能对应玩家主背包。
        if (rawSlot < LOCKED_SLOTS_GRID_START || rawSlot >= gridEndExclusive) {
            return -1;
        }
        // 将 GUI 相对索引映射到 Bukkit 玩家背包的完整 0..35 范围。
        return OperationPreferencesSnapshot.FIRST_LOCKABLE_INVENTORY_SLOT
                + rawSlot - LOCKED_SLOTS_GRID_START;
    }

    /** 处理操作页面按钮。 */
    private void handleOperationClick(Player player, OperationType operation, int rawSlot) {
        // 喵~防御：操作页面缺少操作类型时不执行任何修改。
        if (operation == null) {
            return;
        }
        // 返回按钮打开新主菜单会话。
        if (rawSlot == OPERATION_BACK_SLOT) {
            openMain(player);
            return;
        }
        // 关闭按钮关闭当前库存。
        if (rawSlot == OPERATION_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        // 排序按钮在两种合法模式之间切换。
        if (rawSlot == OPERATION_MODE_SLOT) {
            OperationPreferencesSnapshot snapshot = preferencesService.snapshot(player.getUniqueId(), operation);
            ContainerOrderMode nextMode = snapshot.getOrderMode() == ContainerOrderMode.DISTANCE
                    ? ContainerOrderMode.CONTAINER_PRIORITY : ContainerOrderMode.DISTANCE;
            preferencesService.setOrderMode(player.getUniqueId(), operation, nextMode);
            openOperation(player, operation);
            return;
        }
        // 重置按钮只重置优先级，不触碰黑名单或排序模式。
        if (rawSlot == OPERATION_RESET_SLOT) {
            preferencesService.resetPriority(player.getUniqueId(), operation);
            openOperation(player, operation);
            return;
        }
        // 处理五种容器黑名单切换按钮。
        for (int index = 0; index < TYPE_SLOTS.length; index++) {
            if (rawSlot == TYPE_SLOTS[index]) {
                ContainerIdentity.ContainerType containerType = DISPLAY_TYPES.get(index);
                OperationPreferencesSnapshot snapshot = preferencesService.snapshot(player.getUniqueId(), operation);
                preferencesService.setBlacklisted(player.getUniqueId(), operation, containerType,
                        snapshot.allows(containerType));
                openOperation(player, operation);
                return;
            }
        }
        // 处理优先级上移按钮。
        for (int index = 0; index < MOVE_UP_SLOTS.length; index++) {
            if (rawSlot == MOVE_UP_SLOTS[index]) {
                ContainerIdentity.ContainerType containerType = priorityTypeAt(player, operation, index);
                if (containerType != null) {
                    preferencesService.movePriority(player.getUniqueId(), operation, containerType, true);
                    openOperation(player, operation);
                }
                return;
            }
        }
        // 处理优先级下移按钮。
        for (int index = 0; index < MOVE_DOWN_SLOTS.length; index++) {
            if (rawSlot == MOVE_DOWN_SLOTS[index]) {
                ContainerIdentity.ContainerType containerType = priorityTypeAt(player, operation, index);
                if (containerType != null) {
                    preferencesService.movePriority(player.getUniqueId(), operation, containerType, false);
                    openOperation(player, operation);
                }
                return;
            }
        }
    }

    /** 返回当前优先级索引处的容器类型。 */
    private ContainerIdentity.ContainerType priorityTypeAt(Player player, OperationType operation, int index) {
        // 获取当前最新完整优先级列表。
        List<ContainerIdentity.ContainerType> priority = preferencesService.snapshot(
                player.getUniqueId(), operation).getContainerTypePriority();
        // 喵~防御：索引越界时不尝试移动任何容器。
        if (index < 0 || index >= priority.size()) {
            return null;
        }
        // 返回当前位置种类。
        return priority.get(index);
    }

    /**
     * 渲染锁定格控制栏和玩家主背包预览。
     *
     * @param inventory 锁定格 GUI 库存。
     * @param player 当前玩家。
     * @param snapshot 最新 deposit 偏好快照。
     */
    private void renderLockedInventorySlots(Inventory inventory, Player player,
                                            OperationPreferencesSnapshot snapshot) {
        // 放置返回主菜单按钮。
        inventory.setItem(LOCKED_SLOTS_BACK_SLOT, createItem(Material.ARROW, "§e← 返回主菜单",
                List.of("§7返回全部配置入口")));
        // 放置关闭按钮。
        inventory.setItem(LOCKED_SLOTS_CLOSE_SLOT, createItem(Material.BARRIER, "§c关闭菜单",
                List.of("§7配置已自动进入保存队列")));
        // 为每个 0..35 玩家背包槽位创建独立的展示副本。
        for (int offset = 0; offset < LOCKED_SLOTS_GRID_SIZE; offset++) {
            // 将网格相对位置转换为真实玩家背包槽位。
            int inventorySlot = OperationPreferencesSnapshot.FIRST_LOCKABLE_INVENTORY_SLOT + offset;
            // 将网格相对位置转换为顶部库存原始槽位。
            int displaySlot = LOCKED_SLOTS_GRID_START + offset;
            // 读取玩家当前槽位并克隆，禁止 GUI 与实际背包共享 ItemStack 引用。
            ItemStack playerItem = player.getInventory().getItem(inventorySlot);
            // 获取当前四态权限用于渲染。
            InventorySlotMode mode = snapshot.getInventorySlotMode(inventorySlot);
            // 将状态化展示物品放入独立 GUI 槽位。
            inventory.setItem(displaySlot, createLockedInventorySlotItem(playerItem, inventorySlot, mode));
        }
    }

    /**
     * 创建主背包锁定格的状态化展示物品。
     *
     * @param playerItem 当前玩家背包物品，可为空。
     * @param inventorySlot 对应 Bukkit 玩家背包槽位。
     * @param locked 当前是否锁定。
     * @return 仅用于 GUI 展示的独立物品副本。
     */
    private ItemStack createLockedInventorySlotItem(ItemStack playerItem, int inventorySlot,
                                                    InventorySlotMode mode) {
        // 空槽按四态使用不同颜色玻璃板占位，确保空槽也能配置。
        ItemStack displayItem = playerItem == null || playerItem.getType().isAir()
                ? new ItemStack(materialForSlotMode(mode))
                : playerItem.clone();
        // 获取展示副本元数据。
        ItemMeta meta = displayItem.getItemMeta();
        // 喵~防御：无元数据材料仍可展示物品，但不尝试写入状态文本。
        if (meta == null) {
            return displayItem;
        }
        // 空槽使用状态名称，非空槽保留物品名称并在 lore 标记状态。
        if (playerItem == null || playerItem.getType().isAir()) {
            meta.setDisplayName(slotModeColor(mode) + mode.displayName() + "空槽");
        }
        // 创建不复用原物品 lore 的状态说明。
        List<String> lore = new ArrayList<>();
        // 说明真实 Bukkit 槽位，便于玩家确认映射关系。
        lore.add("§7背包槽位: " + inventorySlot);
        // 明确显示当前四态权限。
        lore.add(slotModeColor(mode) + "状态: " + mode.displayName());
        // 显示整理权限，避免玩家依赖颜色猜测。
        lore.add(mode.allowsDeposit() ? "§a整理: 允许" : "§c整理: 禁止");
        // 显示补货权限，避免玩家依赖颜色猜测。
        lore.add(mode.allowsRestock() ? "§a补货: 允许" : "§c补货: 禁止");
        // 提示玩家点击可按固定顺序轮换状态。
        lore.add("§e点击切换到: " + mode.next().displayName());
        // 设置状态说明，避免将玩家物品原 lore 当作配置来源。
        meta.setLore(lore);
        // 隐藏默认属性，保持 GUI 展示整洁。
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        // 应用修改后的展示元数据。
        displayItem.setItemMeta(meta);
        // 返回只属于 GUI 的物品副本。
        return displayItem;
    }

    /** 根据四态返回空槽占位材料。 */
    private Material materialForSlotMode(InventorySlotMode mode) {
        // 空状态或双允许状态使用绿色，保证渲染异常时保守显示默认状态。
        if (mode == null || mode == InventorySlotMode.ALLOW_BOTH) {
            return Material.LIME_STAINED_GLASS_PANE;
        }
        // 仅整理使用黄色便于与双允许区分。
        if (mode == InventorySlotMode.DEPOSIT_ONLY) {
            return Material.YELLOW_STAINED_GLASS_PANE;
        }
        // 仅补货使用蓝色与旧锁定迁移含义保持直观。
        if (mode == InventorySlotMode.RESTOCK_ONLY) {
            return Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        }
        // 完全禁用使用红色明确提醒。
        return Material.RED_STAINED_GLASS_PANE;
    }

    /** 根据四态返回 lore 与空槽名称的颜色。 */
    private String slotModeColor(InventorySlotMode mode) {
        // 双允许使用绿色。
        if (mode == null || mode == InventorySlotMode.ALLOW_BOTH) {
            return "§a";
        }
        // 仅整理使用黄色。
        if (mode == InventorySlotMode.DEPOSIT_ONLY) {
            return "§e";
        }
        // 仅补货使用蓝色。
        if (mode == InventorySlotMode.RESTOCK_ONLY) {
            return "§b";
        }
        // 完全禁用使用红色。
        return "§c";
    }

    /** 渲染单操作配置页面。 */
    private void renderOperation(Inventory inventory, OperationType operation, OperationPreferencesSnapshot snapshot) {
        // 放置返回主菜单按钮。
        inventory.setItem(OPERATION_BACK_SLOT, createItem(Material.ARROW, "§e← 返回主菜单",
                List.of("§7返回存入与补货选择")));
        // 放置当前排序模式切换按钮。
        inventory.setItem(OPERATION_MODE_SLOT, createItem(Material.COMPARATOR, "§e排序模式: "
                + modeDisplayName(snapshot.getOrderMode()), List.of(
                "§7距离优先：所有容器按距离处理", "§7容器优先：先按种类优先级处理", "§e点击切换模式")));
        // 放置只重置当前操作优先级的按钮。
        inventory.setItem(OPERATION_RESET_SLOT, createItem(Material.CLOCK, "§c重置优先级",
                List.of("§7只恢复默认容器种类顺序", "§7不会修改模式或黑名单", "§e点击重置")));
        // 放置容器黑名单开关按钮。
        for (int index = 0; index < DISPLAY_TYPES.size(); index++) {
            ContainerIdentity.ContainerType type = DISPLAY_TYPES.get(index);
            boolean allowed = snapshot.allows(type);
            String state = allowed ? "§a已启用" : "§c已排除";
            inventory.setItem(TYPE_SLOTS[index], createItem(materialFor(type), displayName(type), List.of(
                    "§7当前状态: " + state,
                    "§7点击切换该容器种类黑名单")));
        }
        // 放置优先级列表和每项相邻移动按钮。
        List<ContainerIdentity.ContainerType> priority = snapshot.getContainerTypePriority();
        for (int index = 0; index < DISPLAY_TYPES.size(); index++) {
            ContainerIdentity.ContainerType type = index < priority.size() ? priority.get(index) : null;
            // 喵~防御：配置归一化异常时跳过缺失优先级行。
            if (type == null) {
                continue;
            }
            inventory.setItem(MOVE_UP_SLOTS[index], createItem(Material.LIME_STAINED_GLASS_PANE,
                    "§a↑ 上移", List.of("§7上移 " + displayName(type))));
            inventory.setItem(MOVE_DOWN_SLOTS[index], createItem(Material.RED_STAINED_GLASS_PANE,
                    "§c↓ 下移", List.of("§7下移 " + displayName(type))));
            inventory.setItem(PRIORITY_SLOTS[index], createItem(materialFor(type), "§e" + (index + 1)
                    + ". " + displayName(type), List.of("§7当前优先级位置: " + (index + 1))));
        }
        // 放置关闭按钮。
        inventory.setItem(OPERATION_CLOSE_SLOT, createItem(Material.BARRIER, "§c关闭菜单",
                List.of("§7配置已自动进入保存队列")));
    }

    /** 创建带名称与 lore 的展示物品。 */
    private ItemStack createItem(Material material, String name, List<String> lore) {
        // 创建不包含玩家实际物品数据的 GUI 展示 ItemStack。
        ItemStack item = new ItemStack(material);
        // 获取物品元数据。
        ItemMeta meta = item.getItemMeta();
        // 喵~防御：少数异常材料可能没有元数据，直接返回原物品以避免 GUI 崩溃。
        if (meta == null) {
            return item;
        }
        // 设置显示名称。
        meta.setDisplayName(name);
        // 复制 lore，防止调用方后续修改集合影响展示物品。
        meta.setLore(new ArrayList<>(lore));
        // 隐藏默认属性，保持 GUI 图标整洁。
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        // 应用元数据。
        item.setItemMeta(meta);
        // 返回渲染物品。
        return item;
    }

    /** 返回排序模式的中文显示名称。 */
    private String modeDisplayName(ContainerOrderMode orderMode) {
        // 容器优先模式显示对应中文文本。
        if (orderMode == ContainerOrderMode.CONTAINER_PRIORITY) {
            return "容器优先";
        }
        // 其他或空模式统一显示距离优先。
        return "距离优先";
    }

    /** 返回容器种类中文名称。 */
    private String displayName(ContainerIdentity.ContainerType type) {
        // 按统一容器枚举返回人类可读名称。
        return switch (type) {
            case CHEST -> "普通箱子";
            case TRAPPED_CHEST -> "陷阱箱";
            case BARREL -> "木桶";
            case SHULKER_BOX -> "潜影盒";
            case ENDER_CHEST -> "末影箱";
        };
    }

    /** 返回容器种类对应的 GUI 图标材料。 */
    private Material materialFor(ContainerIdentity.ContainerType type) {
        // 按容器种类返回不含玩家数据的原版图标材料。
        return switch (type) {
            case CHEST -> Material.CHEST;
            case TRAPPED_CHEST -> Material.TRAPPED_CHEST;
            case BARREL -> Material.BARREL;
            case SHULKER_BOX -> Material.SHULKER_BOX;
            case ENDER_CHEST -> Material.ENDER_CHEST;
        };
    }
}
