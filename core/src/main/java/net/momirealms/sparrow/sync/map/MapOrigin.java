package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public record MapOrigin(@NotNull MapType type, @NotNull String ownerId, int id) {
}
