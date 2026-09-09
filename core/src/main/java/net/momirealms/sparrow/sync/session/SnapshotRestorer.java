package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.session.operation.SnapshotApplyResult;
import net.momirealms.sparrow.sync.session.operation.SnapshotRestoreResult;
import net.momirealms.sparrow.sync.session.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编排指定历史快照的在线与离线恢复, 回执等待新的 RESTORE 记录完成保存.
 * 离线恢复集合与会话锁共同标识正在处理的玩家, 原历史内容保持不变.
 */
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
        if (this.operationsClosed) return CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
        return this.storage.snapshot(snapshotId).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(new SnapshotRestoreResult.NotFound());
            if (!found.get().meta().player().equals(player.getUniqueId())) return CompletableFuture.completedFuture(new SnapshotRestoreResult.WrongPlayer());
            return this.restore(player, found.get());
        });
    }

    /**
     * 恢复选定历史内容, 在线应用完成后等待新的 RESTORE 记录保存.
     *
     * @param player 当前操作绑定的玩家对象
     * @param snapshot 当前选定的完整快照
     * @return 恢复、离线、归属错误、取消或失败结果
     */
    @NotNull
    private CompletableFuture<SnapshotRestoreResult> restore(@NotNull Player player, @NotNull Snapshot snapshot) {
        return this.applier.applyOnline(player, snapshot).thenCompose(applied -> switch (applied) {
            case SnapshotApplyResult.Rejected ignored -> CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
            case SnapshotApplyResult.Failed ignored -> CompletableFuture.completedFuture(new SnapshotRestoreResult.Failed());
            case SnapshotApplyResult.Applied ignored -> this.saver.saveRestored(snapshot, player.getName()).thenApply(saved -> switch (saved) {
                case SnapshotSaveResult.Cancelled cancelled -> new SnapshotRestoreResult.Cancelled();
                case SnapshotSaveResult.Settled settled -> settled.result().stored()
                        ? new SnapshotRestoreResult.Restored(settled.id()) : new SnapshotRestoreResult.Failed();
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
            if (found.isEmpty()) return CompletableFuture.completedFuture(new SnapshotRestoreResult.NotFound());
            if (!found.get().meta().player().equals(player.uuid())) return CompletableFuture.completedFuture(new SnapshotRestoreResult.WrongPlayer());
            if (this.operationsClosed || !this.offlineRestores.add(player.uuid())) return CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
            // 先登记在途写入, 持锁期间交接探测持续回答 SAVING.
            return CompletableFuture.completedFuture(null).thenCompose(ignored -> this.plugin.sessionLock().tryAcquire(player.uuid())).thenCompose(acquired -> {
                if (!(acquired instanceof SessionLock.AcquireOutcome.Acquired lock)) return CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
                return CompletableFuture.completedFuture(null).thenCompose(ignored -> this.saver.saveRestored(found.get(), player.name()))
                        .handle((saved, failure) -> new OfflineSave(saved, failure))
                        .thenCompose(outcome -> this.plugin.sessionLock().release(player.uuid(), lock.value()).thenApply(ignored -> {
                            if (outcome.failure() != null) {
                                throw new CompletionException(outcome.failure());
                            }
                            return (SnapshotRestoreResult) switch (outcome.saved()) {
                                case SnapshotSaveResult.Cancelled cancelled -> new SnapshotRestoreResult.Cancelled();
                                case SnapshotSaveResult.Settled settled -> settled.result().stored()
                                        ? new SnapshotRestoreResult.RestoredOffline(settled.id()) : new SnapshotRestoreResult.Failed();
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
