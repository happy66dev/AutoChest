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

    private static final String API_CLASS_NAME = "com.playerbackpack.api.PlayerBackpackApi";
    private static final int REQUIRED_API_MAJOR = 1;
    private final Logger logger;
    private Object api;
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
            Class<?> apiClass = Class.forName(API_CLASS_NAME, false, pluginClassLoader);
            Object discoveredApi = Bukkit.getServicesManager().load(apiClass);
            if (discoveredApi == null || !isCompatible(discoveredApi)) {
                markUnavailable("PlayerBackpack API 未注册、不可用或主版本不兼容喵~", null);
                return;
            }
            api = discoveredApi;
            unavailableReason = null;
            logger.info("[AutoChest] 已通过反射连接 PlayerBackpack API 喵~");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            markUnavailable("PlayerBackpack API 反射发现失败喵~", exception);
        }
    }

    public boolean isAvailable() {
        return api != null;
    }

    public PlayerBackpackAdapter adapter() {
        return isAvailable() ? new PlayerBackpackAdapter(api, logger) : null;
    }

    public String unavailableReason() {
        return unavailableReason == null ? "" : unavailableReason;
    }

    // 监听 PlayerBackpack API 延迟注册，立即重新发现 provider 喵~
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceRegister(ServiceRegisterEvent event) {
        // 仅处理 PlayerBackpack API 服务，避免无关插件事件触发刷新喵~
        if (API_CLASS_NAME.equals(event.getProvider().getService().getName())) {
            refresh();
        }
    }

    // 监听 PlayerBackpack API 撤销，立即清空旧 provider 喵~
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        // 仅处理 PlayerBackpack API 服务，避免无关插件事件触发刷新喵~
        if (API_CLASS_NAME.equals(event.getProvider().getService().getName())) {
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
            Class<?> apiClass = Class.forName(API_CLASS_NAME, false, pluginClassLoader);
            Object discoveredApi = Bukkit.getServicesManager().load(apiClass);
            if (discoveredApi == null || !isCompatible(discoveredApi)) {
                markUnavailable("PlayerBackpack API 未注册、不可用或主版本不兼容喵~", null);
                return;
            }
            api = discoveredApi;
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

    private void markUnavailable(String reason, Throwable cause) {
        api = null;
        unavailableReason = reason;
        if (cause == null) {
            logger.warning("[AutoChest] " + reason + "将仅处理原版玩家背包喵~");
            return;
        }
        logger.log(Level.WARNING, "[AutoChest] " + reason + "将仅处理原版玩家背包喵~", cause);
    }
}
