package net.momirealms.sparrow.sync.session.operation;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 恢复指定历史快照的结果, 成功分为在线覆盖和离线写入. */
public sealed interface SnapshotRestoreResult {
    /** 在线玩家已应用历史内容, 新的 RESTORE 记录已保存. */
    record Restored(@NotNull UUID snapshotId) implements SnapshotRestoreResult {
    }

    /** 离线恢复的新记录已保存, 供下次登录读取. */
    record RestoredOffline(@NotNull UUID snapshotId) implements SnapshotRestoreResult {
    }

    /** 指定快照不存在. */
    record NotFound() implements SnapshotRestoreResult {
    }

    /** 指定快照不属于目标玩家. */
    record WrongPlayer() implements SnapshotRestoreResult {
    }

    /** 玩家或会话失效, 或离线恢复无法取得会话锁. */
    record Offline() implements SnapshotRestoreResult {
    }

    /** RESTORE 记录的保存被事件监听器取消. */
    record Cancelled() implements SnapshotRestoreResult {
    }

    /** 解码、应用或保存未成功, 或远程执行发生异常. */
    record Failed() implements SnapshotRestoreResult {
    }

    /** 远程请求失联或超时, 无法确认执行结果. */
    record Unavailable() implements SnapshotRestoreResult {
    }
}
