package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public record StoredMap(@NotNull MapIdentity identity, @NotNull MapData data) {
}
