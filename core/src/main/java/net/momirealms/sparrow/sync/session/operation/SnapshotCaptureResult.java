package net.momirealms.sparrow.sync.session.operation;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 主动采集并保存快照的结果, 支持本服调用和远程回执. */
public sealed interface SnapshotCaptureResult {
    /** 玩家离线、会话未激活或服务已停止接收请求. */
    Offline OFFLINE = new Offline();

    /** 保存被事件监听器取消. */
    Cancelled CANCELLED = new Cancelled();

    /** 保存未成功, 或远程执行发生异常. */
    Failed FAILED = new Failed();

    /** 远程请求失联或超时, 无法确认执行结果. */
    Unavailable UNAVAILABLE = new Unavailable();

    /** 已保存采集内容, 携带实际生成的快照 ID. */
    record Captured(@NotNull UUID snapshotId) implements SnapshotCaptureResult {
    }

    record Offline() implements SnapshotCaptureResult {
    }

    record Cancelled() implements SnapshotCaptureResult {
    }

    record Failed() implements SnapshotCaptureResult {
    }

    record Unavailable() implements SnapshotCaptureResult {
    }
}
