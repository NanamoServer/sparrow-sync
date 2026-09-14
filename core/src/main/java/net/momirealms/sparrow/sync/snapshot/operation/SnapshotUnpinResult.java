package net.momirealms.sparrow.sync.snapshot.operation;

public sealed interface SnapshotUnpinResult {

    /** 已取消固定. */
    Unpinned UNPINNED = new Unpinned();
    record Unpinned() implements SnapshotUnpinResult {
    }

    /** 快照原本就未固定. */
    Unchanged UNCHANGED = new Unchanged();
    record Unchanged() implements SnapshotUnpinResult {
    }

    /** 指定快照不存在或已被删除. */
    NotFound NOT_FOUND = new NotFound();
    record NotFound() implements SnapshotUnpinResult {
    }
}
