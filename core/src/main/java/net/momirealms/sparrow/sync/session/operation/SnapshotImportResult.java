package net.momirealms.sparrow.sync.session.operation;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 导入快照文件的结果, 文件读写异常通过 Future 传播. */
public sealed interface SnapshotImportResult {
    /** 快照已按原 ID 和时间写入数据库. */
    record Imported(@NotNull UUID snapshotId) implements SnapshotImportResult {
    }

    /** 文件内容无法解码为合法快照. */
    record InvalidFile() implements SnapshotImportResult {
    }

    /** 快照未成功写入数据库. */
    record Failed() implements SnapshotImportResult {
    }
}
