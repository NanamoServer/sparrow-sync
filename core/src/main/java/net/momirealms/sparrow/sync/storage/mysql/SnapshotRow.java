package net.momirealms.sparrow.sync.storage.mysql;

import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import org.jetbrains.annotations.NotNull;

// 携带独立列与数据帧, JDBC 连接归还后仍可继续解码.
record SnapshotRow(@NotNull SnapshotMeta meta, int format, byte @NotNull [] data) {
}
