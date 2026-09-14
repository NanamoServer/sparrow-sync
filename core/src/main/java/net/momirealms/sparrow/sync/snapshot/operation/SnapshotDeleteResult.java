package net.momirealms.sparrow.sync.snapshot.operation;

public sealed interface SnapshotDeleteResult {
    /** 快照已删除. */
    Deleted DELETED = new Deleted();
    record Deleted() implements SnapshotDeleteResult {
    }

    /** 指定快照不存在或已被删除. */
    NotFound NOT_FOUND = new NotFound();
    record NotFound() implements SnapshotDeleteResult {
    }
}
