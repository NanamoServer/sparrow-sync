package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplier;
import org.jetbrains.annotations.NotNull;

/** 最新快照的读取与预解码结果. */
sealed interface SnapshotLoadResult {

    /** 快照已经读取并预解码, 可以在玩家线程应用. */
    record Ready(@NotNull Snapshot snapshot, @NotNull SnapshotApplier.PreparedSnapshot.Ready data, long loadNanos) implements SnapshotLoadResult {
    }

    /** 玩家没有历史快照, 本服状态即权威. */
    record Empty() implements SnapshotLoadResult {
    }

    /** 关键数据无法解码, 整份快照不能应用. */
    record Failed(@NotNull String detail) implements SnapshotLoadResult {
    }
}
