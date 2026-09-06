package net.momirealms.sparrow.sync.map.data;

import net.momirealms.sparrow.sync.map.handler.MapType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// Map 物品 custom_data 中的来源标记.
@ApiStatus.Internal
public record MapOrigin(@NotNull MapType type, @NotNull String ownerId, int id) {
}
