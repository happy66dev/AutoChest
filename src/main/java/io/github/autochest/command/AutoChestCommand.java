package io.github.autochest.command;

import io.github.autochest.AutoChestPlugin;
import io.github.autochest.config.CooldownService;
import io.github.autochest.config.MessageService;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.hook.CompositeAccessPolicy;
import io.github.autochest.gui.PreferencesGui;
import io.github.autochest.integration.playerbackpack.PlayerBackpackAdapter;
import io.github.autochest.integration.playerbackpack.PlayerBackpackTaskContext;
import io.github.autochest.integration.playerbackpack.PlayerBackpackTaskContexts;
import com.playerbackpack.api.BackpackOperationFailure;
import com.playerbackpack.api.BackpackSnapshotView;
import com.playerbackpack.api.PlayerBackpackOperation;
import io.github.autochest.preference.ContainerOrderMode;
import io.github.autochest.preference.OperationPreferencesSnapshot;
import io.github.autochest.preference.PlayerPreferencesService;
import io.github.autochest.scan.CandidatePlanner;
import io.github.autochest.scan.CandidatePlanner.PlanResult;
import io.github.autochest.scan.InventorySnapshotFactory;
import io.github.autochest.scan.ScanTask;
import io.github.autochest.service.*;
import io.github.autochest.task.OperationType;
import io.github.autochest.task.PlayerTask;
import io.github.autochest.task.PlayerTaskRegistry;
import io.github.autochest.task.RestockTargetListener;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * /autochest 主命令处理器
 * 处理 deposit、restock、reload 三个子命令
 * 完整协调任务创建、扫描、异步规划和库存提交流程
 */
public class AutoChestCommand implements CommandExecutor, TabCompleter {

    private final AutoChestPlugin plugin;
    private final PlayerTaskRegistry registry;
    private final CooldownService cooldownService;
    private final CompositeAccessPolicy accessPolicy;
    private final InventorySnapshotFactory snapshotFactory;
    private final CandidatePlanner planner;
    private final DepositService depositService;
    private final RestockService restockService;
    private final RestockTargetListener restockListener;
    /** 共享的容器事务执行器，供快照阶段和提交阶段复用 */
    private final ContainerTransaction containerTransaction;

    /** 玩家容器偏好服务，负责独立操作配置和 JSON 保存 */
    private final PlayerPreferencesService playerPreferencesService;

    /** 玩家容器偏好 GUI 开启器，保留文本命令作为并列入口 */
    private final PreferencesGui preferencesGui;

    /** PlayerBackpack 外部会话资源表，统一覆盖命令失败和任务完成出口 */
    private final PlayerBackpackTaskContexts playerBackpackTaskContexts;

    /** 活跃的扫描 BukkitTask，UUID → BukkitTask；用于插件禁用时取消 */
    private final Map<UUID, BukkitTask> activeScanTasks = new HashMap<>();

    /**
     * 创建命令处理器
     *
     * @param plugin               插件主类
     * @param registry             任务注册表
     * @param cooldownService      冷却服务
     * @param accessPolicy         容器访问策略
     * @param executor             异步线程池
     * @param snapshotFactory      库存快照工厂
     * @param planner              候选规划器
     * @param depositService       存入服务
     * @param restockService       补货服务
     * @param restockListener      restock 槽位监听器
     * @param containerTransaction 共享的容器事务执行器
     * @param playerPreferencesService 玩家容器偏好服务
     * @param preferencesGui 玩家容器偏好 GUI
     */
    public AutoChestCommand(
            AutoChestPlugin plugin,
            PlayerTaskRegistry registry,
            CooldownService cooldownService,
            CompositeAccessPolicy accessPolicy,
            ExecutorService executor,
            InventorySnapshotFactory snapshotFactory,
            CandidatePlanner planner,
            DepositService depositService,
            RestockService restockService,
            RestockTargetListener restockListener,
            ContainerTransaction containerTransaction,
            PlayerPreferencesService playerPreferencesService,
            PreferencesGui preferencesGui
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.cooldownService = cooldownService;
        this.accessPolicy = accessPolicy;
        this.snapshotFactory = snapshotFactory;
        this.planner = planner;
        this.depositService = depositService;
        this.restockService = restockService;
        this.restockListener = restockListener;
        this.containerTransaction = containerTransaction;
        this.playerPreferencesService = playerPreferencesService;
        this.preferencesGui = preferencesGui;
        // 从插件取得跨域任务统一资源表，避免命令自行维护第二份锁记录喵~
        this.playerBackpackTaskContexts = plugin.getPlayerBackpackTaskContexts();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 只有玩家可以使用 deposit 和 restock，控制台只能使用 reload
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "deposit" -> handleDeposit(sender);
            case "restock" -> handleRestock(sender);
            case "config" -> handleConfig(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (String sub : List.of("deposit", "restock", "config", "reload")) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
            return completions;
        }
        if (!args[0].equalsIgnoreCase("config")) {
            return Collections.emptyList();
        }
        return completeConfig(args);
    }

    // ===== deposit =====

    /**
     * 处理 /autochest deposit 命令
     *
     * @param sender 命令发送者
     */
    private void handleDeposit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c此命令只能由玩家使用喵~");
            return;
        }

        MessageService messages = plugin.getMessageService();

        // 权限检查
        if (!player.hasPermission("autochest.deposit")) {
            messages.sendNoPermission(player);
            return;
        }

        // 任务冲突检查（优先于冷却）
        if (registry.hasActiveTask(player.getUniqueId())) {
            messages.sendTaskConflict(player);
            return;
        }

        // 冷却检查
        if (cooldownService.isOnCooldown(player.getUniqueId(), CooldownService.OperationType.DEPOSIT)) {
            long remaining = cooldownService.getRemainingMs(player.getUniqueId(), CooldownService.OperationType.DEPOSIT);
            messages.sendCooldown(player, remaining);
            return;
        }

        // Hook 可用性整体检查
        String unavailableHook = accessPolicy.findUnavailableHook();
        if (unavailableHook != null) {
            messages.sendHookUnavailable(player, unavailableHook);
            return;
        }

        // 读取本次存入任务的不可变玩家偏好快照。
        OperationPreferencesSnapshot preferencesSnapshot = playerPreferencesService.snapshot(
                player.getUniqueId(), OperationType.DEPOSIT);

        // 若可用则先冻结 PlayerBackpack GUI、取得独占会话并在下一 tick 建立快照。
        if (beginPlayerBackpackThenNextTick(player, OperationType.DEPOSIT,
                () -> beginDepositTask(player, preferencesSnapshot))) {
            // 已开始跨域预备流程，后续由回调创建 AutoChest 任务喵~
            return;
        }
        // PlayerBackpack 不可用时保持现有原版存入流程喵~
        beginDepositTask(player, preferencesSnapshot);
    }

    // 在 PlayerBackpack 预备完成后创建原版存入任务并启动扫描喵~
    private void beginDepositTask(Player player, OperationPreferencesSnapshot preferencesSnapshot) {
        // 喵~防御：下一 tick 回调执行时玩家可能已离线、死亡或切换状态喵~
        if (player == null || !player.isOnline() || player.isDead()) {
            // 释放预备阶段已经登记的 PlayerBackpack 会话喵~
            playerBackpackTaskContexts.releasePlayer(player == null ? null : player.getUniqueId());
            // 不创建不可执行的 AutoChest 任务喵~
            return;
        }
        // 尝试创建任务（CAS 插入，保证不竞争）喵~
        Optional<PlayerTask> taskOpt = registry.tryAcquire(
                player.getUniqueId(), OperationType.DEPOSIT, plugin.getCurrentConfig(), preferencesSnapshot,
                player.getWorld().getUID(), player.getLocation().getBlockX(),
                player.getLocation().getBlockY(), player.getLocation().getBlockZ());
        // 喵~防御：预备 tick 内出现并发任务时释放外部会话并提示冲突喵~
        if (taskOpt.isEmpty()) {
            // 释放不再属于任务的 PlayerBackpack 会话喵~
            playerBackpackTaskContexts.releasePlayer(player.getUniqueId());
            // 提示玩家已有任务喵~
            plugin.getMessageService().sendTaskConflict(player);
            // 结束命令流程喵~
            return;
        }
        // 取得已注册的 AutoChest 任务喵~
        PlayerTask task = taskOpt.get();
        // 命令真正接受后消费冷却喵~
        cooldownService.record(player.getUniqueId(), CooldownService.OperationType.DEPOSIT);
        // 提示扫描开始喵~
        plugin.getMessageService().sendScanStarted(player);
        // 启动分 tick 容器扫描喵~
        startScan(task);
    }

    // ===== restock =====

    /**
     * 处理 /autochest restock 命令
     *
     * @param sender 命令发送者
     */
    private void handleRestock(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c此命令只能由玩家使用喵~");
            return;
        }

        MessageService messages = plugin.getMessageService();

        if (!player.hasPermission("autochest.restock")) {
            messages.sendNoPermission(player);
            return;
        }

        if (registry.hasActiveTask(player.getUniqueId())) {
            messages.sendTaskConflict(player);
            return;
        }

        if (cooldownService.isOnCooldown(player.getUniqueId(), CooldownService.OperationType.RESTOCK)) {
            long remaining = cooldownService.getRemainingMs(player.getUniqueId(), CooldownService.OperationType.RESTOCK);
            messages.sendCooldown(player, remaining);
            return;
        }

        String unavailableHook = accessPolicy.findUnavailableHook();
        if (unavailableHook != null) {
            messages.sendHookUnavailable(player, unavailableHook);
            return;
        }

        // 在创建任务前立即生成目标槽位白名单（命令接受时快照）
        RestockTargetWhitelist whitelist = new RestockTargetWhitelist(player);

        // 读取本次补货任务的不可变玩家偏好快照。
        OperationPreferencesSnapshot preferencesSnapshot = playerPreferencesService.snapshot(
                player.getUniqueId(), OperationType.RESTOCK);

        // 可用时先冻结 PlayerBackpack GUI，再在下一 tick 同步建立双域白名单。
        if (beginPlayerBackpackThenNextTick(player, OperationType.RESTOCK,
                () -> beginRestockTask(player, whitelist, preferencesSnapshot))) {
            // 预备流程已异步接管任务创建喵~
            return;
        }

        // PlayerBackpack 不可用时保持原版 restock 白名单与流程喵~
        beginRestockTask(player, whitelist, preferencesSnapshot);
    }

    // 在预备阶段完成后创建原版补货任务喵~
    private void beginRestockTask(Player player, RestockTargetWhitelist whitelist,
                                  OperationPreferencesSnapshot preferencesSnapshot) {
        // 喵~防御：玩家离线、死亡或白名单缺失时不创建任务喵~
        if (player == null || whitelist == null || preferencesSnapshot == null
                || !player.isOnline() || player.isDead()) {
            // 释放预备阶段外部会话喵~
            playerBackpackTaskContexts.releasePlayer(player == null ? null : player.getUniqueId());
            // 结束不可执行流程喵~
            return;
        }
        // 尝试创建任务（CAS 插入，保证不竞争）喵~
        Optional<PlayerTask> taskOpt = registry.tryAcquire(
                player.getUniqueId(), OperationType.RESTOCK, plugin.getCurrentConfig(), preferencesSnapshot,
                player.getWorld().getUID(), player.getLocation().getBlockX(),
                player.getLocation().getBlockY(), player.getLocation().getBlockZ());
        // 喵~防御：并发冲突时先释放 PlayerBackpack 会话喵~
        if (taskOpt.isEmpty()) {
            // 释放未交给任务的外部会话喵~
            playerBackpackTaskContexts.releasePlayer(player.getUniqueId());
            // 提示任务冲突喵~
            plugin.getMessageService().sendTaskConflict(player);
            // 结束命令流程喵~
            return;
        }
        // 取得已注册任务喵~
        PlayerTask task = taskOpt.get();
        // 命令接受后消费冷却喵~
        cooldownService.record(player.getUniqueId(), CooldownService.OperationType.RESTOCK);
        // 开始追踪原版背包白名单喵~
        restockListener.startTracking(player.getUniqueId(), whitelist);
        // 提示扫描开始喵~
        plugin.getMessageService().sendScanStarted(player);
        // 启动分 tick 扫描喵~
        startRestockScan(task, whitelist);
    }

    // 在主线程开始 PlayerBackpack 外部操作并于下一 tick 建立最新快照喵~
    private boolean beginPlayerBackpackThenNextTick(Player player, OperationType operationType,
                                                    Runnable afterFreeze) {
        // 读取已校验的可选适配器喵~
        PlayerBackpackAdapter adapter = plugin.getPlayerBackpackHook() == null
                ? null : plugin.getPlayerBackpackHook().adapter();
        // 不可用时返回 false 让调用方执行原版流程喵~
        if (adapter == null) {
            // 原版流程不需要外部会话喵~
            return false;
        }
        // 尝试取得当前玩家目标背包独占会话喵~
        Optional<PlayerBackpackOperation> operationOptional = adapter.tryBeginOperation(
                player.getUniqueId(), player.getUniqueId(), operationType.name().toLowerCase(Locale.ROOT));
        // 喵~防御：目标繁忙或 provider 异常时 fail-closed 拒绝扩展任务喵~
        if (operationOptional.isEmpty()) {
            // 不调用 afterFreeze，避免没有双域快照时继续写入喵~
            return true;
        }
        // 取得独占操作句柄喵~
        PlayerBackpackOperation operation = operationOptional.get();
        // 保存并关闭所有相关 PlayerBackpack GUI 喵~
        BackpackOperationFailure freezeFailure = adapter.saveAndCloseOpenGui(operation);
        // 只有 NONE 表示冻结成功喵~
        if (freezeFailure != BackpackOperationFailure.NONE) {
            // 释放冻结失败的外部会话喵~
            adapter.finish(operation);
            // 拒绝扩展任务并提示原版流程不可安全启动喵~
            return true;
        }
        // 下一 tick 读取关闭 GUI 后的最新 snapshot 喵~
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 喵~防御：下一 tick 读取失败时释放会话而不创建任务喵~
            Optional<BackpackSnapshotView> snapshotOptional = adapter.loadSnapshot(player.getUniqueId());
            if (snapshotOptional.isEmpty()) {
                // 释放无法建立快照的外部操作喵~
                adapter.finish(operation);
                // 不执行任何 Bukkit/PlayerBackpack 写入喵~
                return;
            }
            // 创建绑定目标和 revision 的任务上下文喵~
            PlayerBackpackTaskContext context = new PlayerBackpackTaskContext(
                    adapter, operation, snapshotOptional.get());
            // 喵~防御：上下文登记失败时释放会话，避免并发任务双持有喵~
            if (!playerBackpackTaskContexts.register(player.getUniqueId(), context)) {
                // 释放未登记上下文喵~
                context.close();
                // 不启动任务喵~
                return;
            }
            // 运行统一的后续任务创建回调喵~
            afterFreeze.run();
        });
        // 已进入下一 tick 预备流程喵~
        return true;
    }

    // ===== player container preferences =====

    /**
     * 处理玩家独立的容器偏好配置命令。
     *
     * @param sender 命令发送者。
     * @param args 完整命令参数。
     */
    private void handleConfig(CommandSender sender, String[] args) {
        // 喵~防御：控制台没有玩家 UUID，不能管理玩家私有偏好。
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c此配置命令只能由玩家使用喵~");
            return;
        }
        // 检查玩家管理自身偏好的权限。
        if (!player.hasPermission("autochest.config")) {
            plugin.getMessageService().sendNoPermission(player);
            return;
        }
        // 无参数时提供 GUI 主菜单入口。
        if (args.length == 1) {
            preferencesGui.openMain(player);
            return;
        }
        // 单独指定操作时直接打开对应 GUI 页面，保留带后续参数的文本命令。
        if (args.length == 2) {
            OperationType directOperation = parseOperation(args[1]);
            if (directOperation != null) {
                preferencesGui.openOperation(player, directOperation);
                return;
            }
        }
        // 参数不足时显示配置用法。
        if (args.length < 3) {
            sendConfigHelp(player);
            return;
        }
        // 解析独立目标操作。
        OperationType operation = parseOperation(args[1]);
        // 喵~防御：未知操作不修改内存或 JSON。
        if (operation == null) {
            sendConfigHelp(player);
            return;
        }
        // 分发配置类别。
        String category = args[2].toLowerCase(Locale.ROOT);
        if (category.equals("mode") && args.length == 4) {
            handleConfigMode(player, operation, args[3]);
            return;
        }
        if (category.equals("blacklist")) {
            handleConfigBlacklist(player, operation, args);
            return;
        }
        if (category.equals("priority")) {
            handleConfigPriority(player, operation, args);
            return;
        }
        // 无法匹配的配置语法只显示帮助。
        sendConfigHelp(player);
    }

    /** 处理排序模式修改。 */
    private void handleConfigMode(Player player, OperationType operation, String value) {
        // 解析玩家可读模式名称。
        ContainerOrderMode mode = value.equalsIgnoreCase("distance") ? ContainerOrderMode.DISTANCE
                : value.equalsIgnoreCase("priority") ? ContainerOrderMode.CONTAINER_PRIORITY : null;
        // 喵~防御：非法模式不能写入偏好。
        if (mode == null || !playerPreferencesService.setOrderMode(player.getUniqueId(), operation, mode)) {
            player.sendMessage("§c模式无效或设置服务已关闭喵~");
            return;
        }
        // 提示内存更新已经进入异步保存队列。
        player.sendMessage("§a已设置 " + operation.name().toLowerCase(Locale.ROOT) + " 排序模式为 "
                + value.toLowerCase(Locale.ROOT) + " 喵~");
    }

    /** 处理黑名单增删与查看。 */
    private void handleConfigBlacklist(Player player, OperationType operation, String[] args) {
        // 参数不足时展示当前黑名单，避免输入空种类。
        if (args.length == 4 && args[3].equalsIgnoreCase("list")) {
            OperationPreferencesSnapshot snapshot = playerPreferencesService.snapshot(player.getUniqueId(), operation);
            player.sendMessage("§e" + operation.name().toLowerCase(Locale.ROOT) + " 黑名单: "
                    + snapshot.getBlacklistedContainerTypes() + " 喵~");
            return;
        }
        // 增删操作必须携带容器种类。
        if (args.length != 5) {
            sendConfigHelp(player);
            return;
        }
        // 解析黑名单动作和种类。
        boolean add = args[3].equalsIgnoreCase("add");
        boolean remove = args[3].equalsIgnoreCase("remove");
        ContainerIdentity.ContainerType type = parseContainerType(args[4]);
        // 喵~防御：非法动作或种类不修改偏好。
        if ((!add && !remove) || type == null) {
            sendConfigHelp(player);
            return;
        }
        // 写入独立操作黑名单。
        boolean changed = playerPreferencesService.setBlacklisted(player.getUniqueId(), operation, type, add);
        player.sendMessage(changed ? "§a黑名单已更新并进入保存队列喵~" : "§e黑名单没有变化喵~");
    }

    /** 处理优先级移动、重置与查看。 */
    private void handleConfigPriority(Player player, OperationType operation, String[] args) {
        // 查看当前完整优先级列表。
        if (args.length == 4 && args[3].equalsIgnoreCase("list")) {
            player.sendMessage("§e" + operation.name().toLowerCase(Locale.ROOT) + " 容器优先级: "
                    + playerPreferencesService.snapshot(player.getUniqueId(), operation).getContainerTypePriority() + " 喵~");
            return;
        }
        // 重置当前操作的优先级列表。
        if (args.length == 4 && args[3].equalsIgnoreCase("reset")) {
            playerPreferencesService.resetPriority(player.getUniqueId(), operation);
            player.sendMessage("§a容器优先级已重置并进入保存队列喵~");
            return;
        }
        // 移动需包含种类和方向。
        if (args.length != 6 || !args[3].equalsIgnoreCase("move")) {
            sendConfigHelp(player);
            return;
        }
        // 解析目标种类与上/下方向。
        ContainerIdentity.ContainerType type = parseContainerType(args[4]);
        boolean up = args[5].equalsIgnoreCase("up");
        boolean down = args[5].equalsIgnoreCase("down");
        // 喵~防御：非法方向或种类不改变优先级。
        if (type == null || (!up && !down)) {
            sendConfigHelp(player);
            return;
        }
        // 仅实际发生相邻移动时持久化。
        boolean changed = playerPreferencesService.movePriority(player.getUniqueId(), operation, type, up);
        player.sendMessage(changed ? "§a容器优先级已移动并进入保存队列喵~" : "§e该容器已在优先级边界喵~");
    }

    /** 将命令字符串解析为操作类型。 */
    private OperationType parseOperation(String value) {
        // deposit 映射存入操作。
        if (value.equalsIgnoreCase("deposit")) {
            return OperationType.DEPOSIT;
        }
        // restock 映射补货操作。
        if (value.equalsIgnoreCase("restock")) {
            return OperationType.RESTOCK;
        }
        // 其他字符串不是有效操作。
        return null;
    }

    /** 将命令字符串解析为容器种类。 */
    private ContainerIdentity.ContainerType parseContainerType(String value) {
        // 喵~防御：空字符串不能映射容器种类。
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // 统一处理小写、连字符和下划线输入格式。
            return ContainerIdentity.ContainerType.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            // 喵~防御：未知容器种类不触发任何偏好修改。
            return null;
        }
    }

    /** 返回配置命令帮助。 */
    private void sendConfigHelp(Player player) {
        // 输出一行精简但完整的配置语法。
        player.sendMessage("§e/ac config <deposit|restock> mode <distance|priority> | blacklist <add|remove|list> [type] | priority <list|reset|move <type> <up|down>> 喵~");
    }

    /** 按参数层级补全配置命令。 */
    private List<String> completeConfig(String[] args) {
        // 第二参数补全独立目标操作。
        if (args.length == 2) {
            return filterCompletions(args[1], List.of("deposit", "restock"));
        }
        // 第三参数补全配置类别。
        if (args.length == 3) {
            return filterCompletions(args[2], List.of("mode", "blacklist", "priority"));
        }
        // 模式类别补全两个模式名称。
        if (args.length == 4 && args[2].equalsIgnoreCase("mode")) {
            return filterCompletions(args[3], List.of("distance", "priority"));
        }
        // 黑名单第四参数补全动作。
        if (args.length == 4 && args[2].equalsIgnoreCase("blacklist")) {
            return filterCompletions(args[3], List.of("add", "remove", "list"));
        }
        // 黑名单第五参数补全固定容器种类。
        if (args.length == 5 && args[2].equalsIgnoreCase("blacklist")) {
            return filterCompletions(args[4], containerTypeNames());
        }
        // 优先级第四参数补全动作。
        if (args.length == 4 && args[2].equalsIgnoreCase("priority")) {
            return filterCompletions(args[3], List.of("list", "reset", "move"));
        }
        // 优先级移动的第五参数补全容器种类。
        if (args.length == 5 && args[2].equalsIgnoreCase("priority") && args[3].equalsIgnoreCase("move")) {
            return filterCompletions(args[4], containerTypeNames());
        }
        // 优先级移动的第六参数补全方向。
        if (args.length == 6 && args[2].equalsIgnoreCase("priority") && args[3].equalsIgnoreCase("move")) {
            return filterCompletions(args[5], List.of("up", "down"));
        }
        // 其余层级没有安全补全项。
        return Collections.emptyList();
    }

    /** 返回玩家可配置的容器种类命令名称。 */
    private List<String> containerTypeNames() {
        // 返回与 ContainerType 枚举一一对应的稳定小写名称。
        return List.of("chest", "trapped_chest", "barrel", "shulker_box", "ender_chest");
    }

    /** 按前缀过滤补全列表。 */
    private List<String> filterCompletions(String prefix, List<String> values) {
        // 将空前缀规范为可匹配全部的空字符串。
        String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        // 收集前缀匹配项。
        List<String> results = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(normalizedPrefix)) {
                results.add(value);
            }
        }
        // 返回稳定顺序结果。
        return results;
    }

    // ===== reload =====

    /**
     * 处理 /autochest reload 命令
     *
     * @param sender 命令发送者
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("autochest.reload")) {
            sender.sendMessage("§c你没有重载配置的权限喵~");
            return;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage("§a[AutoChest] 配置已重载喵~");
    }

    // ===== 扫描启动 =====

    /**
     * 启动 deposit 的分 tick 扫描任务
     *
     * @param task 玩家任务
     */
    private void startScan(PlayerTask task) {
        ScanTask scanTask = new ScanTask(
                task,
                registry,
                accessPolicy,
                plugin,
                // 扫描完成后：在主线程生成快照并提交异步规划
                containers -> onScanComplete(task, containers),
                // 扫描取消后：释放任务锁并通知玩家
                () -> onScanCancelled(task)
        );

        // 每 tick 执行一步扫描（period=1）
        BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, scanTask, 0L, 1L);
        activeScanTasks.put(task.getPlayerUuid(), bt);
    }

    /**
     * 启动 restock 的分 tick 扫描任务
     *
     * @param task      玩家任务
     * @param whitelist 目标槽位白名单
     */
    private void startRestockScan(PlayerTask task, RestockTargetWhitelist whitelist) {
        ScanTask scanTask = new ScanTask(
                task,
                registry,
                accessPolicy,
                plugin,
                containers -> onRestockScanComplete(task, containers, whitelist),
                () -> {
                    restockListener.stopTracking(task.getPlayerUuid(), whitelist);
                    onScanCancelled(task);
                }
        );

        BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, scanTask, 0L, 1L);
        activeScanTasks.put(task.getPlayerUuid(), bt);
    }

    /**
     * Deposit 扫描完成后：快照库存，提交异步规划，再回主线程执行存入
     *
     * @param task       玩家任务
     * @param containers 扫描到的容器列表
     */
    private void onScanComplete(PlayerTask task, List<ContainerIdentity> containers) {
        cancelActiveScanTask(task.getPlayerUuid());

        if (!registry.isValid(task)) {
            finishTask(task);
            return;
        }

        Player player = Bukkit.getPlayer(task.getPlayerUuid());
        if (player == null || !player.isOnline() || player.isDead()) {
            finishTask(task);
            return;
        }

        if (containers.isEmpty()) {
            plugin.getMessageService().sendNoMatch(player);
            finishTask(task);
            return;
        }

        // 主线程快照容器已有物品，用于限制 deposit 的快照候选资格。
        List<InventorySnapshotFactory.ContainerDto> containerDtos = new ArrayList<>();
        for (ContainerIdentity identity : containers) {
            org.bukkit.inventory.Inventory inventory = getInventorySafely(identity, player.getWorld(), player);
            if (inventory != null) {
                containerDtos.add(snapshotFactory.snapshotContainer(identity, inventory));
            }
        }

        // 提交异步规划。
        try {
            plugin.getExecutor().submit(() -> {
                // 异步线程只建立快照候选索引，不访问 Bukkit 对象。
                PlanResult plan = planner.plan(containerDtos);
                // 回到主线程执行存入
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!registry.isValid(task)) {
                        finishTask(task);
                        return;
                    }
                    depositService.execute(plan, task, new DepositService.DepositCallback() {
                        @Override
                        public void onComplete(DepositService.DepositStats stats) {
                            Player p = Bukkit.getPlayer(task.getPlayerUuid());
                            if (p != null && p.isOnline()) {
                                if (stats.itemsMoved == 0) {
                                    plugin.getMessageService().sendNoMatch(p);
                                } else {
                                    plugin.getMessageService().sendDepositDone(p,
                                            stats.itemsMoved, stats.containersUsed, stats.skipped);
                                }
                            }
                            finishTask(task);
                        }

                        @Override
                        public void onCancelled() {
                            Player p = Bukkit.getPlayer(task.getPlayerUuid());
                            if (p != null && p.isOnline()) {
                                plugin.getMessageService().sendCancelled(p);
                            }
                            finishTask(task);
                        }
                    });
                });
            });
        } catch (RejectedExecutionException e) {
            // 线程池队列已满
            if (player.isOnline()) {
                plugin.getMessageService().sendServerBusy(player);
            }
            finishTask(task);
        }
    }

    /**
     * Restock 扫描完成后：快照库存，提交异步规划，再回主线程执行补货
     *
     * @param task       玩家任务
     * @param containers 扫描到的容器列表
     * @param whitelist  目标槽位白名单
     */
    private void onRestockScanComplete(PlayerTask task, List<ContainerIdentity> containers,
                                        RestockTargetWhitelist whitelist) {
        cancelActiveScanTask(task.getPlayerUuid());

        if (!registry.isValid(task)) {
            restockListener.stopTracking(task.getPlayerUuid(), whitelist);
            finishTask(task);
            return;
        }

        Player player = Bukkit.getPlayer(task.getPlayerUuid());
        if (player == null || !player.isOnline() || player.isDead()) {
            restockListener.stopTracking(task.getPlayerUuid(), whitelist);
            finishTask(task);
            return;
        }

        if (containers.isEmpty() || whitelist.eligibleSlotsSorted().isEmpty()) {
            plugin.getMessageService().sendNoMatch(player);
            restockListener.stopTracking(task.getPlayerUuid(), whitelist);
            finishTask(task);
            return;
        }

        // restock 保持全部距离排序容器候选，确保低序目标槽位优先分配稀缺来源。
        List<ContainerIdentity> containerIdentities = new ArrayList<>(containers);

        try {
            plugin.getExecutor().submit(() -> {
                PlanResult plan = planner.planForRestock(containerIdentities);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!registry.isValid(task)) {
                        restockListener.stopTracking(task.getPlayerUuid(), whitelist);
                        finishTask(task);
                        return;
                    }
                    restockService.execute(plan, task, whitelist, new RestockService.RestockCallback() {
                        @Override
                        public void onComplete(RestockService.RestockStats stats) {
                            restockListener.stopTracking(task.getPlayerUuid(), whitelist);
                            Player p = Bukkit.getPlayer(task.getPlayerUuid());
                            if (p != null && p.isOnline()) {
                                if (stats.itemsMoved == 0) {
                                    plugin.getMessageService().sendNoMatch(p);
                                } else {
                                    plugin.getMessageService().sendRestockDone(p,
                                            stats.itemsMoved, stats.containersUsed, stats.skipped);
                                }
                            }
                            finishTask(task);
                        }

                        @Override
                        public void onCancelled() {
                            restockListener.stopTracking(task.getPlayerUuid(), whitelist);
                            Player p = Bukkit.getPlayer(task.getPlayerUuid());
                            if (p != null && p.isOnline()) {
                                plugin.getMessageService().sendCancelled(p);
                            }
                            finishTask(task);
                        }
                    });
                });
            });
        } catch (RejectedExecutionException e) {
            restockListener.stopTracking(task.getPlayerUuid(), whitelist);
            if (player.isOnline()) {
                plugin.getMessageService().sendServerBusy(player);
            }
            finishTask(task);
        }
    }

    /**
     * 扫描取消时的统一处理
     *
     * @param task 玩家任务
     */
    private void onScanCancelled(PlayerTask task) {
        cancelActiveScanTask(task.getPlayerUuid());
        Player player = Bukkit.getPlayer(task.getPlayerUuid());
        if (player != null && player.isOnline()) {
            plugin.getMessageService().sendCancelled(player);
        }
        finishTask(task);
    }

    /**
     * 取消并移除指定玩家的活跃扫描 BukkitTask
     *
     * @param playerUuid 玩家 UUID
     */
    private void cancelActiveScanTask(UUID playerUuid) {
        BukkitTask bt = activeScanTasks.remove(playerUuid);
        if (bt != null) {
            bt.cancel();
        }
    }

    /**
     * 释放任务锁
     *
     * @param task 要释放的任务
     */
    private void finishTask(PlayerTask task) {
        // 释放 AutoChest 任务锁，token 不匹配时不会影响新任务喵~
        registry.release(task.getPlayerUuid(), task.getToken());
        // 获取当前任务关联的 PlayerBackpack 上下文喵~
        PlayerBackpackTaskContext context = playerBackpackTaskContexts.get(task.getPlayerUuid());
        // 存在跨域会话时按引用条件幂等释放喵~
        if (context != null) {
            // 释放 PlayerBackpack 目标锁喵~
            playerBackpackTaskContexts.release(task.getPlayerUuid(), context);
        }
    }

    /**
     * 安全获取容器库存（仅验证区块和容器结构），区块未加载或容器失效时返回 null
     * 快照阶段无需再次执行 Hook 检查，提交阶段的 validate() 会完整重验
     *
     * @param identity 容器身份
     * @param world    目标世界
     * @param player   执行命令的玩家，末影箱快照必须读取其私有库存
     * @return 库存，或 null
     */
    private org.bukkit.inventory.Inventory getInventorySafely(ContainerIdentity identity,
                                                               org.bukkit.World world, Player player) {
        try {
            return containerTransaction.getInventoryIfValid(identity, world, player);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 发送帮助信息
     *
     * @param sender 命令发送者
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§b[AutoChest] §7用法：");
        sender.sendMessage("§e/autochest deposit §7- 将背包物品存入附近箱子");
        sender.sendMessage("§e/autochest restock §7- 从附近箱子补充背包物品");
        sender.sendMessage("§e/autochest config §7- 打开个人容器偏好设置界面");
        sender.sendMessage("§e/autochest reload §7- 重载配置文件");
    }
}
