package net.momirealms.sparrow.sync.storage;

import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public record SnapshotRow(@NotNull SnapshotMeta meta, int format, byte @NotNull [] data) {
}
