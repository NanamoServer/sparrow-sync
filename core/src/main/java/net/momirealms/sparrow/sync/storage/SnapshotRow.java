package net.momirealms.sparrow.sync.storage;

import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// 携带独立列与数据帧, JDBC 连接归还后仍可继续解码.
@ApiStatus.Internal
public record SnapshotRow(@NotNull SnapshotMeta meta, int format, byte @NotNull [] data) {
}
