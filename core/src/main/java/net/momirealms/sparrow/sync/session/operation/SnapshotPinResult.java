package net.momirealms.sparrow.sync.session.operation;

/** 固定指定快照的结果. */
public sealed interface SnapshotPinResult {
    /** 快照已固定. */
    Pinned PINNED = new Pinned();

    /** 快照已经固定. */
    Unchanged UNCHANGED = new Unchanged();

    /** 指定快照不存在或已被删除. */
    NotFound NOT_FOUND = new NotFound();

    record Pinned() implements SnapshotPinResult {
    }

    record Unchanged() implements SnapshotPinResult {
    }

    record NotFound() implements SnapshotPinResult {
    }
}
