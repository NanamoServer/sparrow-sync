package net.momirealms.sparrow.sync.map.data;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// 来源地图标识, 由地图源 ID 和该来源服上的非负地图 ID 组成.
@ApiStatus.Internal
public record MapSource(@NotNull String ownerId, int id) {
    public MapSource {
        if (ownerId.isBlank() || id < 0) {
            throw new IllegalArgumentException("map source requires an owner and a non-negative native id");
        }
    }
}
