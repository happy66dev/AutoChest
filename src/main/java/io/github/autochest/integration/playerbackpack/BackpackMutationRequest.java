package io.github.autochest.integration.playerbackpack;

import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public record BackpackMutationRequest(UUID mutationId, UUID targetId, BackpackMutationDirection direction,
                                      long expectedRevision, int logicalSlot, ItemStack expectedBefore,
                                      ItemStack requestedAfter, int movedAmount) {
    public BackpackMutationRequest {
        if (mutationId == null || targetId == null || direction == null || expectedRevision < 0
                || logicalSlot <= 0 || movedAmount <= 0) {
            throw new IllegalArgumentException("背包 mutation 请求参数非法喵~");
        }
        expectedBefore = expectedBefore == null ? null : expectedBefore.clone();
        requestedAfter = requestedAfter == null ? null : requestedAfter.clone();
    }
}
