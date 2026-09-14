package net.momirealms.sparrow.sync.snapshot.operation;

import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public sealed interface SnapshotRestoreResult {
    /** 在线玩家已应用历史内容, 新的 RESTORE 记录已保存. */
    record Restored(@NotNull UUID snapshotId, @NotNull List<DataKey> skipped) implements SnapshotRestoreResult {
        public Restored {
            skipped = List.copyOf(skipped);
        }

        public Restored(@NotNull UUID snapshotId) {
            this(snapshotId, List.of());
        }
    }

    /** 离线恢复的新记录已保存, 供下次登录读取. */
    record RestoredOffline(@NotNull UUID snapshotId) implements SnapshotRestoreResult {
    }

    /** 指定快照不存在. */
    NotFound NOT_FOUND = new NotFound();
    record NotFound() implements SnapshotRestoreResult {
    }

    /** 指定快照不属于目标玩家. */
    WrongPlayer WRONG_PLAYER = new WrongPlayer();
    record WrongPlayer() implements SnapshotRestoreResult {
    }

    /** 玩家或会话不可用、未取得离线恢复锁, 或服务已关闭, 本次未执行. */
    Offline OFFLINE = new Offline();
    record Offline() implements SnapshotRestoreResult {
    }

    /** RESTORE 记录的保存被事件监听器取消. */
    Cancelled CANCELLED = new Cancelled();
    record Cancelled(@NotNull Stage stage, @NotNull List<DataKey> skipped) implements SnapshotRestoreResult {
        public Cancelled {
            skipped = List.copyOf(skipped);
        }

        public Cancelled() {
            this(Stage.UNKNOWN, List.of());
        }
    }

    /** 解码、应用或保存失败, 或远程执行异常. */
    Failed FAILED = new Failed();
    record Failed(@NotNull Stage stage, @NotNull String detail, @Nullable Throwable cause, @NotNull List<DataKey> skipped) implements SnapshotRestoreResult {
        public Failed {
            skipped = List.copyOf(skipped);
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
        }

        public Failed() {
            this(Stage.UNKNOWN, "restore failed", null, List.of());
        }
    }

    /** 远程请求失联或超时, 无法确认执行结果. */
    Unavailable UNAVAILABLE = new Unavailable();
    record Unavailable() implements SnapshotRestoreResult {
    }

    enum Stage {
        /** 数据准备失败, 尚未应用玩家数据. */
        PREPARE,
        /** 应用失败, 可能已修改部分玩家数据. */
        APPLY,
        /** 在线数据已应用, 但新的 RESTORE 快照尚未确认写入数据库. */
        SAVE,
        /** 结果未携带在线应用阶段. */
        UNKNOWN
    }
}
