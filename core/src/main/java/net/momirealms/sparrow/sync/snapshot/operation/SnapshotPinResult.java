package net.momirealms.sparrow.sync.snapshot.operation;

public sealed interface SnapshotPinResult {

    /** 快照已固定. */
    Pinned PINNED = new Pinned();
    record Pinned() implements SnapshotPinResult {
    }

    /** 快照已经固定. */
    Unchanged UNCHANGED = new Unchanged();
    record Unchanged() implements SnapshotPinResult {
    }

    /** 指定快照不存在或已被删除. */
    NotFound NOT_FOUND = new NotFound();
    record NotFound() implements SnapshotPinResult {
    }
}
