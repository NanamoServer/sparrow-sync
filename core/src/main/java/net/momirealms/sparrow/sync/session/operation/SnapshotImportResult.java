package net.momirealms.sparrow.sync.session.operation;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 导入快照文件的结果, 文件读写异常通过 Future 传播. */
public sealed interface SnapshotImportResult {
    /** 快照已按原 ID 和时间插入数据库. */
    record Imported(@NotNull UUID snapshotId) implements SnapshotImportResult {
    }

    /** 数据库中已有相同 ID 和相同内容. */
    record Unchanged(@NotNull UUID snapshotId) implements SnapshotImportResult {
    }

    /** 数据库中已有相同 ID 但内容不同的快照. */
    record Conflict(@NotNull UUID snapshotId) implements SnapshotImportResult {
    }

    /** 文件内容无法解码为合法快照. */
    record InvalidFile() implements SnapshotImportResult {
    }

    /** 快照未成功写入数据库. */
    record Failed() implements SnapshotImportResult {
    }
}
