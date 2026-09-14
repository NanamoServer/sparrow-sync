package net.momirealms.sparrow.sync.snapshot.operation;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public sealed interface SnapshotExportResult {
    /** 文件已导出, 包含快照 ID 和插件目录内的相对路径. */
    record Exported(@NotNull UUID snapshotId, @NotNull String path) implements SnapshotExportResult {
    }

    /** 指定快照不存在. */
    NotFound NOT_FOUND = new NotFound();
    record NotFound() implements SnapshotExportResult {
    }
}
