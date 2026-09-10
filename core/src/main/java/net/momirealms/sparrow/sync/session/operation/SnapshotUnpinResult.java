package net.momirealms.sparrow.sync.session.operation;

/** 取消固定指定快照的结果. */
public sealed interface SnapshotUnpinResult {
    /** 已取消固定. */
    Unpinned UNPINNED = new Unpinned();

    /** 快照原本就未固定. */
    Unchanged UNCHANGED = new Unchanged();

    /** 指定快照不存在或已被删除. */
    NotFound NOT_FOUND = new NotFound();

    record Unpinned() implements SnapshotUnpinResult {
    }

    record Unchanged() implements SnapshotUnpinResult {
    }

    record NotFound() implements SnapshotUnpinResult {
    }
}
