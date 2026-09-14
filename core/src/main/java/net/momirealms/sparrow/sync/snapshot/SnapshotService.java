package net.momirealms.sparrow.sync.snapshot;

import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotApplyResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotCaptureResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDeleteResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotExportResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotImportResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotLoadResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotPinResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotRestoreResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotUnpinResult;
import net.momirealms.sparrow.sync.snapshot.trigger.SaveTriggerListener;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class SnapshotService {
    private final SparrowSync plugin;
    private StorageProvider storage;
    private SnapshotSaver saver;
    private SnapshotApplier applier;
    private SnapshotRestorer restorer;
    private SnapshotTransfer transfer;
    private SnapshotFiles files;
    private SnapshotDetails details;

    public SnapshotService(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
        this.storage = this.plugin.storageProvider();
        this.files = new SnapshotFiles(this.plugin.dataFolderPath(), this.plugin.binaryCodec(), this.plugin.logger());
        this.details = new SnapshotDetails(this.storage, this.files, this.plugin.dataRegistry(), this.plugin.scheduler().async());
        this.saver = new SnapshotSaver(this.plugin);
        this.applier = new SnapshotApplier(this.plugin);
        this.restorer = new SnapshotRestorer(this.plugin, this.saver, this.applier);
        this.transfer = new SnapshotTransfer(this.storage, this.files, this.plugin.scheduler().async(), this.plugin.snapshotCache());
    }

    public void onDelayedEnable() {
        Bukkit.getPluginManager().registerEvents(new SaveTriggerListener(this.plugin, this.plugin.sessionManager()), this.plugin.javaPlugin());
    }

    /** 读取最新快照并准备地图和类型数据, 没有历史快照时返回 Empty. */
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotLoadResult> loadLatest(@NotNull UUID player, @NotNull String playerName) {
        return this.applier.loadLatest(player, playerName);
    }

    /** 在登录拦截阶段准备原版要加载的数据, 本地数据为空时保留新玩家语义. */
    @NotNull
    @ApiStatus.Internal
    public Optional<CompoundTag> applyNative(@NotNull PlayerSession session, @NotNull Optional<CompoundTag> localData, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.applier.applyNative(session, localData, loaded);
    }

    /** 在玩家线程应用登录前尚未处理的数据, 并执行交接回调. */
    @NotNull
    @ApiStatus.Internal
    public SnapshotApplyResult applyOnJoin(@NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.applier.applyOnJoin(player, loaded);
    }

    /** 采集 ACTIVE 玩家的快照, 等待保存结果后返回. */
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotCaptureResult> capture(@NotNull Player player, @NotNull SaveCause cause) {
        return this.saver.capture(player, cause);
    }

    /** 在当前玩家线程采集全部数据, 再由玩家串行线程编码并保存. */
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotSaveResult> captureNowAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        return this.saver.captureNowAndSave(player, cause, retainedData);
    }

    /** 先在玩家线程采集必须同步读取的数据, 再由玩家串行线程补齐其余数据并保存. */
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotSaveResult> captureLaterAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        return this.saver.captureLaterAndSave(player, cause, retainedData);
    }

    /** 在串行线程采集已退出的玩家并保存, 任务期间保留退出后的 Player 对象. */
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotSaveResult> captureLogoutAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        return this.saver.captureLogoutAndSave(player, cause, retainedData);
    }

    /** 将历史快照应用到在线玩家, 再保存一份新的 RESTORE 快照, 等待保存结果后返回. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restore(@NotNull Player player, @NotNull UUID snapshotId) {
        return this.restorer.restore(player, snapshotId);
    }

    /** 取得会话锁后保存新的 RESTORE 快照, 下次登录生效, 解锁尝试结束后返回. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restoreOffline(@NotNull PlayerIdentity player, @NotNull UUID snapshotId) {
        return this.restorer.restoreOffline(player, snapshotId);
    }

    /** 本服是否正在恢复该玩家的离线快照. */
    public boolean restoringOffline(@NotNull UUID player) {
        return this.restorer.restoringOffline(player);
    }

    /** 导出数据库中的快照, 返回快照 ID 和文件路径. */
    @NotNull
    public CompletableFuture<SnapshotExportResult> export(@NotNull UUID snapshotId, @NotNull SnapshotFiles.Format format) {
        return this.transfer.export(snapshotId, format);
    }

    /**
     * 从本地文件导入快照, 保留原 ID 和时间, 覆盖同 ID 记录.
     * @param relative 本服快照目录内的相对路径
     */
    @NotNull
    public CompletableFuture<SnapshotImportResult> importFile(@NotNull String relative) {
        return this.transfer.importFile(relative);
    }

    /** 固定快照, 后续自动清理时保留. */
    @NotNull
    public CompletableFuture<SnapshotPinResult> pin(@NotNull UUID snapshotId) {
        return this.storage.setPinned(snapshotId, true).thenCompose(changed -> {
            if (changed) return CompletableFuture.completedFuture(SnapshotPinResult.PINNED);
            return this.storage.snapshot(snapshotId).thenApply(current -> current.isEmpty() ? SnapshotPinResult.NOT_FOUND : SnapshotPinResult.UNCHANGED);
        });
    }

    /** 取消固定, 允许自动清理此快照. */
    @NotNull
    public CompletableFuture<SnapshotUnpinResult> unpin(@NotNull UUID snapshotId) {
        return this.storage.setPinned(snapshotId, false).thenCompose(changed -> {
            if (changed) return CompletableFuture.completedFuture(SnapshotUnpinResult.UNPINNED);
            return this.storage.snapshot(snapshotId).thenApply(current -> current.isEmpty() ? SnapshotUnpinResult.NOT_FOUND : SnapshotUnpinResult.UNCHANGED);
        });
    }

    /** 按 ID 删除数据库中的快照. */
    @NotNull
    public CompletableFuture<SnapshotDeleteResult> delete(@NotNull UUID snapshotId) {
        return this.storage.snapshotMeta(snapshotId).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(SnapshotDeleteResult.NOT_FOUND);
            return this.storage.deleteSnapshot(snapshotId).thenCompose(deleted -> {
                if (!deleted) return CompletableFuture.completedFuture(SnapshotDeleteResult.NOT_FOUND);
                return this.plugin.snapshotCache().invalidate(found.get().player()).thenApply(ignored -> SnapshotDeleteResult.DELETED);
            });
        });
    }

    @NotNull
    public SnapshotFiles files() {
        return this.files;
    }

    /**
     * 异步查询本地异常快照列表, 只读取头文件.
     * @param player 玩家 UUID, null 表示不限
     * @param category 目录类别, null 表示不限
     * @param index 从 0 开始的页码
     * @param size 每页条数, 必须为正数
     * @return 当前页和总数, 文件读取失败时异常完成
     */
    @NotNull
    public CompletableFuture<SnapshotFiles.ExceptionPage> listExceptions(@Nullable UUID player, @Nullable String category, int index, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("exception page size must be positive: " + size);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.files.listExceptions(player, category, index, size);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, this.plugin.scheduler().async());
    }

    @NotNull
    public SnapshotDetails details() {
        return this.details;
    }

    /** 停止接收新管理操作, 已接收的保存和关服保存继续执行. */
    public void stopOperations() {
        // 启动失败也会进入停服流程, 只关闭已初始化的对象
        if (this.saver != null) this.saver.stopOperations();
        if (this.restorer != null) this.restorer.stopOperations();
        if (this.applier != null) this.applier.stopOperations();
    }

    /**
     * 停止接收新保存并等待已有请求结束, 期间继续写入和重试.
     * @param timeout 连续无进展的最长等待时间, 非正数表示不等待
     * @param unit 时间单位
     * @return 所有请求均已结束时为 true, 异常完成也算结束
     */
    public boolean sealAndAwaitSaves(long timeout, @NotNull TimeUnit unit) {
        return this.saver == null || this.saver.sealAndAwaitSaves(timeout, unit);
    }

    /** 暂存尚未完成的完整快照, 未编码的请求按超时结束. */
    public void stashUnsettled() {
        if (this.saver != null) {
            this.saver.stashUnsettled();
        }
    }
}
