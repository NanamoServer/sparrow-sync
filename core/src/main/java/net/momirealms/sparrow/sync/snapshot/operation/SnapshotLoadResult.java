package net.momirealms.sparrow.sync.snapshot.operation;

import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplyContext;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public sealed interface SnapshotLoadResult {

    /** 快照已读取并解码, 可在玩家线程应用. */
    record Ready(@NotNull Snapshot snapshot, @NotNull SnapshotApplyContext context, long loadNanos) implements SnapshotLoadResult {
    }

    /** 没有历史快照, 使用本服数据登录. */
    Empty EMPTY = new Empty();
    record Empty() implements SnapshotLoadResult {
    }

    /** 关键数据无法解码, 整份快照不能应用. */
    record Failed(@NotNull String detail) implements SnapshotLoadResult {
    }
}
