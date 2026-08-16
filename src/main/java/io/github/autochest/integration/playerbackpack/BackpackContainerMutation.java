package io.github.autochest.integration.playerbackpack;

import org.bukkit.inventory.ItemStack;

public record BackpackContainerMutation(BackpackContainerDescriptor descriptor, ItemStack expectedBefore,
                                        ItemStack requestedAfter) {
    public BackpackContainerMutation {
        if (descriptor == null) {
            throw new IllegalArgumentException("容器 mutation 位置不能为空喵~");
        }
        expectedBefore = expectedBefore == null ? null : expectedBefore.clone();
        requestedAfter = requestedAfter == null ? null : requestedAfter.clone();
    }
}
