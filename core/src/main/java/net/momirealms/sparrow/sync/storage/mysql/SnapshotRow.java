package net.momirealms.sparrow.sync.storage.mysql;

import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// 数据库查询和插入时所用的中间对象, 避免在数据库线程进行数据解码.
@ApiStatus.Internal
public record SnapshotRow(@NotNull SnapshotMeta meta, int format, byte @NotNull [] data) {
}
