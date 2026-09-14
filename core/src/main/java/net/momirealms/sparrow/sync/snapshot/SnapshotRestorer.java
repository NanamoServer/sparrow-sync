package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionState;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotApplyResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotRestoreResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

final class SnapshotRestorer {
    private final SparrowSync plugin;
    private final StorageProvider storage;
    private final SnapshotSaver saver;
    private final SnapshotApplier applier;
    private volatile boolean operationsClosed; // 停服后拒绝新恢复请求
    private final Set<UUID> offlineRestores = ConcurrentHashMap.newKeySet(); // 供登录和交接流程查询正在进行的离线恢复

    SnapshotRestorer(@NotNull SparrowSync plugin, @NotNull SnapshotSaver saver, @NotNull SnapshotApplier applier) {
        this.plugin = plugin;
        this.storage = plugin.storageProvider();
        this.saver = saver;
        this.applier = applier;
    }

    /** 应用历史快照后保存新的 RESTORE 快照, 等待保存结果后返回. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restore(@NotNull Player player, @NotNull UUID snapshotId) {
        // 读取前记录会话, 后续只操作这次登录的玩家
        PlayerSession session = this.plugin.sessionManager().find(player.getUniqueId());
        if (this.operationsClosed || session == null || session.state() != SessionState.ACTIVE) {
            return CompletableFuture.completedFuture(SnapshotRestoreResult.OFFLINE);
        }
        return this.storage.snapshot(snapshotId)
                .thenCompose(found -> {
                    if (found.isEmpty()) return CompletableFuture.completedFuture(SnapshotRestoreResult.NOT_FOUND);
                    if (!found.get().meta().player().equals(session.uuid())) return CompletableFuture.completedFuture(SnapshotRestoreResult.WRONG_PLAYER);
                    return this.restore(session, player, found.get());
                });
    }

    // 保存失败前可能已修改玩家, 结果需保留应用和保存两个阶段的状态
    @NotNull
    private CompletableFuture<SnapshotRestoreResult> restore(@NotNull PlayerSession session, @NotNull Player player, @NotNull Snapshot snapshot) {
        return this.applier.applyOnline(session, player, snapshot).thenCompose(applied ->
                switch (applied) {
                    case SnapshotApplyResult.Rejected ignored -> CompletableFuture.completedFuture(SnapshotRestoreResult.OFFLINE);
                    case SnapshotApplyResult.Failed failed -> CompletableFuture.completedFuture(new SnapshotRestoreResult.Failed(
                            failed.applicationStarted() ? SnapshotRestoreResult.Stage.APPLY : SnapshotRestoreResult.Stage.PREPARE, failed.detail(), failed.cause(), failed.skipped()));
                    case SnapshotApplyResult.Applied success -> CompletableFuture.completedFuture(null)
                            .thenCompose(ignored -> this.saver.saveRestored(snapshot, session.playerName()))
                            .handle((saved, failure) -> {
                                if (failure != null) return new SnapshotRestoreResult.Failed(SnapshotRestoreResult.Stage.SAVE, failure.toString(), failure, success.skipped());
                                return switch (saved) {
                                    case SnapshotSaveResult.Cancelled ignored -> new SnapshotRestoreResult.Cancelled(SnapshotRestoreResult.Stage.SAVE, success.skipped());
                                    case SnapshotSaveResult.Settled settled -> settled.result().stored()
                                            ? new SnapshotRestoreResult.Restored(settled.id(), success.skipped())
                                            : new SnapshotRestoreResult.Failed(SnapshotRestoreResult.Stage.SAVE, "RESTORE snapshot was not stored", null, success.skipped());
                                };
                            });
                });
    }

    /** 取得会话锁后保存新的 RESTORE 快照, 下次登录生效, 解锁尝试结束后返回. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restoreOffline(@NotNull PlayerIdentity player, @NotNull UUID snapshotId) {
        return this.storage.snapshot(snapshotId).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(SnapshotRestoreResult.NOT_FOUND);
            if (!found.get().meta().player().equals(player.uuid())) return CompletableFuture.completedFuture(SnapshotRestoreResult.WRONG_PLAYER);
            if (this.operationsClosed || !this.offlineRestores.add(player.uuid())) return CompletableFuture.completedFuture(SnapshotRestoreResult.OFFLINE);
            // 先登记离线恢复, 持锁期间交接探测返回 SAVING
            return CompletableFuture.completedFuture(null).thenCompose(ignored -> this.plugin.sessionLock().tryAcquire(player.uuid())).thenCompose(acquired -> {
                if (!(acquired instanceof SessionLock.AcquireOutcome.Acquired lock)) return CompletableFuture.completedFuture(SnapshotRestoreResult.OFFLINE);
                return CompletableFuture.completedFuture(null).thenCompose(ignored -> this.saver.saveRestored(found.get(), player.name()))
                        .handle((saved, failure) -> new OfflineSave(saved, failure))
                        .thenCompose(outcome -> this.plugin.sessionLock().release(player.uuid(), lock.value()).thenApply(ignored -> {
                            if (outcome.failure() != null) {
                                throw new CompletionException(outcome.failure());
                            }
                            return (SnapshotRestoreResult) switch (outcome.saved()) {
                                case SnapshotSaveResult.Cancelled ignored1 -> SnapshotRestoreResult.CANCELLED;
                                case SnapshotSaveResult.Settled settled -> settled.result().stored()
                                        ? new SnapshotRestoreResult.RestoredOffline(settled.id()) : SnapshotRestoreResult.FAILED;
                            };
                        }));
            }).whenComplete((result, failure) -> this.offlineRestores.remove(player.uuid()));
        });
    }

    /** 本服是否正在恢复该玩家的离线快照. */
    public boolean restoringOffline(@NotNull UUID player) {
        return this.offlineRestores.contains(player);
    }

    /** 停止新恢复请求, 已持锁的请求继续保存并解锁. */
    public void stopOperations() {
        this.operationsClosed = true;
    }

    /** 保存结果和异常暂存在此处, 解锁尝试结束后再返回给调用方. */
    private record OfflineSave(@Nullable SnapshotSaveResult saved, @Nullable Throwable failure) {
    }
}
