package io.github.autochest.preference;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.task.OperationType;

import java.math.BigDecimal;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 玩家容器偏好服务。
 * 负责主线程内存偏好、JSON 容错加载和独立单线程原子持久化。
 */
public final class PlayerPreferencesService {

    /** 当前 JSON 数据格式版本。 */
    private static final int SCHEMA_VERSION = 1;

    /** 玩家 JSON 文件的根目录。 */
    private final Path playersDirectory;

    /** 用于 JSON 序列化的 Gson 实例。 */
    private final Gson gson = new Gson();

    /** 缓存已加载玩家的可变偏好模型，只能在主线程修改。 */
    private final Map<UUID, PlayerPreferences> loadedPreferences = new HashMap<>();

    /** 单线程持久化执行器，保证同一玩家更新按提交顺序落盘。 */
    private final ExecutorService persistenceExecutor;

    /** 用于记录加载、写入和关闭异常的日志器。 */
    private final Logger logger;

    /** 是否已开始关闭，关闭后拒绝新的配置更新。 */
    private boolean closing;

    /**
     * 创建玩家偏好服务。
     *
     * @param dataDirectory 插件 data 目录。
     * @param logger 插件日志器。
     */
    public PlayerPreferencesService(Path dataDirectory, Logger logger) {
        // 将玩家文件固定放入 data/players 子目录。
        this.playersDirectory = dataDirectory.resolve("players");
        // 保存日志器，供后台线程报告文件系统异常。
        this.logger = logger;
        // 创建独立单线程，禁止与库存规划线程池共享队列或职责。
        this.persistenceExecutor = Executors.newSingleThreadExecutor(runnable -> {
            // 为偏好持久化线程设置可识别的名称。
            Thread thread = new Thread(runnable, "AutoChest-PlayerData");
            // 后台线程不得阻止服务器 JVM 关闭。
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 返回指定玩家、指定操作的不可变偏好快照。
     * 首次访问同步容错加载，确保玩家命令不会依据错误默认值意外执行。
     *
     * @param playerUuid 玩家 UUID。
     * @param operation 操作类型。
     * @return 不可变操作偏好快照。
     */
    public OperationPreferencesSnapshot snapshot(UUID playerUuid, OperationType operation) {
        // 喵~防御：空 UUID 或操作使用默认快照，避免配置系统中断业务命令。
        if (playerUuid == null || operation == null) {
            return OperationPreferencesSnapshot.defaults();
        }
        // 按需加载玩家偏好，之后仅从内存读取。
        PlayerPreferences preferences = loadedPreferences.computeIfAbsent(playerUuid, this::loadPreferences);
        // 返回操作专属的新不可变快照。
        return preferences.snapshot(operation);
    }

    /**
     * 更新排序模式并排队持久化。
     *
     * @param playerUuid 玩家 UUID。
     * @param operation 操作类型。
     * @param orderMode 新排序模式。
     * @return true 表示更新已应用到内存并进入保存队列。
     */
    public boolean setOrderMode(UUID playerUuid, OperationType operation, ContainerOrderMode orderMode) {
        // 喵~防御：关闭、空参数或空模式时不修改内存和磁盘。
        if (closing || playerUuid == null || operation == null || orderMode == null) {
            return false;
        }
        // 获取当前玩家可变偏好模型。
        PlayerPreferences preferences = loadedPreferences.computeIfAbsent(playerUuid, this::loadPreferences);
        // 修改当前操作独立 profile 的排序模式。
        preferences.profile(operation).orderMode = orderMode;
        // 提交最新完整偏好快照，防止只写局部字段造成数据丢失。
        queueSave(playerUuid, preferences.copy());
        // 表示内存更新已生效。
        return true;
    }

    /**
     * 向指定操作黑名单添加或移除容器种类。
     *
     * @param playerUuid 玩家 UUID。
     * @param operation 操作类型。
     * @param containerType 容器种类。
     * @param blacklisted true 表示添加，false 表示移除。
     * @return true 表示黑名单状态发生实际改变。
     */
    public boolean setBlacklisted(UUID playerUuid, OperationType operation,
                                  ContainerIdentity.ContainerType containerType, boolean blacklisted) {
        // 喵~防御：无效参数或关闭中时拒绝修改。
        if (closing || playerUuid == null || operation == null || containerType == null) {
            return false;
        }
        // 获取本玩家的已加载偏好。
        PlayerPreferences preferences = loadedPreferences.computeIfAbsent(playerUuid, this::loadPreferences);
        // 获取当前操作独立 profile。
        MutableProfile profile = preferences.profile(operation);
        // 按请求添加或移除黑名单种类。
        boolean changed = blacklisted
                ? profile.blacklistedContainerTypes.add(containerType)
                : profile.blacklistedContainerTypes.remove(containerType);
        // 仅在实际变化时排队写入，减少无意义磁盘操作。
        if (changed) {
            queueSave(playerUuid, preferences.copy());
        }
        // 返回本次状态是否变化。
        return changed;
    }

    /**
     * 移动当前操作的容器种类优先级。
     *
     * @param playerUuid 玩家 UUID。
     * @param operation 操作类型。
     * @param containerType 待移动容器种类。
     * @param moveUp true 向前移动，false 向后移动。
     * @return true 表示优先级发生实际变化。
     */
    public boolean movePriority(UUID playerUuid, OperationType operation,
                                ContainerIdentity.ContainerType containerType, boolean moveUp) {
        // 喵~防御：关闭或无效参数时不改变排序。
        if (closing || playerUuid == null || operation == null || containerType == null) {
            return false;
        }
        // 获取当前玩家偏好。
        PlayerPreferences preferences = loadedPreferences.computeIfAbsent(playerUuid, this::loadPreferences);
        // 获取当前操作独立 profile。
        MutableProfile profile = preferences.profile(operation);
        // 查询种类在完整规范列表中的当前位置。
        int currentIndex = profile.containerTypePriority.indexOf(containerType);
        // 喵~防御：找不到种类时不改变配置。
        if (currentIndex < 0) {
            return false;
        }
        // 根据方向计算相邻目标位置。
        int targetIndex = moveUp ? currentIndex - 1 : currentIndex + 1;
        // 边界移动不修改列表，也不触发磁盘写入。
        if (targetIndex < 0 || targetIndex >= profile.containerTypePriority.size()) {
            return false;
        }
        // 交换相邻元素，保持列表中类型不丢失。
        ContainerIdentity.ContainerType displacedType = profile.containerTypePriority.get(targetIndex);
        profile.containerTypePriority.set(targetIndex, containerType);
        profile.containerTypePriority.set(currentIndex, displacedType);
        // 写入完整玩家偏好快照。
        queueSave(playerUuid, preferences.copy());
        // 表示移动已成功。
        return true;
    }

    /**
     * 重置指定操作的容器种类优先级列表。
     *
     * @param playerUuid 玩家 UUID。
     * @param operation 操作类型。
     * @return true 表示重置已完成。
     */
    public boolean resetPriority(UUID playerUuid, OperationType operation) {
        // 喵~防御：关闭或无效参数时拒绝重置。
        if (closing || playerUuid == null || operation == null) {
            return false;
        }
        // 获取当前玩家偏好。
        PlayerPreferences preferences = loadedPreferences.computeIfAbsent(playerUuid, this::loadPreferences);
        // 获取当前操作 profile。
        MutableProfile profile = preferences.profile(operation);
        // 使用默认快照生成完整默认优先级顺序。
        profile.containerTypePriority = new ArrayList<>(OperationPreferencesSnapshot.defaults().getContainerTypePriority());
        // 排队保存最新完整偏好。
        queueSave(playerUuid, preferences.copy());
        // 返回重置成功。
        return true;
    }

    /**
     * 设置玩家背包槽位的四态操作权限并排队持久化。
     *
     * @param playerUuid 玩家 UUID。
     * @param inventorySlot Bukkit 玩家背包槽位，范围为 0..35。
     * @param mode 新的四态操作权限。
     * @return true 表示权限状态发生实际变化。
     */
    public boolean setInventorySlotMode(UUID playerUuid, int inventorySlot, InventorySlotMode mode) {
        // 喵~防御：关闭、空 UUID、空状态或范围外槽位时不修改内存和磁盘。
        if (closing || playerUuid == null || mode == null
                || !OperationPreferencesSnapshot.isLockableInventorySlot(inventorySlot)) {
            return false;
        }
        // 获取当前玩家偏好模型。
        PlayerPreferences preferences = loadedPreferences.computeIfAbsent(playerUuid, this::loadPreferences);
        // 缺省双允许状态不占用持久化映射，减少 JSON 噪声。
        InventorySlotMode previousMode = preferences.inventorySlotModes.getOrDefault(
                inventorySlot, InventorySlotMode.ALLOW_BOTH);
        // 状态未变化时不创建无意义写入。
        if (previousMode == mode) {
            return false;
        }
        // 默认状态移除显式配置，其他状态写入共享玩家级映射。
        if (mode == InventorySlotMode.ALLOW_BOTH) {
            preferences.inventorySlotModes.remove(inventorySlot);
        } else {
            preferences.inventorySlotModes.put(inventorySlot, mode);
        }
        // 保存完整偏好副本，确保两项操作读取同一份槽位权限。
        queueSave(playerUuid, preferences.copy());
        // 表示内存更新已生效。
        return true;
    }

    /**
     * 设置旧版锁定格状态并迁移为仅补货权限。
     *
     * @param playerUuid 玩家 UUID。
     * @param inventorySlot 玩家背包 Bukkit 槽位。
     * @param locked true 表示仅补货，false 表示允许两种操作。
     * @return true 表示权限状态发生实际变化。
     */
    public boolean setLockedInventorySlot(UUID playerUuid, int inventorySlot, boolean locked) {
        // 将旧二态 API 委托给四态权限 API，保留已有调用方兼容性。
        return setInventorySlotMode(playerUuid, inventorySlot,
                locked ? InventorySlotMode.RESTOCK_ONLY : InventorySlotMode.ALLOW_BOTH);
    }

    /**
     * 读取并容错解析单个玩家 JSON 文件。
     *
     * @param playerUuid 玩家 UUID。
     * @return 已规范化的玩家偏好模型。
     */
    private PlayerPreferences loadPreferences(UUID playerUuid) {
        // 构建该玩家的独立 JSON 文件路径。
        Path playerFile = playerFile(playerUuid);
        // 文件不存在时直接返回默认配置，不创建空文件。
        if (!Files.isRegularFile(playerFile)) {
            return PlayerPreferences.defaults();
        }
        try {
            // 读取 UTF-8 JSON 内容。
            String json = Files.readString(playerFile, StandardCharsets.UTF_8);
            // 解析 JSON 根元素。
            JsonElement rootElement = JsonParser.parseString(json);
            // 喵~防御：根节点不是对象时使用默认配置。
            if (!rootElement.isJsonObject()) {
                logger.warning("[AutoChest] 玩家偏好 JSON 根节点无效，使用默认配置: " + playerFile);
                return PlayerPreferences.defaults();
            }
            // 将 JSON 对象转换为规范内存模型。
            return parsePreferences(rootElement.getAsJsonObject());
        } catch (Exception exception) {
            // 喵~防御：损坏或不可读 JSON 不阻断玩家命令，记录后回退默认。
            logger.warning("[AutoChest] 读取玩家偏好失败，使用默认配置: " + playerFile + " - " + exception.getMessage());
            return PlayerPreferences.defaults();
        }
    }

    /**
     * 从 JSON 解析完整玩家偏好。
     *
     * @param root JSON 根对象。
     * @return 规范化后的玩家偏好。
     */
    private PlayerPreferences parsePreferences(JsonObject root) {
        // 解析共享玩家背包四态权限，并兼容旧版 deposit 锁定槽位。
        Map<Integer, InventorySlotMode> inventorySlotModes = parseInventorySlotModes(
                root.getAsJsonObject("inventorySlotModes"), root.getAsJsonObject("deposit"));
        // 两项操作继续独立解析自己的容器偏好。
        MutableProfile deposit = parseProfile(root.getAsJsonObject("deposit"));
        // restock 保持独立容器偏好。
        MutableProfile restock = parseProfile(root.getAsJsonObject("restock"));
        // 返回共享槽位权限和两套独立 profile。
        return new PlayerPreferences(deposit, restock, inventorySlotModes);
    }

    /**
     * 从 JSON 解析单个操作 profile。
     *
     * @param object 当前操作 JSON 对象，可为空。
     * @return 已归一化的操作 profile。
     */
    private MutableProfile parseProfile(JsonObject object) {
        // 缺失 profile 时返回默认 profile。
        if (object == null) {
            return MutableProfile.defaults();
        }
        // 解析模式，非法值回退距离优先。
        ContainerOrderMode orderMode = parseOrderMode(object.get("orderMode"));
        // 解析黑名单数组。
        Set<ContainerIdentity.ContainerType> blacklist = parseTypeSet(object.get("blacklistedContainerTypes"));
        // 解析容器优先级数组。
        List<ContainerIdentity.ContainerType> priority = parseTypeList(object.get("containerTypePriority"));
        // 用空权限映射创建只保存容器偏好的快照。
        OperationPreferencesSnapshot normalized = new OperationPreferencesSnapshot(
                orderMode, blacklist, priority, Map.of());
        return new MutableProfile(normalized);
    }

    /**
     * 解析玩家级四态槽位权限并兼容旧版 deposit 锁定数组。
     *
     * @param modesObject 新版状态对象，可为空。
     * @param depositObject deposit JSON 对象，可为空，用于读取旧字段。
     * @return 仅含合法非默认状态的槽位映射。
     */
    private Map<Integer, InventorySlotMode> parseInventorySlotModes(JsonObject modesObject,
                                                                     JsonObject depositObject) {
        // 使用稳定映射保存显式的新格式状态。
        Map<Integer, InventorySlotMode> inventorySlotModes = new java.util.HashMap<>();
        // 喵~防御：非空对象才尝试读取新版键值对。
        if (modesObject != null) {
            for (Map.Entry<String, JsonElement> entry : modesObject.entrySet()) {
                Integer inventorySlot = parseInventorySlotKey(entry.getKey());
                InventorySlotMode mode = parseInventorySlotMode(entry.getValue());
                if (inventorySlot != null && mode != null && mode != InventorySlotMode.ALLOW_BOTH) {
                    inventorySlotModes.put(inventorySlot, mode);
                }
            }
        }
        // 旧数组只补充新版未声明的槽位，保证新格式优先。
        Set<Integer> legacyLockedSlots = depositObject == null ? Set.of()
                : parseLockedInventorySlots(depositObject.get("lockedInventorySlots"));
        for (Integer inventorySlot : legacyLockedSlots) {
            if (inventorySlot != null && !inventorySlotModes.containsKey(inventorySlot)) {
                inventorySlotModes.put(inventorySlot, InventorySlotMode.RESTOCK_ONLY);
            }
        }
        // 统一用快照过滤无效键和值后返回非默认映射。
        return new OperationPreferencesSnapshot(ContainerOrderMode.DISTANCE, Set.of(), List.of(),
                inventorySlotModes).getInventorySlotModes();
    }

    /** 解析新 JSON 对象的槽位键。 */
    private Integer parseInventorySlotKey(String slotKey) {
        // 喵~防御：空键或非整数键不对应任何玩家背包槽位。
        if (slotKey == null || slotKey.isBlank()) {
            return null;
        }
        try {
            // 使用精确整数转换阻止小数和溢出键被截断。
            int inventorySlot = new BigDecimal(slotKey).intValueExact();
            // 仅接受 Bukkit 玩家背包 0..35。
            return OperationPreferencesSnapshot.isLockableInventorySlot(inventorySlot)
                    ? inventorySlot : null;
        } catch (NumberFormatException | ArithmeticException exception) {
            // 喵~防御：手工编辑的非法键不阻断整份偏好加载。
            return null;
        }
    }

    /** 解析新 JSON 对象的槽位权限值。 */
    private InventorySlotMode parseInventorySlotMode(JsonElement element) {
        // 喵~防御：仅接受已知枚举名称字符串。
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        try {
            // 将合法状态名称解析为枚举。
            return InventorySlotMode.valueOf(element.getAsString());
        } catch (IllegalArgumentException exception) {
            // 喵~防御：未来或手工错误状态按默认未配置处理。
            return null;
        }
    }

    /**
     * 解析 deposit 锁定主背包槽位数组。
     *
     * @param element JSON 槽位数组。
     * @return 仅含合法主背包槽位的集合。
     */
    private Set<Integer> parseLockedInventorySlots(JsonElement element) {
        // 使用集合自动消除重复槽位。
        Set<Integer> lockedInventorySlots = new java.util.HashSet<>();
        // 喵~防御：非数组或缺失字段兼容旧 JSON，按空集合处理。
        if (element == null || !element.isJsonArray()) {
            return lockedInventorySlots;
        }
        // 逐项读取手工编辑或旧版本 JSON 的槽位值。
        for (JsonElement slotElement : element.getAsJsonArray()) {
            // 喵~防御：仅接受数值原始类型，跳过字符串、布尔值和 null。
            if (slotElement == null || !slotElement.isJsonPrimitive()
                    || !slotElement.getAsJsonPrimitive().isNumber()) {
                continue;
            }
            try {
                // 读取 JSON 原始数字文本并要求它是精确整数喵~
                BigDecimal numericValue = new BigDecimal(slotElement.getAsString());
                // 喵~防御：小数、无穷大和超出 int 范围的配置一律忽略喵~
                int inventorySlot = numericValue.intValueExact();
                // 只接受 AutoChest 定义的可锁定主背包槽位喵~
                if (OperationPreferencesSnapshot.isLockableInventorySlot(inventorySlot)) {
                    // 保存合法槽位并自动去重喵~
                    lockedInventorySlots.add(inventorySlot);
                }
            } catch (NumberFormatException | ArithmeticException exception) {
                // 喵~防御：异常数值不阻断整份玩家偏好加载喵~
            }
        }
        // 返回待快照再次规范化的合法集合。
        return lockedInventorySlots;
    }

    /**
     * 解析排序模式 JSON 值。
     *
     * @param element JSON 元素。
     * @return 合法排序模式或距离优先默认值。
     */
    private ContainerOrderMode parseOrderMode(JsonElement element) {
        // 非字符串模式字段直接回退默认。
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return ContainerOrderMode.DISTANCE;
        }
        try {
            // 解析枚举名称。
            return ContainerOrderMode.valueOf(element.getAsString());
        } catch (IllegalArgumentException exception) {
            // 喵~防御：未知模式不会让加载失败，使用安全默认。
            return ContainerOrderMode.DISTANCE;
        }
    }

    /**
     * 解析容器类型集合。
     *
     * @param element JSON 数组元素。
     * @return 去重后的容器类型集合。
     */
    private Set<ContainerIdentity.ContainerType> parseTypeSet(JsonElement element) {
        // 使用枚举集合自动去重。
        EnumSet<ContainerIdentity.ContainerType> types = EnumSet.noneOf(ContainerIdentity.ContainerType.class);
        // 将列表解析结果加入集合。
        types.addAll(parseTypeList(element));
        // 返回枚举集合。
        return types;
    }

    /**
     * 解析容器类型列表。
     *
     * @param element JSON 数组元素。
     * @return 仅含合法容器类型的列表。
     */
    private List<ContainerIdentity.ContainerType> parseTypeList(JsonElement element) {
        // 创建结果列表。
        List<ContainerIdentity.ContainerType> types = new ArrayList<>();
        // 非数组字段视为没有配置条目。
        if (element == null || !element.isJsonArray()) {
            return types;
        }
        // 逐项读取 JSON 数组。
        for (JsonElement typeElement : element.getAsJsonArray()) {
            if (typeElement == null || !typeElement.isJsonPrimitive()
                    || !typeElement.getAsJsonPrimitive().isString()) {
                continue;
            }
            try {
                // 只接受当前插件已知的枚举种类。
                types.add(ContainerIdentity.ContainerType.valueOf(typeElement.getAsString()));
            } catch (IllegalArgumentException exception) {
                // 喵~防御：未知种类兼容未来或手工编辑 JSON，静默忽略该项。
            }
        }
        // 返回可能含重复项的列表，后续 snapshot 会统一去重。
        return types;
    }

    /**
     * 将当前完整偏好排队写入 JSON 文件。
     *
     * @param playerUuid 玩家 UUID。
     * @param preferences 已复制的完整偏好模型。
     */
    private void queueSave(UUID playerUuid, PlayerPreferences preferences) {
        // 关闭过程中不再接受新持久化任务。
        if (closing) {
            return;
        }
        // 在主线程预先完成 JSON 序列化，后台线程仅执行文件 IO。
        String json = serializePreferences(preferences);
        // 计算目标文件路径，后台线程无需访问 Bukkit 或可变缓存。
        Path targetFile = playerFile(playerUuid);
        // 单线程 FIFO 执行确保旧快照永远不会覆盖后提交的新快照。
        persistenceExecutor.execute(() -> writeAtomically(targetFile, json));
    }

    /**
     * 将完整玩家偏好序列化为 JSON 文本。
     *
     * @param preferences 玩家偏好模型。
     * @return UTF-8 JSON 文本。
     */
    private String serializePreferences(PlayerPreferences preferences) {
        // 创建 JSON 根对象。
        JsonObject root = new JsonObject();
        // 写入格式版本以支持未来迁移。
        root.addProperty("version", SCHEMA_VERSION);
        // 写入共享玩家级槽位权限。
        root.add("inventorySlotModes", serializeInventorySlotModes(preferences.inventorySlotModes));
        // 写入 deposit 独立 profile。
        root.add("deposit", serializeProfile(preferences.deposit));
        // 写入 restock 独立 profile。
        root.add("restock", serializeProfile(preferences.restock));
        // 输出可读且稳定的 JSON 文本。
        return gson.toJson(root);
    }

    /** 序列化共享玩家级的非默认槽位权限。 */
    private JsonObject serializeInventorySlotModes(Map<Integer, InventorySlotMode> inventorySlotModes) {
        // 创建 JSON 对象以保存槽位到状态的映射。
        JsonObject modesObject = new JsonObject();
        // 喵~防御：空映射写出空对象，保持新格式结构稳定。
        if (inventorySlotModes == null) {
            return modesObject;
        }
        // 使用稳定排序输出，便于玩家查看和版本控制比较。
        for (Integer inventorySlot : inventorySlotModes.keySet().stream().sorted().toList()) {
            InventorySlotMode mode = inventorySlotModes.get(inventorySlot);
            if (inventorySlot != null && mode != null && mode != InventorySlotMode.ALLOW_BOTH) {
                modesObject.addProperty(String.valueOf(inventorySlot), mode.name());
            }
        }
        // 返回新格式状态对象。
        return modesObject;
    }

    /**
     * 将单个操作 profile 序列化为 JSON 对象。
     *
     * @param profile 待写入 profile。
     * @return JSON profile 对象。
     */
    private JsonObject serializeProfile(MutableProfile profile) {
        // 先转换为不可变快照以确保输出已归一化。
        OperationPreferencesSnapshot snapshot = profile.snapshot();
        // 创建 JSON profile 对象。
        JsonObject object = new JsonObject();
        // 保存排序模式名称。
        object.addProperty("orderMode", snapshot.getOrderMode().name());
        // 创建黑名单 JSON 数组。
        JsonArray blacklist = new JsonArray();
        // 依序写入黑名单枚举名称。
        for (ContainerIdentity.ContainerType type : snapshot.getBlacklistedContainerTypes()) {
            blacklist.add(type.name());
        }
        // 挂载黑名单数组。
        object.add("blacklistedContainerTypes", blacklist);
        // 创建优先级 JSON 数组。
        JsonArray priority = new JsonArray();
        // 依序写入完整优先级。
        for (ContainerIdentity.ContainerType type : snapshot.getContainerTypePriority()) {
            priority.add(type.name());
        }
        // 挂载优先级数组。
        object.add("containerTypePriority", priority);
        // 返回 JSON profile。
        return object;
    }

    /**
     * 使用临时文件和原子替换写入 JSON。
     *
     * @param targetFile 目标 JSON 文件。
     * @param json 已序列化 JSON 文本。
     */
    private void writeAtomically(Path targetFile, String json) {
        Path temporaryFile = null;
        try {
            // 确保 data/players 目录存在。
            Files.createDirectories(playersDirectory);
            // 在目标目录创建临时文件，确保原子移动可在同一文件系统内完成。
            temporaryFile = Files.createTempFile(playersDirectory, targetFile.getFileName().toString(), ".tmp");
            // 将 JSON 字节写入临时文件并强制刷入磁盘。
            try (FileChannel channel = FileChannel.open(temporaryFile,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            // 仅允许原子替换，避免非原子覆盖损坏上一份有效配置。
            Files.move(temporaryFile, targetFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            // 喵~防御：文件系统不支持原子替换时保留旧文件，明确记录无法安全保存。
            logger.severe("[AutoChest] 玩家偏好文件系统不支持原子替换，未保存: " + targetFile);
        } catch (IOException exception) {
            // 喵~防御：目录、临时文件、fsync 或替换失败时保留旧文件并记录异常。
            logger.warning("[AutoChest] 保存玩家偏好失败: " + targetFile + " - " + exception.getMessage());
        } finally {
            // 尽力清理未移动的临时文件，避免积累残留。
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // 喵~防御：清理失败不会影响旧有效 JSON，后续写入仍可继续。
                }
            }
        }
    }

    /**
     * 计算玩家 JSON 文件路径。
     *
     * @param playerUuid 玩家 UUID。
     * @return 玩家独立 JSON 文件路径。
     */
    private Path playerFile(UUID playerUuid) {
        // 使用 UUID 防止玩家名改名导致配置错配。
        return playersDirectory.resolve(playerUuid + ".json");
    }

    /**
     * 停止接收新更新并立即返回，允许 daemon 持久化线程在停服阶段自行完成剩余队列。
     * 该方法专供 Bukkit onDisable 使用，避免主线程等待文件 IO。
     */
    public void closeWithoutWaiting() {
        // 标记关闭以拒绝停服开始后的新配置更新。
        closing = true;
        // 停止接收新任务但保留已提交队列继续执行。
        persistenceExecutor.shutdown();
    }

    /**
     * 停止服务并有界等待排队写入完成。
     *
     * @param timeoutSeconds 最大等待秒数。
     */
    public void flushAndClose(long timeoutSeconds) {
        // 标记关闭以拒绝后续命令更新。
        closing = true;
        // 停止接收新任务并让已提交任务按 FIFO 完成。
        persistenceExecutor.shutdown();
        try {
            // 有界等待防止插件关闭时永久卡住主线程。
            if (!persistenceExecutor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warning("[AutoChest] 等待玩家偏好保存超时，可能存在未落盘配置喵~");
                persistenceExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            // 喵~防御：关闭线程被中断时强制停止并恢复中断标记。
            persistenceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 可变单操作 profile，仅由偏好服务主线程管理。
     */
    private static final class MutableProfile {
        /** 当前排序模式。 */
        private ContainerOrderMode orderMode;
        /** 当前黑名单集合。 */
        private EnumSet<ContainerIdentity.ContainerType> blacklistedContainerTypes;
        /** 当前容器种类优先级列表。 */
        private List<ContainerIdentity.ContainerType> containerTypePriority;

        /** 创建默认 profile。 */
        private static MutableProfile defaults() {
            // 使用默认不可变快照创建独立可变 profile。
            return new MutableProfile(OperationPreferencesSnapshot.defaults());
        }

        /** 从不可变快照创建可变 profile。 */
        private MutableProfile(OperationPreferencesSnapshot snapshot) {
            // 保存模式。
            this.orderMode = snapshot.getOrderMode();
            // 复制黑名单集合。
            this.blacklistedContainerTypes = snapshot.getBlacklistedContainerTypes().isEmpty()
                    ? EnumSet.noneOf(ContainerIdentity.ContainerType.class)
                    : EnumSet.copyOf(snapshot.getBlacklistedContainerTypes());
            // 复制优先级列表。
            this.containerTypePriority = new ArrayList<>(snapshot.getContainerTypePriority());
        }

        /** 生成只包含容器偏好的不可变快照。 */
        private OperationPreferencesSnapshot snapshot() {
            // 槽位权限由玩家级映射单独管理，profile 快照不保存它。
            return new OperationPreferencesSnapshot(orderMode, blacklistedContainerTypes,
                    containerTypePriority, Map.of());
        }
    }

    /**
     * 可变完整玩家偏好，仅由偏好服务主线程管理。
     */
    private static final class PlayerPreferences {
        /** deposit 独立 profile。 */
        private final MutableProfile deposit;
        /** restock 独立 profile。 */
        private final MutableProfile restock;
        /** 玩家级共享的非默认槽位权限映射。 */
        private final Map<Integer, InventorySlotMode> inventorySlotModes;

        /** 创建指定的两个 profile 和共享槽位权限。 */
        private PlayerPreferences(MutableProfile deposit, MutableProfile restock,
                                  Map<Integer, InventorySlotMode> inventorySlotModes) {
            // 喵~防御：缺失 profile 回退默认，确保两个操作始终独立存在。
            this.deposit = deposit == null ? MutableProfile.defaults() : deposit;
            // 喵~防御：缺失 profile 回退默认，确保两个操作始终独立存在。
            this.restock = restock == null ? MutableProfile.defaults() : restock;
            // 创建独立可变映射，仅保留合法且非默认的共享权限。
            this.inventorySlotModes = new java.util.HashMap<>(new OperationPreferencesSnapshot(
                    ContainerOrderMode.DISTANCE, Set.of(), List.of(), inventorySlotModes).getInventorySlotModes());
        }

        /** 创建双操作默认偏好。 */
        private static PlayerPreferences defaults() {
            // 为两项操作创建彼此独立的默认 profile 与空权限映射。
            return new PlayerPreferences(MutableProfile.defaults(), MutableProfile.defaults(), Map.of());
        }

        /** 根据操作返回对应 profile。 */
        private MutableProfile profile(OperationType operation) {
            // deposit 与 restock 使用完全独立数据。
            return operation == OperationType.DEPOSIT ? deposit : restock;
        }

        /** 根据操作返回不可变任务快照。 */
        private OperationPreferencesSnapshot snapshot(OperationType operation) {
            // 合并操作专属容器偏好和玩家级共享槽位权限。
            MutableProfile profile = profile(operation);
            return new OperationPreferencesSnapshot(profile.orderMode, profile.blacklistedContainerTypes,
                    profile.containerTypePriority, inventorySlotModes);
        }

        /** 创建完整深拷贝，供后台写入安全使用。 */
        private PlayerPreferences copy() {
            // 通过不可变快照重建两份独立 profile，并复制共享槽位权限。
            return new PlayerPreferences(new MutableProfile(deposit.snapshot()),
                    new MutableProfile(restock.snapshot()), inventorySlotModes);
        }
    }
}
