package net.momirealms.sparrow.sync.snapshot.operation;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public sealed interface SnapshotImportResult {
    /** 快照已按原 ID 和时间写入数据库. */
    record Imported(@NotNull UUID snapshotId) implements SnapshotImportResult {
    }

    /** 文件内容无法解码为合法快照. */
    InvalidFile INVALID_FILE = new InvalidFile();
    record InvalidFile() implements SnapshotImportResult {
    }

    /** 快照未成功写入数据库. */
    Failed FAILED = new Failed();
    record Failed() implements SnapshotImportResult {
    }
}
