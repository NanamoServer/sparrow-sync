package net.momirealms.sparrow.sync.map.data;

import net.momirealms.sparrow.sync.map.handler.MapType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// 保存在物品 custom_data 中的同步模式和地图来源.
@ApiStatus.Internal
public record MapOrigin(@NotNull MapType type, @NotNull String ownerId, int id) {
}
