package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public record MapSource(@NotNull String ownerId, int id) {
    public MapSource {
        if (ownerId.isBlank() || id < 0) {
            throw new IllegalArgumentException("map source requires an owner and a non-negative native id");
        }
    }
}
