package io.github.autochest.integration.playerbackpack;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;

public final class PlayerBackpackHook implements Listener {

    private static final String SYNC_API_CLASS_NAME = "com.playerbackpack.api.PlayerBackpackApi";
    private static final String ASYNC_API_CLASS_NAME = "com.playerbackpack.api.v2.PlayerBackpackAsyncApi";
    private static final int REQUIRED_API_MAJOR = 1;
    private static final int REQUIRED_ASYNC_API_MAJOR = 2;
    private final Logger logger;
    private Object api;
    private Object asyncApi;
    private String unavailableReason;

    public PlayerBackpackHook(Logger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("日志对象不能为空喵~");
        }
        this.logger = logger;
        Plugin playerBackpackPlugin = Bukkit.getPluginManager().getPlugin("PlayerBackpack");
        if (playerBackpackPlugin == null || !playerBackpackPlugin.isEnabled()) {
            markUnavailable("PlayerBackpack 插件缺失或未启用喵~", null);
            return;
        }
        try {
            ClassLoader pluginClassLoader = playerBackpackPlugin.getClass().getClassLoader();
            Class<?> apiClass = Class.forName(SYNC_API_CLASS_NAME, false, pluginClassLoader);
            Object discoveredApi = Bukkit.getServicesManager().load(apiClass);
            if (discoveredApi != null && isCompatible(discoveredApi)) {
                api = discoveredApi;
            }
            // v2 provider 独立发现，只有明确 supportsWriteOperations 且 ready 才作为可写 backend 喵~
            Class<?> asyncApiClass = Class.forName(ASYNC_API_CLASS_NAME, false, pluginClassLoader);
            Object discoveredAsyncApi = Bukkit.getServicesManager().load(asyncApiClass);
            if (discoveredAsyncApi != null && isAsyncCompatible(discoveredAsyncApi)) {
                asyncApi = discoveredAsyncApi;
            }
            if (api == null && asyncApi == null) {
                markUnavailable("PlayerBackpack API 未注册、不可用或主版本不兼容喵~", null);
                return;
            }
            unavailableReason = null;
            logger.info("[AutoChest] 已通过反射连接 PlayerBackpack API 喵~");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            markUnavailable("PlayerBackpack API 反射发现失败喵~", exception);
        }
    }

    public boolean isAvailable() {
        return api != null || asyncApi != null;
    }

    // 判断已发现 v2 provider 是否明确承诺安全异步写操作喵~
    public boolean supportsAsyncWriteOperations() {
        // 只有完整 v2 provider 才可作为跨域异步 backend 喵~
        return asyncApi != null;
    }

    // 返回固定当前会话 backend 的同步 v1 adapter，v2 可写时调用方应优先异步路径喵~
    public PlayerBackpackAdapter adapter() {
        return api != null ? new PlayerBackpackAdapter(api, logger) : null;
    }

    // 返回当前可写 v2 provider 原始对象，调用方必须经独立反射 adapter 使用喵~
    public Object asyncApi() {
        return asyncApi;
    }

    // 创建当前会话使用的 v2 异步 adapter，provider 缺失时返回空值喵~
    public PlayerBackpackAsyncAdapter asyncAdapter() {
        // 喵~防御：只读或未就绪 provider 不得创建可写 backend 喵~
        return asyncApi == null ? null : new PlayerBackpackAsyncAdapter(asyncApi, logger);
    }

    public String unavailableReason() {
        return unavailableReason == null ? "" : unavailableReason;
    }

    // 监听 PlayerBackpack API 延迟注册，立即重新发现 provider 喵~
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceRegister(ServiceRegisterEvent event) {
        // 仅处理 PlayerBackpack API 服务，避免无关插件事件触发刷新喵~
        if (SYNC_API_CLASS_NAME.equals(event.getProvider().getService().getName())
                || ASYNC_API_CLASS_NAME.equals(event.getProvider().getService().getName())) {
            refresh();
        }
    }

    // 监听 PlayerBackpack API 撤销，立即清空旧 provider 喵~
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        // 仅处理 PlayerBackpack API 服务，避免无关插件事件触发刷新喵~
        if (SYNC_API_CLASS_NAME.equals(event.getProvider().getService().getName())
                || ASYNC_API_CLASS_NAME.equals(event.getProvider().getService().getName())) {
            markUnavailable("PlayerBackpack API 服务已撤销喵~", null);
        }
    }

    // 重新发现当前服务并隔离 provider 兼容性异常喵~
    private synchronized void refresh() {
        // 喵~防御：PlayerBackpack 插件缺失或停用时立即关闭扩展能力喵~
        Plugin playerBackpackPlugin = Bukkit.getPluginManager().getPlugin("PlayerBackpack");
        if (playerBackpackPlugin == null || !playerBackpackPlugin.isEnabled()) {
            markUnavailable("PlayerBackpack 插件缺失或未启用喵~", null);
            return;
        }
        try {
            // 使用 provider 所属插件 classloader 加载 API 类型，避免 AutoChest 静态链接可选类喵~
            ClassLoader pluginClassLoader = playerBackpackPlugin.getClass().getClassLoader();
            Class<?> apiClass = Class.forName(SYNC_API_CLASS_NAME, false, pluginClassLoader);
            Object discoveredApi = Bukkit.getServicesManager().load(apiClass);
            api = discoveredApi != null && isCompatible(discoveredApi) ? discoveredApi : null;
            // 重新发现 v2 provider，并丢弃不具备完整写能力的只读实现喵~
            Class<?> asyncApiClass = Class.forName(ASYNC_API_CLASS_NAME, false, pluginClassLoader);
            Object discoveredAsyncApi = Bukkit.getServicesManager().load(asyncApiClass);
            asyncApi = discoveredAsyncApi != null && isAsyncCompatible(discoveredAsyncApi) ? discoveredAsyncApi : null;
            if (api == null && asyncApi == null) {
                markUnavailable("PlayerBackpack API 未注册、不可用或主版本不兼容喵~", null);
                return;
            }
            unavailableReason = null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            markUnavailable("PlayerBackpack API 反射发现失败喵~", exception);
        }
    }

    private boolean isCompatible(Object discoveredApi) throws ReflectiveOperationException {
        // 读取 provider 的公开可用性状态喵~
        Method availableMethod = discoveredApi.getClass().getMethod("isAvailable");
        // 读取 provider 的公开版本信息喵~
        Method versionMethod = discoveredApi.getClass().getMethod("apiVersion");
        // 调用 provider 可用性检查喵~
        Object availableValue = availableMethod.invoke(discoveredApi);
        // 调用 provider 版本查询喵~
        Object versionValue = versionMethod.invoke(discoveredApi);
        // 喵~防御：服务不可用或版本对象为空时不启用跨域功能喵~
        if (!(availableValue instanceof Boolean available) || !available || versionValue == null) {
            // 报告当前 provider 不兼容喵~
            return false;
        }
        // 获取版本对象的主版本兼容性方法喵~
        Method supportsMajorMethod = versionValue.getClass().getMethod("supportsMajor", int.class);
        // 调用 API 主版本兼容性检查喵~
        Object supportedValue = supportsMajorMethod.invoke(versionValue, REQUIRED_API_MAJOR);
        // 仅明确支持的 provider 可进入跨域流程喵~
        return supportedValue instanceof Boolean supported && supported;
    }

    // 判断 v2 provider 主版本、可写 capability 和异步 readiness 是否全部满足喵~
    private boolean isAsyncCompatible(Object discoveredAsyncApi) throws ReflectiveOperationException {
        // 读取 v2 主版本方法喵~
        Method majorMethod = discoveredAsyncApi.getClass().getMethod("apiMajorVersion");
        // 读取显式写能力方法喵~
        Method supportsWriteMethod = discoveredAsyncApi.getClass().getMethod("supportsWriteOperations");
        // 读取异步就绪方法喵~
        Method readyMethod = discoveredAsyncApi.getClass().getMethod("isReadyAsync");
        // 校验协议主版本喵~
        Object majorValue = majorMethod.invoke(discoveredAsyncApi);
        // 校验写能力 capability 喵~
        Object supportsWriteValue = supportsWriteMethod.invoke(discoveredAsyncApi);
        // 调用 readiness stage，发现阶段只允许短暂查询，不在 gameplay 事件中阻塞喵~
        Object readyStage = readyMethod.invoke(discoveredAsyncApi);
        // 喵~防御：版本、写能力、CompletionStage 和已完成 ready 值缺一不可喵~
        if (!(majorValue instanceof Number major) || major.intValue() != REQUIRED_ASYNC_API_MAJOR
                || !(supportsWriteValue instanceof Boolean supportsWrite) || !supportsWrite
                || !(readyStage instanceof java.util.concurrent.CompletionStage<?> completionStage)) {
            // 拒绝只读或未知异步 provider 喵~
            return false;
        }
        // 只在发现阶段读取已完成 future，禁止将 join 复制到 gameplay mutation path 喵~
        java.util.concurrent.CompletableFuture<?> readyFuture = completionStage.toCompletableFuture();
        // 未完成 readiness 不锁主线程，后续 service register 事件重新发现喵~
        if (!readyFuture.isDone()) {
            // 返回 false，避免未就绪 provider 进入 gameplay 写入喵~
            return false;
        }
        // 读取已经完成的 readiness 值并严格要求 true 喵~
        Object readyValue = readyFuture.getNow(null);
        // 只有明确 Boolean true 才允许异步写 backend 喵~
        return Boolean.TRUE.equals(readyValue);
    }

    // 插件停用时清空 provider，迟到 callback 保持固定 context 但不能发现新 backend 喵~
    public synchronized void disable() {
        // 清除 v1 与 v2 provider，阻止停用期间再创建 adapter 喵~
        api = null;
        // 清除异步 provider 引用喵~
        asyncApi = null;
        // 记录停用原因供命令层诊断喵~
        unavailableReason = "AutoChest 正在停用喵~";
    }

    private void markUnavailable(String reason, Throwable cause) {
        api = null;
        asyncApi = null;
        unavailableReason = reason;
        if (cause == null) {
            logger.warning("[AutoChest] " + reason + "将仅处理原版玩家背包喵~");
            return;
        }
        logger.log(Level.WARNING, "[AutoChest] " + reason + "将仅处理原版玩家背包喵~", cause);
    }
}
