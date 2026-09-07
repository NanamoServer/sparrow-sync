package net.momirealms.sparrow.sync.map.data;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// 地图存储记录, 包含地图同步标识与地图同步数据.
@ApiStatus.Internal
public record StoredMap(@NotNull MapIdentity identity, @NotNull MapData data) {
}
