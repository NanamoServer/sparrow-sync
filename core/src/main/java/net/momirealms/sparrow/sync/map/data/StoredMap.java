package net.momirealms.sparrow.sync.map.data;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// 全局地图ID和地图数据的集合, 代表存储在数据库中的一份地图
@ApiStatus.Internal
public record StoredMap(@NotNull MapIdentity identity, @NotNull MapData data) {
}
