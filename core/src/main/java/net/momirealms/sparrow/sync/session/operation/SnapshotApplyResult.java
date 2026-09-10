package net.momirealms.sparrow.sync.session.operation;

import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplyContext;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 一次预解码快照的应用结果. */
@ApiStatus.Internal
public sealed interface SnapshotApplyResult {

    /** 快照已应用, 或该玩家没有需要应用的历史快照. */
    record Applied(@NotNull List<DataKey> applied, @NotNull List<DataKey> skipped, @NotNull List<SnapshotApplyContext.Failure> failures) implements SnapshotApplyResult {
    }

    /** 会话在应用前已经失效. */
    Rejected REJECTED = new Rejected();

    /** 关键数据应用失败, 玩家不能进入 ACTIVE. */
    record Failed(@NotNull String detail) implements SnapshotApplyResult {
    }

    record Rejected() implements SnapshotApplyResult {
    }
}
