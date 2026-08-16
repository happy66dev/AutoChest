package io.github.autochest.integration.playerbackpack;

import java.util.UUID;

public record BackpackOperation(UUID targetId, UUID requesterId, String token, long initialRevision, Object nativeHandle) {
    public BackpackOperation {
        if (targetId == null || requesterId == null || token == null || token.isBlank()
                || initialRevision < 0 || nativeHandle == null) {
            throw new IllegalArgumentException("背包操作会话参数非法喵~");
        }
    }
}
