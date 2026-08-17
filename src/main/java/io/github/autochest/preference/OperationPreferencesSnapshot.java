package io.github.autochest.preference;

import io.github.autochest.container.ContainerIdentity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 不可变的单操作容器偏好快照。
 * 只保存 Bukkit-free 数据，可安全随玩家任务进入异步规划阶段。
 */
public final class OperationPreferencesSnapshot {

    /** 插件当前支持的容器种类默认顺序。 */
    private static final List<ContainerIdentity.ContainerType> DEFAULT_PRIORITY = List.of(
            ContainerIdentity.ContainerType.CHEST,
            ContainerIdentity.ContainerType.TRAPPED_CHEST,
            ContainerIdentity.ContainerType.BARREL,
            ContainerIdentity.ContainerType.SHULKER_BOX,
            ContainerIdentity.ContainerType.ENDER_CHEST
    );

    /** 本操作使用的稳定容器排序模式。 */
    private ContainerOrderMode orderMode;

    /** 本操作不允许参与扫描和排序的容器种类。 */
    private Set<ContainerIdentity.ContainerType> blacklistedContainerTypes;

    /** 本操作的完整容器种类优先级列表。 */
    private List<ContainerIdentity.ContainerType> containerTypePriority;

    /** 可被配置的玩家背包首个 Bukkit 槽位。 */
    public static final int FIRST_LOCKABLE_INVENTORY_SLOT = 0;

    /** 可被配置的玩家背包最后一个 Bukkit 槽位。 */
    public static final int LAST_LOCKABLE_INVENTORY_SLOT = 35;

    /** 本操作各玩家背包槽位的权限状态。 */
    private Map<Integer, InventorySlotMode> inventorySlotModes;

    /** 兼容旧调用方的二态锁定槽位视图。 */
    private Set<Integer> lockedInventorySlots;

    /**
     * 创建并规范化操作偏好快照。
     *
     * @param orderMode 容器排序模式，可为空。
     * @param blacklistedContainerTypes 黑名单种类，可为空。
     * @param containerTypePriority 玩家配置优先级，可为空。
     */
    public OperationPreferencesSnapshot(ContainerOrderMode orderMode,
                                        Set<ContainerIdentity.ContainerType> blacklistedContainerTypes,
                                        List<ContainerIdentity.ContainerType> containerTypePriority) {
        // 兼容既有调用方：未提供锁定槽位时使用空集合。
        this(orderMode, blacklistedContainerTypes, containerTypePriority, Set.of());
    }

    /**
     * 创建并规范化包含旧版锁定槽位的操作偏好快照。
     *
     * @param orderMode 容器排序模式，可为空。
     * @param blacklistedContainerTypes 黑名单种类，可为空。
     * @param containerTypePriority 玩家配置优先级，可为空。
     * @param lockedInventorySlots 旧版被锁定的玩家背包槽位，可为空。
     */
    public OperationPreferencesSnapshot(ContainerOrderMode orderMode,
                                        Set<ContainerIdentity.ContainerType> blacklistedContainerTypes,
                                        List<ContainerIdentity.ContainerType> containerTypePriority,
                                        Set<Integer> lockedInventorySlots) {
        // 创建旧版锁定槽位转换后的四态映射。
        Map<Integer, InventorySlotMode> migratedModes = new TreeMap<>();
        // 喵~防御：空旧集合不会阻断快照创建。
        if (lockedInventorySlots != null) {
            for (Integer inventorySlot : lockedInventorySlots) {
                // 旧版锁定的合法槽位迁移为仅补货。
                if (inventorySlot != null && isLockableInventorySlot(inventorySlot)) {
                    migratedModes.put(inventorySlot, InventorySlotMode.RESTOCK_ONLY);
                }
            }
        }
        // 复用统一四态构造器完成全部归一化。
        initialize(orderMode, blacklistedContainerTypes, containerTypePriority, migratedModes);
    }

    /**
     * 创建并规范化包含四态玩家背包槽位权限的操作偏好快照。
     *
     * @param orderMode 容器排序模式，可为空。
     * @param blacklistedContainerTypes 黑名单种类，可为空。
     * @param containerTypePriority 玩家配置优先级，可为空。
     * @param inventorySlotModes 槽位权限映射，可为空。
     */
    public OperationPreferencesSnapshot(ContainerOrderMode orderMode,
                                        Set<ContainerIdentity.ContainerType> blacklistedContainerTypes,
                                        List<ContainerIdentity.ContainerType> containerTypePriority,
                                        Map<Integer, InventorySlotMode> inventorySlotModes) {
        // 使用统一初始化路径冻结所有配置。
        initialize(orderMode, blacklistedContainerTypes, containerTypePriority, inventorySlotModes);
    }

    /**
     * 统一归一化并冻结构造器参数。
     *
     * @param orderMode 容器排序模式。
     * @param blacklistedContainerTypes 容器黑名单。
     * @param containerTypePriority 容器优先级。
     * @param configuredSlotModes 玩家背包槽位权限。
     */
    private void initialize(ContainerOrderMode orderMode,
                            Set<ContainerIdentity.ContainerType> blacklistedContainerTypes,
                            List<ContainerIdentity.ContainerType> containerTypePriority,
                            Map<Integer, InventorySlotMode> configuredSlotModes) {
        // 空模式使用距离优先，兼容缺失或损坏的 JSON 字段。
        this.orderMode = orderMode == null ? ContainerOrderMode.DISTANCE : orderMode;
        // 创建独立枚举集合，防止调用方后续修改黑名单影响任务快照。
        EnumSet<ContainerIdentity.ContainerType> normalizedBlacklist = EnumSet.noneOf(ContainerIdentity.ContainerType.class);
        // 仅复制非空的合法黑名单种类。
        if (blacklistedContainerTypes != null) {
            normalizedBlacklist.addAll(blacklistedContainerTypes);
        }
        // 冻结黑名单集合，禁止运行中任务被外部修改。
        this.blacklistedContainerTypes = Set.copyOf(normalizedBlacklist);
        // 规范优先级，去重并补齐所有当前支持的容器种类。
        this.containerTypePriority = List.copyOf(normalizePriority(containerTypePriority));
        // 创建稳定排序映射，仅保存非默认且合法的状态。
        Map<Integer, InventorySlotMode> normalizedSlotModes = new TreeMap<>();
        // 喵~防御：空映射、空键和值及范围外键均不会中断快照创建。
        if (configuredSlotModes != null) {
            for (Map.Entry<Integer, InventorySlotMode> entry : configuredSlotModes.entrySet()) {
                Integer inventorySlot = entry.getKey();
                InventorySlotMode mode = entry.getValue();
                if (inventorySlot != null && mode != null && isLockableInventorySlot(inventorySlot)
                        && mode != InventorySlotMode.ALLOW_BOTH) {
                    normalizedSlotModes.put(inventorySlot, mode);
                }
            }
        }
        // 冻结状态映射，避免 GUI 修改影响已创建任务。
        this.inventorySlotModes = Map.copyOf(normalizedSlotModes);
        // 保留旧 API 视图：无整理权限的槽位视为已锁定。
        this.lockedInventorySlots = normalizedSlotModes.entrySet().stream()
                .filter(entry -> !entry.getValue().allowsDeposit())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 创建默认距离优先快照。
     *
     * @return 默认操作偏好快照。
     */
    public static OperationPreferencesSnapshot defaults() {
        // 默认不排除任何容器并保持旧版距离排序语义。
        return new OperationPreferencesSnapshot(ContainerOrderMode.DISTANCE, Set.of(), DEFAULT_PRIORITY);
    }

    /**
     * 规范玩家给出的优先级列表。
     *
     * @param configuredPriority 玩家配置的列表，可为空。
     * @return 去重、过滤空值并补齐默认种类后的完整列表。
     */
    private static List<ContainerIdentity.ContainerType> normalizePriority(
            List<ContainerIdentity.ContainerType> configuredPriority) {
        // 创建结果列表以保持玩家有效条目的原有相对顺序。
        List<ContainerIdentity.ContainerType> normalizedPriority = new ArrayList<>();
        // 创建集合以消除重复容器种类。
        EnumSet<ContainerIdentity.ContainerType> seenTypes = EnumSet.noneOf(ContainerIdentity.ContainerType.class);
        // 有配置时依次保留首次出现的有效种类。
        if (configuredPriority != null) {
            for (ContainerIdentity.ContainerType containerType : configuredPriority) {
                if (containerType != null && seenTypes.add(containerType)) {
                    normalizedPriority.add(containerType);
                }
            }
        }
        // 将遗漏的当前支持种类按默认顺序追加到列表末尾。
        for (ContainerIdentity.ContainerType defaultType : DEFAULT_PRIORITY) {
            if (seenTypes.add(defaultType)) {
                normalizedPriority.add(defaultType);
            }
        }
        // 返回完整的稳定优先级列表。
        return normalizedPriority;
    }

    /**
     * 判断 Bukkit 玩家背包槽位是否允许被锁定。
     *
     * @param inventorySlot 待检查的玩家背包槽位。
     * @return true 表示槽位属于主背包可整理范围。
     */
    public static boolean isLockableInventorySlot(int inventorySlot) {
        // 仅允许 Deposit 当前实际遍历的主背包槽位范围。
        return inventorySlot >= FIRST_LOCKABLE_INVENTORY_SLOT
                && inventorySlot <= LAST_LOCKABLE_INVENTORY_SLOT;
    }

    /** 判断指定槽位是否允许 deposit 整理。 */
    public boolean allowsDeposit(int inventorySlot) {
        // 非法槽位和未知状态均保守禁止整理。
        return isLockableInventorySlot(inventorySlot)
                && getInventorySlotMode(inventorySlot).allowsDeposit();
    }

    /** 判断指定槽位是否允许 restock 补货。 */
    public boolean allowsRestock(int inventorySlot) {
        // 非法槽位和未知状态均保守禁止补货。
        return isLockableInventorySlot(inventorySlot)
                && getInventorySlotMode(inventorySlot).allowsRestock();
    }

    /** 获取指定槽位的四态权限，缺失配置默认为允许两种操作。 */
    public InventorySlotMode getInventorySlotMode(int inventorySlot) {
        // 缺失的合法槽位使用默认双允许状态。
        return inventorySlotModes.getOrDefault(inventorySlot, InventorySlotMode.ALLOW_BOTH);
    }

    /** 获取不可变的非默认槽位权限映射。 */
    public Map<Integer, InventorySlotMode> getInventorySlotModes() {
        // 返回已经防御性冻结的状态映射。
        return inventorySlotModes;
    }

    /**
     * 判断指定主背包槽位是否已被旧语义锁定。
     *
     * @param inventorySlot 待检查的玩家背包槽位。
     * @return true 表示 deposit 不允许使用该槽位。
     */
    public boolean isLockedInventorySlot(int inventorySlot) {
        // 兼容旧调用，所有不允许 deposit 的状态都视为锁定。
        return isLockableInventorySlot(inventorySlot) && !allowsDeposit(inventorySlot);
    }

    /** 获取兼容旧 API 的锁定槽位集合。 */
    public Set<Integer> getLockedInventorySlots() {
        // 返回已经防御性冻结的旧语义集合。
        return lockedInventorySlots;
    }

    /**
     * 判断容器种类是否允许参与本操作。
     *
     * @param containerType 待检查的容器种类。
     * @return true 表示容器类型未被黑名单排除。
     */
    public boolean allows(ContainerIdentity.ContainerType containerType) {
        // 空类型无法安全归类，保守排除。
        return containerType != null && !blacklistedContainerTypes.contains(containerType);
    }

    /**
     * 获取容器种类在优先级列表中的稳定排名。
     *
     * @param containerType 待查询的容器种类。
     * @return 数字越小表示优先级越高。
     */
    public int priorityRank(ContainerIdentity.ContainerType containerType) {
        // 返回列表下标；未知类型固定排在所有已知类型之后。
        int rank = containerTypePriority.indexOf(containerType);
        return rank < 0 ? containerTypePriority.size() : rank;
    }

    /**
     * 获取本操作使用的排序模式。
     *
     * @return 当前排序模式。
     */
    public ContainerOrderMode getOrderMode() {
        // 返回不可变排序模式。
        return orderMode;
    }

    /**
     * 获取不可变黑名单集合。
     *
     * @return 黑名单容器种类集合。
     */
    public Set<ContainerIdentity.ContainerType> getBlacklistedContainerTypes() {
        // 返回已冻结的黑名单集合。
        return blacklistedContainerTypes;
    }

    /**
     * 获取不可变容器种类优先级列表。
     *
     * @return 完整容器种类优先级列表。
     */
    public List<ContainerIdentity.ContainerType> getContainerTypePriority() {
        // 返回已冻结的优先级列表。
        return containerTypePriority;
    }
}
