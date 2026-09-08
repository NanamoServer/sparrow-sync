package net.momirealms.sparrow.sync.session.operation;

/** 删除指定快照的结果. */
public sealed interface SnapshotDeleteResult {
    /** 快照已删除. */
    record Deleted() implements SnapshotDeleteResult {
    }

    /** 指定快照不存在或已被删除. */
    record NotFound() implements SnapshotDeleteResult {
    }
}
