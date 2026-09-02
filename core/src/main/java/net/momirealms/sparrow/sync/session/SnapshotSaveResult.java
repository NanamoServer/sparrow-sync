package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.jetbrains.annotations.NotNull;

/** 一次快照保存请求的最终结果. */
public sealed interface SnapshotSaveResult {

    /** 保存链已经收敛, 携带存储或本地留存结果. */
    record Settled(@NotNull SaveResult result) implements SnapshotSaveResult {
    }

    /** 快照保存被事件监听器取消. */
    record Cancelled() implements SnapshotSaveResult {
    }
}
