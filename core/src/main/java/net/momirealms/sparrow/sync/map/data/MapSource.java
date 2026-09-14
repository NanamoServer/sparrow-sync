package net.momirealms.sparrow.sync.map.data;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// 地图来源: 地图源 ID 和来源服上的非负地图 ID.
@ApiStatus.Internal
public record MapSource(@NotNull String ownerId, int id) {
    public MapSource {
        if (ownerId.isBlank() || id < 0) {
            throw new IllegalArgumentException("map source requires an owner and a non-negative native id");
        }
    }
}
