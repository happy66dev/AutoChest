package io.github.autochest.integration.playerbackpack;

// 导入稳定 PlayerBackpack API 版本模型喵~
import com.playerbackpack.api.ApiVersion;
// 导入稳定 PlayerBackpack API 接口喵~
import com.playerbackpack.api.PlayerBackpackApi;
// 导入 Bukkit 服务注册查询类型喵~
import org.bukkit.Bukkit;
// 导入插件类型以检查可选插件生命周期喵~
import org.bukkit.plugin.Plugin;
// 导入日志类型以输出一次性兼容诊断喵~
import java.util.logging.Logger;

// 负责发现和校验可选 PlayerBackpack API，失败时安全回退原版功能喵~
public final class PlayerBackpackHook {

    // 当前 AutoChest 编译契约固定支持 API 主版本一喵~
    private static final int REQUIRED_API_MAJOR = 1;
    // 保存日志依赖以输出缺失或版本不兼容原因喵~
    private final Logger logger;
    // 保存发现到的 API 服务，未发现时保持空值喵~
    private PlayerBackpackApi api;
    // 保存一次性诊断文本，避免每次命令重复刷屏喵~
    private String unavailableReason;

    // 创建 PlayerBackpack Hook 并立即完成只读服务发现喵~
    public PlayerBackpackHook(Logger logger) {
        // 喵~防御：日志不能为空，避免兼容失败时再次发生空指针异常喵~
        if (logger == null) {
            // 拒绝没有日志通道的 Hook 喵~
            throw new IllegalArgumentException("日志对象不能为空喵~");
        }
        // 保存日志依赖喵~
        this.logger = logger;
        // 查询可选 PlayerBackpack 插件是否存在喵~
        Plugin playerBackpackPlugin = Bukkit.getPluginManager().getPlugin("PlayerBackpack");
        // 喵~防御：插件不存在或未启用时不访问其服务喵~
        if (playerBackpackPlugin == null || !playerBackpackPlugin.isEnabled()) {
            // 记录不可用原因并回退原版能力喵~
            this.api = null;
            // 保存可诊断的禁用原因喵~
            this.unavailableReason = "PlayerBackpack 插件缺失或未启用喵~";
            // 输出一次性警告喵~
            logger.warning("[AutoChest] " + unavailableReason + "将仅处理原版玩家背包喵~");
            // 结束无服务构造喵~
            return;
        }
        // 通过 Bukkit 服务管理器查询稳定 API 实现喵~
        PlayerBackpackApi discoveredApi = Bukkit.getServicesManager().load(PlayerBackpackApi.class);
        // 喵~防御：服务未注册时不反射内部类，也不猜测插件实现喵~
        if (discoveredApi == null) {
            // 保存无服务回退状态喵~
            this.api = null;
            // 保存可诊断的服务缺失原因喵~
            this.unavailableReason = "PlayerBackpack API 服务未注册喵~";
            // 输出一次性警告喵~
            logger.warning("[AutoChest] " + unavailableReason + "将仅处理原版玩家背包喵~");
            // 结束无服务构造喵~
            return;
        }
        // 读取服务公开的 API 版本并隔离第三方实现异常喵~
        try {
            // 查询 provider 的版本信息喵~
            ApiVersion discoveredVersion = discoveredApi.apiVersion();
            // 喵~防御：版本对象为空或主版本不匹配时拒绝扩展写入喵~
            if (discoveredVersion == null || !discoveredVersion.supportsMajor(REQUIRED_API_MAJOR) || !discoveredApi.isAvailable()) {
                // 保存不兼容服务回退状态喵~
                this.api = null;
                // 保存可诊断的版本或可用性原因喵~
                this.unavailableReason = "PlayerBackpack API 不可用或主版本不兼容喵~";
                // 输出一次性警告喵~
                logger.warning("[AutoChest] " + unavailableReason + "将仅处理原版玩家背包喵~");
                // 结束无服务构造喵~
                return;
            }
            // 保存经过版本校验的稳定 API 服务喵~
            this.api = discoveredApi;
            // 清空不可用原因表示扩展能力已准备喵~
            this.unavailableReason = null;
            // 输出扩展能力启用日志喵~
            logger.info("[AutoChest] 已连接 PlayerBackpack API " + discoveredVersion + "喵~");
        } catch (RuntimeException | LinkageError compatibilityException) {
            // 喵~防御：第三方 provider ABI 或初始化异常时只关闭扩展能力喵~
            this.api = null;
            // 保存异常类型而不继续调用损坏服务喵~
            this.unavailableReason = "PlayerBackpack API provider 校验失败喵~";
            // 记录异常并安全回退原版流程喵~
            logger.log(java.util.logging.Level.WARNING, "[AutoChest] " + unavailableReason + "将仅处理原版玩家背包喵~", compatibilityException);
        }
    }

    // 判断 PlayerBackpack 扩展是否可用喵~
    public boolean isAvailable() {
        // 只有发现并通过版本校验的服务才可使用喵~
        return api != null;
    }

    // 返回已校验的 API 服务，调用方必须先检查 isAvailable 喵~
    public PlayerBackpackApi api() {
        // 返回稳定 API 引用，不暴露任何内部实现喵~
        return api;
    }

    // 返回不可用诊断文本，服务可用时返回空字符串喵~
    public String unavailableReason() {
        // 避免返回 null 让命令消息处理更安全喵~
        return unavailableReason == null ? "" : unavailableReason;
    }
}
