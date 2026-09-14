package net.momirealms.sparrow.sync.snapshot.operation;

import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplyContext;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@ApiStatus.Internal
public sealed interface SnapshotApplyResult {

    /** 快照已应用, 或该玩家没有需要应用的历史快照. */
    record Applied(@NotNull List<DataKey> applied, @NotNull List<DataKey> skipped, @NotNull List<SnapshotApplyContext.Failure> failures) implements SnapshotApplyResult {
    }

    /** 关键数据失败, 在线恢复同时记录是否已进入玩家应用阶段. */
    record Failed(@NotNull String detail, boolean applicationStarted, @Nullable Throwable cause, @NotNull List<DataKey> skipped) implements SnapshotApplyResult {
        public Failed(@NotNull String detail) {
            this(detail, true, null, List.of());
        }
    }

    /** 会话在应用前已经失效. */
    Rejected REJECTED = new Rejected();
    record Rejected() implements SnapshotApplyResult {
    }
}
