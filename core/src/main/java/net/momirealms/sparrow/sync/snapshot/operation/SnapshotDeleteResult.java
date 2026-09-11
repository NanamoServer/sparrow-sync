package net.momirealms.sparrow.sync.snapshot.operation;

/** 删除指定快照的结果. */
public sealed interface SnapshotDeleteResult {
    /** 快照已删除. */
    Deleted DELETED = new Deleted();

    /** 指定快照不存在或已被删除. */
    NotFound NOT_FOUND = new NotFound();

    record Deleted() implements SnapshotDeleteResult {
    }

    record NotFound() implements SnapshotDeleteResult {
    }
}
