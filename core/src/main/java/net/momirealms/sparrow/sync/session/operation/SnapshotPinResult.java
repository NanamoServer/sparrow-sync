package net.momirealms.sparrow.sync.session.operation;

/** 固定指定快照的结果. */
public sealed interface SnapshotPinResult {
    /** 快照已固定. */
    record Pinned() implements SnapshotPinResult {
    }

    /** 快照已经固定. */
    record Unchanged() implements SnapshotPinResult {
    }

    /** 指定快照不存在或已被删除. */
    record NotFound() implements SnapshotPinResult {
    }
}
