package net.momirealms.sparrow.sync.snapshot.operation;

import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * 恢复指定历史快照的结果, 保留失败阶段、说明和跳过类型; 远程回执中的 cause 为空.
 */
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

    /** 指定快照不属于目标玩家. */
    WrongPlayer WRONG_PLAYER = new WrongPlayer();

    /** 玩家离线、会话不可操作、离线恢复未取得锁或服务已停止接收请求, 本次未执行. */
    Offline OFFLINE = new Offline();

    /** RESTORE 记录的保存被事件监听器取消. */
    Cancelled CANCELLED = new Cancelled();

    /** 解码、应用或保存未成功, 或远程执行发生异常. */
    Failed FAILED = new Failed();

    /** 远程请求失联或超时, 无法确认执行结果. */
    Unavailable UNAVAILABLE = new Unavailable();

    record NotFound() implements SnapshotRestoreResult {
    }

    record WrongPlayer() implements SnapshotRestoreResult {
    }

    record Offline() implements SnapshotRestoreResult {
    }

    record Cancelled(@NotNull Stage stage, @NotNull List<DataKey> skipped) implements SnapshotRestoreResult {
        public Cancelled {
            skipped = List.copyOf(skipped);
        }

        public Cancelled() {
            this(Stage.UNKNOWN, List.of());
        }
    }

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

    record Unavailable() implements SnapshotRestoreResult {
    }

    enum Stage {
        /** 数据准备失败, 尚未应用玩家数据. */
        PREPARE,
        /** 应用失败, 可能已修改部分玩家数据. */
        APPLY,
        /** 在线应用已完成, 新的 RESTORE 记录未确认入库. */
        SAVE,
        /** 结果未携带在线应用阶段. */
        UNKNOWN
    }
}
