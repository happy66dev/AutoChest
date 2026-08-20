package io.github.autochest.command;

import io.github.autochest.AutoChestPlugin;
import io.github.autochest.config.CooldownService;
import io.github.autochest.config.MessageService;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.hook.CompositeAccessPolicy;
import io.github.autochest.gui.PreferencesGui;
import io.github.autochest.integration.playerbackpack.PlayerBackpackAdapter;
import io.github.autochest.integration.playerbackpack.PlayerBackpackAsyncAdapter;
import io.github.autochest.integration.playerbackpack.PlayerBackpackTaskContext;
import io.github.autochest.integration.playerbackpack.PlayerBackpackTaskContexts;
import io.github.autochest.integration.playerbackpack.BackpackOperationFailure;
import io.github.autochest.integration.playerbackpack.BackpackOperation;
import io.github.autochest.integration.playerbackpack.BackpackSnapshot;
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
import org.bukkit.inventory.ItemStack;
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
        // 读取当前预备阶段注册的 PlayerBackpack context 喵~
        PlayerBackpackTaskContext context = playerBackpackTaskContexts.get(player.getUniqueId());
        // 喵~防御：跨域 context 缺失或不属于本次 task 时立即释放，禁止孤立 operation 继续喵~
        if (context != null && !playerBackpackTaskContexts.bind(player.getUniqueId(), context,
                task.getToken(), task.getSessionEpoch())) {
            // 释放刚创建但尚未执行的 AutoChest task 喵~
            registry.release(task.getPlayerUuid(), task.getToken());
            // 仅按引用释放本次 context，避免影响其他任务资源喵~
            playerBackpackTaskContexts.release(player.getUniqueId(), context);
            // 向仍在线玩家报告保守取消喵~
            plugin.getMessageService().sendCancelled(player);
            // 停止后续扫描启动喵~
            return;
        }
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

        // 读取本次补货任务的不可变玩家偏好快照。
        OperationPreferencesSnapshot preferencesSnapshot = playerPreferencesService.snapshot(
                player.getUniqueId(), OperationType.RESTOCK);

        // 可用时先冻结 PlayerBackpack GUI，再在下一 tick 同步建立双域白名单。
        if (beginPlayerBackpackThenNextTick(player, OperationType.RESTOCK,
                () -> beginRestockTask(player, new RestockTargetWhitelist(player, preferencesSnapshot), preferencesSnapshot))) {
            // 预备流程已异步接管任务创建喵~
            return;
        }

        // PlayerBackpack 不可用时在命令 tick 建立原版白名单并保持原有流程喵~
        beginRestockTask(player, new RestockTargetWhitelist(player, preferencesSnapshot), preferencesSnapshot);
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
        // 读取并绑定本次任务专属 PlayerBackpack context 喵~
        PlayerBackpackTaskContext context = playerBackpackTaskContexts.get(player.getUniqueId());
        // 喵~防御：context 缺失或归属绑定失败时释放任务，禁止未绑定双域写入喵~
        if (context != null && !playerBackpackTaskContexts.bind(player.getUniqueId(), context,
                task.getToken(), task.getSessionEpoch())) {
            // 释放刚创建的任务锁喵~
            registry.release(task.getPlayerUuid(), task.getToken());
            // 按引用释放本次外部 context 喵~
            playerBackpackTaskContexts.release(player.getUniqueId(), context);
            // 通知玩家本次任务取消喵~
            plugin.getMessageService().sendCancelled(player);
            // 终止扫描启动喵~
            return;
        }
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
        // 读取可选 Hook，缺失时保持原版流程喵~
        io.github.autochest.integration.playerbackpack.PlayerBackpackHook hook = plugin.getPlayerBackpackHook();
        // 仅在明确可写、已就绪的 v2 provider 存在时固定选择 async backend 喵~
        PlayerBackpackAsyncAdapter asyncAdapter = hook != null && hook.supportsAsyncWriteOperations()
                ? hook.asyncAdapter() : null;
        // v2 provider 存在时禁止回退 v1，按异步 GUI 冻结和快照状态机建立固定 backend 喵~
        if (asyncAdapter != null) {
            // 异步预备完成后由 callback 创建任务喵~
            beginAsyncPlayerBackpackThenNextTick(player, operationType, asyncAdapter, afterFreeze);
            // 表示已接管跨域预备流程喵~
            return true;
        }
        // 读取已校验同步 v1 适配器，只在没有 v2 backend 时使用喵~
        PlayerBackpackAdapter adapter = hook == null ? null : hook.adapter();
        // 不可用时返回 false 让调用方执行原版流程喵~
        if (adapter == null) {
            return false;
        }
        // 尝试取得当前玩家目标背包独占会话喵~
        Optional<BackpackOperation> operationOptional = adapter.tryBeginOperation(
                player.getUniqueId(), player.getUniqueId(), operationType.name().toLowerCase(Locale.ROOT));
        // 喵~防御：目标繁忙或 provider 异常时 fail-closed 拒绝扩展任务喵~
        if (operationOptional.isEmpty()) {
            plugin.getMessageService().sendTaskConflict(player);
            return true;
        }
        // 取得独占操作句柄喵~
        BackpackOperation operation = operationOptional.get();
        // 登记 v1 operation，覆盖 freeze 到 context 注册之间的退出与停服竞态喵~
        if (!playerBackpackTaskContexts.registerPending(player.getUniqueId(), adapter, operation)) {
            // 登记失败时立即释放 provider operation，禁止孤立 busy token 喵~
            adapter.finish(operation);
            plugin.getMessageService().sendTaskConflict(player);
            return true;
        }
        // 保存并关闭所有相关 PlayerBackpack GUI 喵~
        BackpackOperationFailure freezeFailure = adapter.saveAndCloseOpenGui(operation);
        // 只有 NONE 表示冻结成功喵~
        if (freezeFailure != BackpackOperationFailure.NONE) {
            adapter.finish(operation);
            plugin.getMessageService().sendCancelled(player);
            return true;
        }
        // 下一 tick 读取关闭 GUI 后的最新 snapshot 喵~
        Bukkit.getScheduler().runTask(plugin, () -> {
            BackpackOperationFailure readinessFailure = adapter.confirmExternalOperationReady(operation);
            if (readinessFailure != BackpackOperationFailure.NONE) {
                adapter.finish(operation);
                if (player.isOnline()) {
                    plugin.getMessageService().sendCancelled(player);
                }
                return;
            }
            Optional<BackpackSnapshot> snapshotOptional = adapter.loadSnapshot(player.getUniqueId());
            if (snapshotOptional.isEmpty()) {
                adapter.finish(operation);
                return;
            }
            PlayerBackpackTaskContext context = new PlayerBackpackTaskContext(
                    adapter, operation, snapshotOptional.get());
            // 喵~防御：v1 callback 回来时 context 注册入口可能已关闭或 operation 已被生命周期释放喵~
            if (!playerBackpackTaskContexts.register(player.getUniqueId(), context)) {
                // 从 pending 表移除本 operation，避免 releaseAll 重复释放喵~
                playerBackpackTaskContexts.removePending(player.getUniqueId(), operation);
                // 关闭未登记 context，幂等释放 provider token 喵~
                context.close();
                return;
            }
            // v1 context 已接管 operation，移除预备资源登记喵~
            playerBackpackTaskContexts.removePending(player.getUniqueId(), operation);
            // 创建后续 AutoChest 任务喵~
            afterFreeze.run();
        });
        return true;
    }

    // 使用 v2 async API 保存关闭 GUI、确认 readiness 并在下一 tick 建立固定 backend 会话喵~
    private void beginAsyncPlayerBackpackThenNextTick(Player player, OperationType operationType,
                                                       PlayerBackpackAsyncAdapter asyncAdapter, Runnable afterFreeze) {
        // 喵~防御：预备参数缺失时不能预约或冻结任意背包喵~
        if (player == null || asyncAdapter == null || afterFreeze == null) {
            // 结束不完整预备流程喵~
            return;
        }
        // 主线程 capture 不可变玩家身份，异步 callback 不再读取易变 Player 对象喵~
        java.util.UUID playerId = player.getUniqueId();
        // 保存已预约 operation，异常 completion 也能进入统一释放出口喵~
        java.util.concurrent.atomic.AtomicReference<BackpackOperation> begunOperation =
                new java.util.concurrent.atomic.AtomicReference<>();
        // 异步预约外部 operation，不阻塞 Bukkit 主线程喵~
        asyncAdapter.tryBeginOperationAsync(playerId, playerId, operationType.name().toLowerCase(Locale.ROOT))
                .thenCompose(operationOptional -> {
                    // 预约失败时返回空值，由主线程发送冲突消息喵~
                    if (operationOptional == null || operationOptional.isEmpty()) {
                        // 维持 optional 链路，不进入 GUI 操作喵~
                        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.<BackpackOperation>empty());
                    }
                    // 保存 operation，之后 GUI 关闭成功才会在 provider 侧激活 token 喵~
                    BackpackOperation operation = operationOptional.get();
                    // 记录已预约 operation，后续任一异常出口都可释放它喵~
                    begunOperation.set(operation);
                    // 将预备 operation 纳入统一生命周期表，覆盖 context 注册前停服竞态喵~
                    if (!playerBackpackTaskContexts.registerPending(playerId, asyncAdapter, operation)) {
                        return asyncAdapter.finishOperationAsync(operation)
                                .thenApply(ignored -> java.util.Optional.<BackpackOperation>empty());
                    }
                    // 异步保存关闭目标 GUI 喵~
                    return asyncAdapter.saveAndCloseOpenGuiAsync(operation).thenCompose(failure -> {
                        // GUI 冻结未成功时释放预约，禁止 load 或 mutation 喵~
                        if (failure != BackpackOperationFailure.NONE) {
                            // 释放尚未激活或已部分激活的 operation 喵~
                            return asyncAdapter.finishOperationAsync(operation)
                                    .thenApply(ignored -> java.util.Optional.<BackpackOperation>empty());
                        }
                        // 只有 GUI 关闭完成后才允许下一 tick 执行 readiness 与 snapshot 读取喵~
                        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.of(operation));
                    });
                }).whenComplete((preparedOperation, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    // 喵~防御：插件停用、玩家离线或异步失败时不得创建后继任务喵~
                    if (!plugin.isEnabled() || failure != null || preparedOperation == null || preparedOperation.isEmpty()
                            || !player.isOnline() || player.isDead()) {
                        // 异常 stage 可能丢失 Optional，使用外层引用释放已经预约的 operation 喵~
                        BackpackOperation operationToRelease = preparedOperation != null && preparedOperation.isPresent()
                                ? preparedOperation.get() : begunOperation.get();
                        // 异步阶段已有 operation 时必须释放 token 或 reservation，避免目标永久锁定喵~
                        if (operationToRelease != null) {
                            // 先移除预备资源登记，避免释放完成后被停服路径再次提交喵~
                            playerBackpackTaskContexts.removePending(playerId, operationToRelease);
                            // 释放玩家离线、插件停用或异常完成时遗留的 v2 operation 喵~
                            asyncAdapter.finishOperationAsync(operationToRelease);
                        }
                        // 仅在线玩家接收保守取消提示喵~
                        if (player.isOnline()) {
                            // 提示异步预备未完成喵~
                            plugin.getMessageService().sendCancelled(player);
                        }
                        // 结束失败预备流程喵~
                        return;
                    }
                    // 读取已保存关闭 GUI 后仍归当前任务拥有的 operation 喵~
                    BackpackOperation operation = preparedOperation.get();
                    // 下一 tick 再确认 GUI 关闭，避免 close event 延迟或其他插件取消关闭喵~
                    Bukkit.getScheduler().runTask(plugin, () -> asyncAdapter.confirmExternalOperationReadyAsync(operation)
                            .thenCompose(readinessFailure -> {
                                // readiness 失败时释放 token，禁止读取或写入背包喵~
                                if (readinessFailure != BackpackOperationFailure.NONE) {
                                    // 返回空 snapshot，完成回调统一取消喵~
                                    return asyncAdapter.finishOperationAsync(operation)
                                            .thenApply(ignored -> java.util.Optional.<BackpackSnapshot>empty());
                                }
                                // actor load 不访问 Bukkit，快照 DTO 解码由 adapter 投递主线程喵~
                                return asyncAdapter.loadSnapshotAsync(playerId,
                                        runnable -> Bukkit.getScheduler().runTask(plugin, runnable));
                            }).whenComplete((snapshotOptional, snapshotFailure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                                // 喵~防御：插件、玩家、操作或快照任一失效时释放固定 backend 会话喵~
                                if (!plugin.isEnabled() || snapshotFailure != null || snapshotOptional == null
                                        || snapshotOptional.isEmpty() || !player.isOnline() || player.isDead()) {
                                    // 尝试释放所有失败出口仍持有的 v2 token 喵~
                                    asyncAdapter.finishOperationAsync(operation);
                                    // 仅在线玩家接收取消提示喵~
                                    if (player.isOnline()) {
                                        // 提示异步快照未建立喵~
                                        plugin.getMessageService().sendCancelled(player);
                                    }
                                    // 结束失败路径喵~
                                    return;
                                }
                        // 使用 v2 adapter 构造固定 backend context，后续 mutation 禁止切回 v1 喵~
                                PlayerBackpackTaskContext context = new PlayerBackpackTaskContext(asyncAdapter, operation,
                                        snapshotOptional.get());
                                // 原子转移 pending operation 与完整 context，避免停服竞态产生资源空窗喵~
                                if (!playerBackpackTaskContexts.adoptPending(playerId, operation, context)) {
                                    // 转移失败时关闭未登记 context 并释放 operation 喵~
                                    context.close();
                                    // 结束冲突路径喵~
                                    return;
                                }
                                // 创建实际 AutoChest 扫描与跨域任务喵~
                                afterFreeze.run();
                            })));
                }));
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
                try {
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
                                Player completedPlayer = Bukkit.getPlayer(task.getPlayerUuid());
                                if (completedPlayer != null && completedPlayer.isOnline()) {
                                    if (stats.itemsMoved == 0) {
                                        plugin.getMessageService().sendNoMatch(completedPlayer);
                                    } else {
                                        plugin.getMessageService().sendDepositDone(completedPlayer,
                                                stats.itemsMoved, stats.containersUsed, stats.skipped);
                                    }
                                }
                                finishTask(task);
                            }

                            @Override
                            public void onCancelled() {
                                Player cancelledPlayer = Bukkit.getPlayer(task.getPlayerUuid());
                                if (cancelledPlayer != null && cancelledPlayer.isOnline()) {
                                    plugin.getMessageService().sendCancelled(cancelledPlayer);
                                }
                                finishTask(task);
                            }
                        });
                    });
                } catch (Throwable planningFailure) {
                    // 喵~防御：规划线程任意异常都必须回主线程释放任务，避免永久占用玩家锁喵~
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        // 任务可能已被生命周期事件释放，finishTask 保持 token 条件幂等喵~
                        finishTask(task);
                        // 在线玩家收到取消提示，明确本次规划失败喵~
                        Player failedPlayer = Bukkit.getPlayer(task.getPlayerUuid());
                        if (failedPlayer != null && failedPlayer.isOnline()) {
                            plugin.getMessageService().sendCancelled(failedPlayer);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池队列已满
            if (player.isOnline()) {
                plugin.getMessageService().sendServerBusy(player);
            }
            finishTask(task);
        }
    }

    // 统计当前外部会话快照中容量内已有非满 PlayerBackpack 补货目标喵~
    private int playerBackpackTargetCount(PlayerTask task) {
        // 喵~防御：任务或外部会话表缺失时不存在 PlayerBackpack 补货目标喵~
        if (task == null || playerBackpackTaskContexts == null) {
            // 返回零目标保持原版补货判定喵~
            return 0;
        }
        // 读取当前任务持有的唯一 PlayerBackpack 外部会话喵~
        PlayerBackpackTaskContext context = playerBackpackTaskContexts.get(task.getPlayerUuid());
        // 喵~防御：会话缺失或已释放时不得读取陈旧 PlayerBackpack 快照喵~
        if (context == null || !context.isOpen()) {
            // 返回零目标喵~
            return 0;
        }
        // 初始化可补货逻辑槽位计数喵~
        int targetCount = 0;
        // 遍历快照中按升序保存的所有物品槽位喵~
        for (Integer logicalSlot : context.snapshot().items().navigableKeySet()) {
            // 跳过空键、非法键和容量外 overflow 槽位喵~
            if (logicalSlot == null || logicalSlot <= 0 || logicalSlot > context.snapshot().capacity()) {
                continue;
            }
            // 读取隔离后的目标物品副本喵~
            ItemStack targetItem = ContainerTransaction.cloneOrNull(context.snapshot().itemAt(logicalSlot));
            // 仅统计已有且未达到最大堆叠数的容量内槽位喵~
            if (targetItem != null && targetItem.getAmount() < targetItem.getMaxStackSize()) {
                // 增加一个可补货 PlayerBackpack 目标喵~
                targetCount++;
            }
        }
        // 返回准确的 PlayerBackpack 补货目标数量喵~
        return targetCount;
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

        // 只有没有任何容器来源，或两套目标都为空时才提前结束补货喵~
        if (containers.isEmpty()
                || (whitelist.eligibleSlotsSorted().isEmpty() && playerBackpackTargetCount(task) == 0)) {
            plugin.getMessageService().sendNoMatch(player);
            restockListener.stopTracking(task.getPlayerUuid(), whitelist);
            finishTask(task);
            return;
        }

        // restock 保持全部距离排序容器候选，确保低序目标槽位优先分配稀缺来源。
        List<ContainerIdentity> containerIdentities = new ArrayList<>(containers);

        try {
            plugin.getExecutor().submit(() -> {
                try {
                    // 异步线程只执行纯数据规划，不访问 Bukkit 对象。
                    PlanResult plan = planner.planForRestock(containerIdentities);
                    // 规划完成后切回主线程执行补货。
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
                                Player completedPlayer = Bukkit.getPlayer(task.getPlayerUuid());
                                if (completedPlayer != null && completedPlayer.isOnline()) {
                                    if (stats.itemsMoved == 0) {
                                        plugin.getMessageService().sendNoMatch(completedPlayer);
                                    } else {
                                        plugin.getMessageService().sendRestockDone(completedPlayer,
                                                stats.itemsMoved, stats.containersUsed, stats.skipped);
                                    }
                                }
                                finishTask(task);
                            }

                            @Override
                            public void onCancelled() {
                                restockListener.stopTracking(task.getPlayerUuid(), whitelist);
                                Player cancelledPlayer = Bukkit.getPlayer(task.getPlayerUuid());
                                if (cancelledPlayer != null && cancelledPlayer.isOnline()) {
                                    plugin.getMessageService().sendCancelled(cancelledPlayer);
                                }
                                finishTask(task);
                            }
                        });
                    });
                } catch (Throwable planningFailure) {
                    // 喵~防御：规划线程任意异常都必须回主线程释放任务，避免永久占用玩家锁喵~
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        // 停止白名单跟踪，避免规划失败后残留补货状态喵~
                        restockListener.stopTracking(task.getPlayerUuid(), whitelist);
                        // 任务可能已经被生命周期事件释放，finishTask 保持幂等喵~
                        finishTask(task);
                        // 在线玩家收到取消提示，明确本次规划失败喵~
                        Player failedPlayer = Bukkit.getPlayer(task.getPlayerUuid());
                        if (failedPlayer != null && failedPlayer.isOnline()) {
                            plugin.getMessageService().sendCancelled(failedPlayer);
                        }
                    });
                }
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
        // 仅 token 匹配并实际释放当前任务时才允许释放关联的 PlayerBackpack context 喵~
        if (!registry.release(task.getPlayerUuid(), task.getToken())) {
            return;
        }
        // 仅 token/epoch 同时匹配时才释放关联 PlayerBackpack context 喵~
        PlayerBackpackTaskContext context = playerBackpackTaskContexts.get(task.getPlayerUuid());
        // 喵~防御：旧任务 callback 不得读取或关闭新 session context 喵~
        if (context != null && context.belongsTo(task.getToken(), task.getSessionEpoch())) {
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
