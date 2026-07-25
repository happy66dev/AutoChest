package io.github.autochest.gui;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GUI 会话注册表测试。
 */
class PreferencesGuiSessionRegistryTest {

    /**
     * 新页面令牌必须使旧页面令牌失效，旧关闭不能删除新会话。
     */
    @Test
    void beginAndConditionalClear_protectNewSessionFromOldClose() {
        PreferencesGuiSessionRegistry registry = new PreferencesGuiSessionRegistry();
        UUID playerUuid = UUID.randomUUID();

        UUID firstToken = registry.begin(playerUuid);
        UUID secondToken = registry.begin(playerUuid);

        assertFalse(registry.isCurrent(playerUuid, firstToken));
        assertTrue(registry.isCurrent(playerUuid, secondToken));
        registry.clearIfCurrent(playerUuid, firstToken);
        assertTrue(registry.isCurrent(playerUuid, secondToken));
        registry.clearIfCurrent(playerUuid, secondToken);
        assertFalse(registry.isCurrent(playerUuid, secondToken));
    }

    /**
     * 清空会话后所有既有令牌都必须失效。
     */
    @Test
    void clear_invalidatesAllSessions() {
        PreferencesGuiSessionRegistry registry = new PreferencesGuiSessionRegistry();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID firstToken = registry.begin(firstPlayer);
        UUID secondToken = registry.begin(secondPlayer);

        registry.clear();

        assertFalse(registry.isCurrent(firstPlayer, firstToken));
        assertFalse(registry.isCurrent(secondPlayer, secondToken));
    }
}
