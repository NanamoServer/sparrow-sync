package net.momirealms.sparrow.sync.map.data;

import net.momirealms.sparrow.sync.map.handler.MapType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// 地图物品 custom_data 中的来源标记, 记录同步模式、地图源 ID 和来源地图 ID.
@ApiStatus.Internal
public record MapOrigin(@NotNull MapType type, @NotNull String ownerId, int id) {
}
