package net.momirealms.sparrow.sync.session;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/** 在线玩家即时恢复最新快照的结果. */
@ApiStatus.Internal
public sealed interface SnapshotRestoreResult {

    /** 快照已应用. */
    record Applied(int applied, int skipped) implements SnapshotRestoreResult {
    }

    /** 玩家没有历史快照. */
    record Empty() implements SnapshotRestoreResult {
    }

    /** 应用调度前会话或玩家实体已经失效. */
    record Gone() implements SnapshotRestoreResult {
    }

    /** 关键数据解码或应用失败. */
    record Failed(@NotNull String detail) implements SnapshotRestoreResult {
    }
}
