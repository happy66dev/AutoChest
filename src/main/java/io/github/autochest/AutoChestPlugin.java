package io.github.autochest;

import io.github.autochest.command.AutoChestCommand;
import io.github.autochest.config.AutoChestConfig;
import io.github.autochest.config.CooldownService;
import io.github.autochest.config.MessageService;
import io.github.autochest.hook.*;
import io.github.autochest.preference.PlayerPreferencesService;
import io.github.autochest.scan.CandidatePlanner;
import io.github.autochest.scan.InventorySnapshotFactory;
import io.github.autochest.service.ContainerTransaction;
import io.github.autochest.service.DepositService;
import io.github.autochest.service.RestockService;
import io.github.autochest.task.PlayerLifecycleListener;
import io.github.autochest.task.PlayerTaskRegistry;
import io.github.autochest.task.RestockTargetListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * AutoChest 插件主类，负责所有模块的初始化和生命周期管理
 * 类似泰拉瑞亚的附近容器双向补货功能
 */
public class AutoChestPlugin extends JavaPlugin {

    /** 当前生效的配置快照，reload 后更新 */
    private AutoChestConfig currentConfig;

    /** 消息服务，持有配置快照的引用 */
    private MessageService messageService;

    /** 冷却服务 */
    private CooldownService cooldownService;

    /** 玩家任务注册表 */
    private PlayerTaskRegistry taskRegistry;

    /** 玩家容器偏好服务，负责 JSON 持久化与不可变任务快照 */
    private PlayerPreferencesService playerPreferencesService;

    /** 复合容器访问策略（聚合 WorldGuard、Towny、ChestShop、Slimefun 四个可选 Hook） */
    private CompositeAccessPolicy accessPolicy;

    /**
     * 插件私有有界线程池，仅用于异步规划
     * 不使用 common pool，防止影响其他插件的异步任务
     */
    private ThreadPoolExecutor executor;

    @Override
    public void onEnable() {
        // 步骤 1：保存默认配置文件（若 config.yml 不存在则从 JAR 内复制）
        saveDefaultConfig();

        // 步骤 2：构造配置快照
        currentConfig = new AutoChestConfig(getConfig());

        // 步骤 3：构造私有有界线程池
        // 使用命名线程工厂便于调试；有界队列满时拒绝新任务并返回服务器繁忙
        executor = new ThreadPoolExecutor(
                currentConfig.getExecutorPoolSize(),
                currentConfig.getExecutorPoolSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(currentConfig.getExecutorQueueSize()),
                r -> {
                    Thread t = new Thread(r, "AutoChest-Planner");
                    // 后台线程，不阻止 JVM 退出
                    t.setDaemon(true);
                    return t;
                },
                // 拒绝策略：直接抛出 RejectedExecutionException，由命令层转换为"服务器繁忙"
                new ThreadPoolExecutor.AbortPolicy()
        );

        // 步骤 4：构造玩家任务注册表（初始 generation=1）
        taskRegistry = new PlayerTaskRegistry();

        // 步骤 5：构造冷却服务、消息服务和玩家 JSON 偏好服务。
        cooldownService = new CooldownService(currentConfig);
        messageService = new MessageService(currentConfig);
        // 将玩家 JSON 数据固定保存到插件 data 目录，隔离全局 config.yml。
        playerPreferencesService = new PlayerPreferencesService(getDataFolder().toPath().resolve("data"), getLogger());

        // 步骤 6：初始化可选 Hook，构造复合访问策略
        List<ContainerAccessPolicy> policies = new ArrayList<>();
        policies.add(new WorldGuardHook(getLogger()));
        policies.add(new TownyHook(getLogger()));
        policies.add(new ChestShopHook(getLogger()));
        // 添加 Slimefun 机器与方块数据保护策略，避免自动访问其管理的容器。
        policies.add(new SlimefunHook(getLogger()));
        accessPolicy = new CompositeAccessPolicy(policies, getLogger());

        // 步骤 7：注册事件监听器
        RestockTargetListener restockListener = new RestockTargetListener();
        getServer().getPluginManager().registerEvents(
                new PlayerLifecycleListener(taskRegistry), this);
        getServer().getPluginManager().registerEvents(restockListener, this);

        // 步骤 8：构造服务层
        InventorySnapshotFactory snapshotFactory = new InventorySnapshotFactory();
        CandidatePlanner candidatePlanner = new CandidatePlanner();
        ContainerTransaction containerTransaction =
                new ContainerTransaction(taskRegistry, accessPolicy, getLogger());
        DepositService depositService =
                new DepositService(containerTransaction, taskRegistry, this, getLogger());
        RestockService restockService =
                new RestockService(containerTransaction, taskRegistry, this, getLogger());

        // 步骤 9：注册命令
        AutoChestCommand commandHandler = new AutoChestCommand(
                this, taskRegistry, cooldownService, accessPolicy,
                executor, snapshotFactory, candidatePlanner,
                depositService, restockService, restockListener,
                containerTransaction, playerPreferencesService
        );
        getCommand("autochest").setExecutor(commandHandler);
        getCommand("autochest").setTabCompleter(commandHandler);

        getLogger().info("AutoChest 已启动喵~ 版本 " + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        // 步骤 1：使 plugin generation 失效，所有迟到异步回调立即失效
        if (taskRegistry != null) {
            taskRegistry.disablePlugin();
        }

        // 步骤 2：停止线程池，等待最多 2 秒后强制停止
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 步骤 3：有界刷新玩家偏好 JSON，避免服务器关闭时丢失最后一次设置修改。
        if (playerPreferencesService != null) {
            playerPreferencesService.flushAndClose(2L);
        }

        // 步骤 4：清空冷却记录
        if (cooldownService != null) {
            cooldownService.clear();
        }

        getLogger().info("AutoChest 已关闭喵~");
    }

    /**
     * 重载插件配置文件
     * 生成新的配置快照，同步更新消息服务和冷却服务
     * 运行中的任务继续使用创建时的快照，不受影响
     */
    public void reloadPluginConfig() {
        reloadConfig();
        currentConfig = new AutoChestConfig(getConfig());
        messageService = new MessageService(currentConfig);
        cooldownService.updateConfig(currentConfig);
        getLogger().info("[AutoChest] 配置已重载喵~");
    }

    /**
     * 获取当前配置快照（供任务创建时使用）
     *
     * @return 当前 AutoChestConfig 实例
     */
    public AutoChestConfig getCurrentConfig() {
        return currentConfig;
    }

    /**
     * 获取消息服务
     *
     * @return MessageService 实例
     */
    public MessageService getMessageService() {
        return messageService;
    }

    /**
     * 获取异步规划线程池
     *
     * @return ExecutorService 实例
     */
    public ExecutorService getExecutor() {
        return executor;
    }
}

