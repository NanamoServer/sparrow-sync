package net.momirealms.sparrow.sync.snapshot.operation;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public sealed interface SnapshotCaptureResult {
    /** 已保存采集内容, 携带实际生成的快照 ID. */
    record Captured(@NotNull UUID snapshotId) implements SnapshotCaptureResult {
    }

    /** 玩家或会话不可用, 或服务已关闭, 本次未执行. */
    Offline OFFLINE = new Offline();
    record Offline() implements SnapshotCaptureResult {
    }

    /** 保存被事件监听器取消. */
    Cancelled CANCELLED = new Cancelled();
    record Cancelled() implements SnapshotCaptureResult {
    }

    /** 保存失败或远程执行异常. */
    Failed FAILED = new Failed();
    record Failed() implements SnapshotCaptureResult {
    }

    /** 远程请求失联或超时, 无法确认执行结果. */
    Unavailable UNAVAILABLE = new Unavailable();
    record Unavailable() implements SnapshotCaptureResult {
    }
}
