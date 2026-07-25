package io.github.autochest.gui;

import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.preference.ContainerOrderMode;
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
        // 关闭按钮关闭库存。
        if (rawSlot == MAIN_CLOSE_SLOT) {
            player.closeInventory();
        }
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
