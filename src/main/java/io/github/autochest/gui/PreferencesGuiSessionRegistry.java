package io.github.autochest.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家容器偏好 GUI 会话注册表。
 * 所有方法只允许在 Bukkit 主线程调用。
 */
public final class PreferencesGuiSessionRegistry {

    /** 每位玩家当前有效的 GUI 会话令牌。 */
    private final Map<UUID, UUID> currentSessionTokens = new HashMap<>();

    /**
     * 为玩家创建并登记新的 GUI 会话令牌。
     *
     * @param playerUuid 玩家 UUID。
     * @return 新创建的不可预测会话令牌。
     */
    public UUID begin(UUID playerUuid) {
        // 喵~防御：空玩家 UUID 无法建立安全会话。
        if (playerUuid == null) {
            throw new IllegalArgumentException("玩家 UUID 不能为空");
        }
        // 生成新的随机会话令牌以使旧页面立即失效。
        UUID sessionToken = UUID.randomUUID();
        // 覆盖该玩家的旧会话令牌。
        currentSessionTokens.put(playerUuid, sessionToken);
        // 返回新令牌供 Holder 固化。
        return sessionToken;
    }

    /**
     * 判断 Holder 中的会话是否仍是该玩家当前会话。
     *
     * @param playerUuid 玩家 UUID。
     * @param sessionToken Holder 中固化的令牌。
     * @return true 表示点击可继续处理。
     */
    public boolean isCurrent(UUID playerUuid, UUID sessionToken) {
        // 空参数不能匹配有效会话。
        if (playerUuid == null || sessionToken == null) {
            return false;
        }
        // 精确比较登记令牌，避免旧界面修改新会话。
        return sessionToken.equals(currentSessionTokens.get(playerUuid));
    }

    /**
     * 仅当令牌匹配当前会话时清理登记。
     *
     * @param playerUuid 玩家 UUID。
     * @param sessionToken 即将关闭页面的令牌。
     */
    public void clearIfCurrent(UUID playerUuid, UUID sessionToken) {
        // 空参数不能安全清理任何会话。
        if (playerUuid == null || sessionToken == null) {
            return;
        }
        // 条件删除避免旧关闭事件误删刚打开的新页面。
        currentSessionTokens.remove(playerUuid, sessionToken);
    }

    /**
     * 使指定玩家当前 GUI 会话失效。
     *
     * @param playerUuid 玩家 UUID。
     */
    public void invalidate(UUID playerUuid) {
        // 空 UUID 没有可清理会话。
        if (playerUuid == null) {
            return;
        }
        // 移除该玩家当前令牌。
        currentSessionTokens.remove(playerUuid);
    }

    /** 使所有 GUI 会话立即失效。 */
    public void clear() {
        // 清空全部会话以配合插件停用。
        currentSessionTokens.clear();
    }
}
