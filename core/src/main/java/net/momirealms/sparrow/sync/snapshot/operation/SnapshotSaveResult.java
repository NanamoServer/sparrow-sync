package net.momirealms.sparrow.sync.snapshot.operation;

import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public sealed interface SnapshotSaveResult {

    /** 保存链已经收敛, 携带存储或本地留存结果. */
    record Settled(@NotNull SaveResult result, @NotNull UUID id) implements SnapshotSaveResult {
    }

    /** 快照保存被事件监听器取消. */
    Cancelled CANCELLED = new Cancelled();
    record Cancelled() implements SnapshotSaveResult {
    }
}
