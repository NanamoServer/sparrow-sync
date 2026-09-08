package net.momirealms.sparrow.sync.session.operation;

/** 取消固定指定快照的结果. */
public sealed interface SnapshotUnpinResult {
    /** 已取消固定. */
    record Unpinned() implements SnapshotUnpinResult {
    }

    /** 快照原本就未固定. */
    record Unchanged() implements SnapshotUnpinResult {
    }

    /** 指定快照不存在或已被删除. */
    record NotFound() implements SnapshotUnpinResult {
    }
}
