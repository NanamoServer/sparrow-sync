package net.momirealms.sparrow.sync.map.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record MapArchiveRecord(@NotNull MapIdentity identity, int dataVersion, long updatedAt, byte @NotNull [] data) {
}
