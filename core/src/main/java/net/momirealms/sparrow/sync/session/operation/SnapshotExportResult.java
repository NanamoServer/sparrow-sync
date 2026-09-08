package net.momirealms.sparrow.sync.session.operation;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 导出快照文件的结果, 文件读写异常通过 Future 传播. */
public sealed interface SnapshotExportResult {
    /** 完整文件已写出, 携带实际快照 ID 和插件目录内的相对路径. */
    record Exported(@NotNull UUID snapshotId, @NotNull String path) implements SnapshotExportResult {
    }

    /** 指定快照不存在. */
    record NotFound() implements SnapshotExportResult {
    }
}
