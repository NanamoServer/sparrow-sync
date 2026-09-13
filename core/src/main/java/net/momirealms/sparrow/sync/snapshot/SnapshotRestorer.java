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
    private final SnapshotSaver saver; // 生成新的 RESTORE 记录
    private final SnapshotApplier applier; // 在线玩家的数据准备与应用
    private volatile boolean operationsClosed; // 停服后拒绝新的恢复请求
    private final Set<UUID> offlineRestores = ConcurrentHashMap.newKeySet(); // 离线恢复持锁期间供登录和交接查询

    SnapshotRestorer(@NotNull SparrowSync plugin, @NotNull SnapshotSaver saver, @NotNull SnapshotApplier applier) {
        this.plugin = plugin;
        this.storage = plugin.storageProvider();
        this.saver = saver;
        this.applier = applier;
    }

    /**
     * 恢复选定历史内容, 在线应用完成后等待新的 RESTORE 记录保存.
     *
     * @param player 当前操作绑定的玩家对象
     * @param snapshotId 明确选定的快照 ID
     * @return 恢复、离线、归属错误、取消或失败结果
     */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restore(@NotNull Player player, @NotNull UUID snapshotId) {
        // 在读库前绑定会话, 在线恢复的所有后续阶段都使用这次身份.
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

    // 保留应用和保存的阶段结果, 保存失败时玩家已经被修改.
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

    /**
     * 取得玩家会话锁后写入新的 RESTORE 记录, 留待下次登录加载.
     *
     * @param player 目标玩家的名字与 UUID
     * @param snapshotId 明确选定的快照 ID
     * @return 离线恢复结果, 写入结束并归还锁后完成
     */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restoreOffline(@NotNull PlayerIdentity player, @NotNull UUID snapshotId) {
        return this.storage.snapshot(snapshotId).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(SnapshotRestoreResult.NOT_FOUND);
            if (!found.get().meta().player().equals(player.uuid())) return CompletableFuture.completedFuture(SnapshotRestoreResult.WRONG_PLAYER);
            if (this.operationsClosed || !this.offlineRestores.add(player.uuid())) return CompletableFuture.completedFuture(SnapshotRestoreResult.OFFLINE);
            // 先登记在途写入, 持锁期间交接探测持续回答 SAVING.
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

    /**
     * 查询本服是否持有该玩家的在途离线恢复任务.
     *
     * @param player 目标玩家 UUID
     * @return 是否应在登录或交接时视为仍在保存
     */
    public boolean restoringOffline(@NotNull UUID player) {
        return this.offlineRestores.contains(player);
    }

    /**
     * 停止新的历史恢复请求, 已取得锁的恢复继续保存并归还锁.
     */
    public void stopOperations() {
        this.operationsClosed = true;
    }

    /**
     * 暂存写入结果或失败, 会话锁归还后再完成管理请求.
     *
     * @param saved 已完成的保存结果, 异常完成时为 null
     * @param failure 保存异常, 正常完成时为 null
     */
    private record OfflineSave(@Nullable SnapshotSaveResult saved, @Nullable Throwable failure) {
    }
}
