package io.github.autochest.integration.playerbackpack;

import java.util.UUID;

public record BackpackContainerDescriptor(UUID worldId, int x, int y, int z, int slot) {
    public BackpackContainerDescriptor {
        if (worldId == null || slot < 0) {
            throw new IllegalArgumentException("容器位置参数非法喵~");
        }
    }
}
