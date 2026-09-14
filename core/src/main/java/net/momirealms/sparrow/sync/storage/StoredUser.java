package net.momirealms.sparrow.sync.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

@ApiStatus.Internal
public record StoredUser(@NotNull UUID player, @NotNull String name, long lastSeen) {
}
