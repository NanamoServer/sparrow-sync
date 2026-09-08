package net.momirealms.sparrow.sync.session.operation;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/** 在线恢复在玩家线程上的应用结果, 后续保存由快照服务继续完成. */
@ApiStatus.Internal
public sealed interface SnapshotRestoreApplyResult {

    /** 快照已应用. */
    record Applied(int applied, int skipped) implements SnapshotRestoreApplyResult {
    }

    /** 玩家处于死亡状态, 本次恢复被拒绝. */
    record Dead() implements SnapshotRestoreApplyResult {
    }

    /** 应用调度前会话或玩家实体已经失效. */
    record Gone() implements SnapshotRestoreApplyResult {
    }

    /** 关键数据解码或应用失败. */
    record Failed(@NotNull String detail) implements SnapshotRestoreApplyResult {
    }
}
