package io.github.autochest.command;

import io.github.autochest.AutoChestPlugin;
import io.github.autochest.config.CooldownService;
import io.github.autochest.config.MessageService;
import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.hook.CompositeAccessPolicy;
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
            ContainerTransaction containerTransaction
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
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (String sub : List.of("deposit", "restock", "reload")) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
            return completions;
        }
        return Collections.emptyList();
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

        // 尝试创建任务（CAS 插入，保证不竞争）
        Optional<PlayerTask> taskOpt = registry.tryAcquire(
                player.getUniqueId(),
                OperationType.DEPOSIT,
                plugin.getCurrentConfig(),
                player.getWorld().getUID(),
                player.getLocation().getBlockX(),
                player.getLocation().getBlockY(),
                player.getLocation().getBlockZ()
        );

        if (taskOpt.isEmpty()) {
            // tryAcquire 失败（并发下极小概率），视为任务冲突
            messages.sendTaskConflict(player);
            return;
        }

        PlayerTask task = taskOpt.get();
        // 命令接受即消费冷却，不因后续任何原因退还
        cooldownService.record(player.getUniqueId(), CooldownService.OperationType.DEPOSIT);

        // 发送扫描开始提示
        messages.sendScanStarted(player);

        // 启动分 tick 扫描
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

        Optional<PlayerTask> taskOpt = registry.tryAcquire(
                player.getUniqueId(),
                OperationType.RESTOCK,
                plugin.getCurrentConfig(),
                player.getWorld().getUID(),
                player.getLocation().getBlockX(),
                player.getLocation().getBlockY(),
                player.getLocation().getBlockZ()
        );

        if (taskOpt.isEmpty()) {
            messages.sendTaskConflict(player);
            return;
        }

        PlayerTask task = taskOpt.get();
        cooldownService.record(player.getUniqueId(), CooldownService.OperationType.RESTOCK);

        // 开始追踪玩家背包变化，标记失效槽位
        restockListener.startTracking(player.getUniqueId());

        messages.sendScanStarted(player);

        startRestockScan(task, whitelist);
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
                    restockListener.stopTracking(task.getPlayerUuid());
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

        // 主线程生成库存快照 DTO（Bukkit-free）
        InventorySnapshotFactory.PlayerInventoryDto playerDto =
                snapshotFactory.snapshotPlayer(player, 9, 35);

        List<InventorySnapshotFactory.ContainerDto> containerDtos = new ArrayList<>();
        for (ContainerIdentity identity : containers) {
            org.bukkit.inventory.Inventory inv = getInventorySafely(identity, player.getWorld());
            if (inv != null) {
                containerDtos.add(snapshotFactory.snapshotContainer(identity, inv));
            }
        }

        // 提交异步规划
        try {
            plugin.getExecutor().submit(() -> {
                // 在异步线程中仅做排序和候选索引，不访问任何 Bukkit 对象
                PlanResult plan = planner.plan(playerDto, containerDtos);
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
            restockListener.stopTracking(task.getPlayerUuid());
            finishTask(task);
            return;
        }

        Player player = Bukkit.getPlayer(task.getPlayerUuid());
        if (player == null || !player.isOnline() || player.isDead()) {
            restockListener.stopTracking(task.getPlayerUuid());
            finishTask(task);
            return;
        }

        if (containers.isEmpty() || whitelist.eligibleSlotsSorted().isEmpty()) {
            plugin.getMessageService().sendNoMatch(player);
            restockListener.stopTracking(task.getPlayerUuid());
            finishTask(task);
            return;
        }

        InventorySnapshotFactory.PlayerInventoryDto playerDto =
                snapshotFactory.snapshotPlayer(player, 0, 35);

        List<InventorySnapshotFactory.ContainerDto> containerDtos = new ArrayList<>();
        for (ContainerIdentity identity : containers) {
            org.bukkit.inventory.Inventory inv = getInventorySafely(identity, player.getWorld());
            if (inv != null) {
                containerDtos.add(snapshotFactory.snapshotContainer(identity, inv));
            }
        }

        try {
            plugin.getExecutor().submit(() -> {
                PlanResult plan = planner.plan(playerDto, containerDtos);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!registry.isValid(task)) {
                        restockListener.stopTracking(task.getPlayerUuid());
                        finishTask(task);
                        return;
                    }
                    restockService.execute(plan, task, whitelist, new RestockService.RestockCallback() {
                        @Override
                        public void onComplete(RestockService.RestockStats stats) {
                            restockListener.stopTracking(task.getPlayerUuid());
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
                            restockListener.stopTracking(task.getPlayerUuid());
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
            restockListener.stopTracking(task.getPlayerUuid());
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
        registry.release(task.getPlayerUuid(), task.getToken());
    }

    /**
     * 安全获取容器库存（仅验证区块和容器结构），区块未加载或容器失效时返回 null
     * 快照阶段无需再次执行 Hook 检查，提交阶段的 validate() 会完整重验
     *
     * @param identity 容器身份
     * @param world    世界
     * @return 库存，或 null
     */
    private org.bukkit.inventory.Inventory getInventorySafely(ContainerIdentity identity,
                                                               org.bukkit.World world) {
        try {
            return containerTransaction.getInventoryIfValid(identity, world);
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
        sender.sendMessage("§e/autochest reload §7- 重载配置文件");
    }
}
