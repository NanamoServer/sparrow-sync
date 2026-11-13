package net.momirealms.sparrow.sync.snapshot.local;

import net.momirealms.sparrow.sync.cluster.cache.SnapshotCache;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.plugin.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveOutcome;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class SnapshotStash {
    private SparrowSync plugin;
    private SnapshotFiles files; // 与管理查询共用的快照文件操作
    private SyncLogger logger;
    private SnapshotCache cache;
    private StorageProvider storage; // 运行期重新上传使用的存储后端

    private final Object pendingLock = new Object(); // 写入本地快照和列出 pending 目录时持有, 列出的文件均已写完
    private volatile boolean pendingPresent = true;  // pending 目录可能有文件, 为 false 时定时任务不扫描目录
    private volatile boolean closed;                 // 关服开始后不再处理新的文件
    private volatile SchedulerTask uploadTask;       // 下一轮重新上传的定时任务
    private volatile CompletableFuture<SaveOutcome> uploading; // 正在等待结果的数据库写入

    public SnapshotStash(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad(@NotNull SnapshotFiles files) {
        this.files = files;
        this.logger = this.plugin.logger();
        this.cache = this.plugin.snapshotCache();
    }

    /** 开始按配置的间隔将本地待重试的快照重新上传到数据库. */
    public void onDelayedEnable() {
        this.storage = this.plugin.storageProvider();
        this.scheduleUpload();
    }

    /** 停止运行期的重新上传, 尚未处理的文件留给下次启动. */
    public void stopUploading() {
        this.closed = true;
        SchedulerTask task = this.uploadTask;
        if (task != null) task.cancel();
        // 串行线程在关服时可能丢弃这次写入, 取消后等待方立即返回
        CompletableFuture<SaveOutcome> uploading = this.uploading;
        if (uploading != null) uploading.cancel(false);
    }

    // 每轮结束后按当前配置安排下一轮, 重载配置后下一轮即生效.
    private void scheduleUpload() {
        if (this.closed) return;
        int interval = Math.max(1, PluginConfig.synchronization$pendingUpload().intervalSeconds());
        this.uploadTask = this.plugin.scheduler().asyncLater(this::uploadPending, interval, TimeUnit.SECONDS);
    }

    private void uploadPending() {
        try {
            if (PluginConfig.synchronization$pendingUpload().enabled() && this.pendingPresent && !this.closed) {
                this.restorePending(this.storage, false);
            }
        } finally {
            this.scheduleUpload();
        }
    }

    /**
     * 按保存失败原因, 将快照保存为本地待重试的快照或异常快照, 并记录文件写入失败.
     *
     * @param snapshot 当前选定的完整快照
     * @param playerName 日志和快照文件名使用的玩家名
     * @param reason 存储层给出的失败分类
     */
    public void stash(@NotNull Snapshot snapshot, @NotNull String playerName, @NotNull SaveResult reason) {
        boolean pending = reason.retriable();
        try {
            Path file;
            synchronized (this.pendingLock) {
                file = this.files.write(snapshot, playerName, pending ? null : directoryOf(reason));
                if (pending) this.pendingPresent = true;
            }
            String key = pending ? LogConstants.STASH_PENDING : LogConstants.STASH_EXCEPTION;
            this.logger.warn(LogCategory.STASH, snapshot.meta().player(), playerName, key, playerName, file.toString());
        } catch (Throwable throwable) {
            // 最后的防线也失败, 这份数据已经没有去处, 如实报出
            this.logger.error(
                    LogCategory.STASH,
                    snapshot.meta().player(),
                    playerName,
                    throwable,
                    LogConstants.STASH_WRITE_FAILED,
                    playerName,
                    snapshot.meta().id().toString(),
                    (pending ? this.files.pending() : this.files.exceptions().resolve(directoryOf(reason))).toString()
            );
        }
    }

    /**
     * 留存残缺快照及关键失败类型, 写入失败时记录日志, 供管理员处理.
     *
     * @param snapshot 采集和编码成功的数据, 包含保留的未注册类型
     * @param playerName 日志和快照文件名使用的玩家名
     * @param failedKeys 采集或编码失败的关键类型
     */
    public void stashIncomplete(@NotNull Snapshot snapshot, @NotNull String playerName, @NotNull List<DataKey> failedKeys) {
        try {
            Path file = this.files.writeIncomplete(snapshot, playerName, failedKeys);
            this.logger.warn(LogCategory.STASH, snapshot.meta().player(), playerName, LogConstants.STASH_EXCEPTION, playerName, file.toString());
        } catch (Throwable throwable) {
            this.logger.error(
                    LogCategory.STASH,
                    snapshot.meta().player(),
                    playerName,
                    throwable,
                    LogConstants.STASH_WRITE_FAILED,
                    playerName,
                    snapshot.meta().id().toString(),
                    this.files.exceptions().resolve("incomplete").toString()
            );
        }
    }

    /**
     * 启动时将本地快照逐份重新插入数据库, 数据库暂时不可用时保留剩余文件并停止.
     *
     * @param storage 当前存储后端
     */
    public void restorePending(@NotNull StorageProvider storage) {
        this.restorePending(storage, true);
    }

    // 处理一轮本地快照. 运行期的轮次只在有文件离开 pending 目录时输出到控制台, 数据库不可用只写入文件日志.
    private void restorePending(@NotNull StorageProvider storage, boolean startup) {
        List<Path> files;
        synchronized (this.pendingLock) {
            files = this.listPendingFiles();
            if (files.isEmpty()) this.pendingPresent = false;
        }
        if (files.isEmpty()) {
            return;
        }
        if (startup) {
            this.logger.info(LogCategory.STASH, LogConstants.STASH_RESTORE_FOUND, String.valueOf(files.size()));
        }
        int restored = 0;
        int leftover = 0;
        for (int i = 0; i < files.size(); i++) {
            if (this.closed) {
                leftover = files.size() - i;
                break;
            }
            RestoreOutcome outcome = this.restoreOne(files.get(i), storage, startup);
            if (outcome == RestoreOutcome.RESTORED) {
                restored++;
            }
            // 数据库又不行了, 剩下的文件原样留给下一轮
            if (outcome == RestoreOutcome.STORAGE_UNAVAILABLE) {
                leftover = files.size() - i;
                if (startup) {
                    this.logger.warn(LogCategory.STASH, LogConstants.STASH_RESTORE_UNAVAILABLE, String.valueOf(leftover));
                }
                break;
            }
        }
        if (startup || leftover < files.size()) {
            this.logger.info(LogCategory.STASH, LogConstants.STASH_RESTORE_DONE, String.valueOf(restored), String.valueOf(files.size() - restored - leftover), String.valueOf(leftover));
        }
    }

    // 读取一份本地待重试的快照并重新插入数据库, 根据结果删除、转为异常快照或保留.
    private RestoreOutcome restoreOne(@NotNull Path file, @NotNull StorageProvider storage, boolean startup) {
        DecodedSnapshot decoded;
        try {
            decoded = this.files.readPending(file);
        } catch (IOException exception) {
            this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_CORRUPTED, file.getFileName().toString());
            this.moveToException(file, "corrupted", null);
            return RestoreOutcome.DISCARDED;
        }
        if (!(decoded instanceof DecodedSnapshot.Valid(Snapshot snapshot))) {
            DecodedSnapshot.Invalid invalid = (DecodedSnapshot.Invalid) decoded;
            this.logger.error(LogCategory.STASH, LogConstants.STASH_CORRUPTED, file.getFileName() + " (" + invalid.reason() + ": " + invalid.detail() + ")");
            this.moveToException(file, "corrupted", null);
            return RestoreOutcome.DISCARDED;
        }
        SaveOutcome saved;
        try {
            CompletableFuture<SaveOutcome> saving = storage.saveSnapshotOutcome(snapshot);
            // 先登记再检查关服标记, 与 stopUploading 的顺序相对, 两边至少有一方取消这次等待
            this.uploading = saving;
            if (this.closed) saving.cancel(false);
            saved = saving.join();
        } catch (RuntimeException exception) {
            if (startup) {
                this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_RESTORE_FAILED, file.getFileName().toString());
            } else if (!this.closed) {
                this.logger.file(LogCategory.STASH, null, null, exception, LogConstants.STASH_RESTORE_FAILED, file.getFileName().toString());
            }
            return RestoreOutcome.STORAGE_UNAVAILABLE;
        }
        SaveResult result = saved.result();
        // 落库结果三分: 已在库中的删掉文件, 数据库不可用的整轮收工, 被拒的移去给管理员
        if (result.stored()) {
            this.cache.invalidate(snapshot.meta().player()).join();
            this.delete(file);
            return RestoreOutcome.RESTORED;
        }
        if (result.retriable()) {
            if (saved.failure() != null) {
                this.logger.file(LogCategory.STORAGE, snapshot.meta().player(), null, saved.failure(), LogConstants.STORAGE_WRITE_RETRIABLE, snapshot.meta().player().toString());
            }
            return RestoreOutcome.STORAGE_UNAVAILABLE;
        }
        this.logger.error(LogCategory.STASH, LogConstants.STASH_RESTORE_REJECTED, file.getFileName().toString(), result.name());
        this.moveToException(file, directoryOf(result), snapshot.meta());
        return RestoreOutcome.DISCARDED;
    }

    // 筛选本地待重试的快照数据文件并清理临时文件, 按文件路径排序.
    @NotNull
    private List<Path> listPendingFiles() {
        List<Path> files = new ArrayList<>();
        try {
            List<Path> entries = this.files.pendingEntries();
            for (int i = 0; i < entries.size(); i++) {
                Path entry = entries.get(i);
                String name = entry.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    this.delete(entry);
                } else if (name.endsWith(".snapshot")) {
                    files.add(entry);
                }
            }
        } catch (IOException exception) {
            this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_RESTORE_FAILED, this.files.pending().toString());
            return List.of();
        }
        files.sort(null);
        return files;
    }

    // 将被永久拒绝或无法读取的本地快照移入异常快照目录, 并记录文件操作失败.
    private void moveToException(@NotNull Path file, @NotNull String category, @Nullable SnapshotMeta meta) {
        try {
            this.files.moveToException(file, category, meta);
        } catch (IOException exception) {
            this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_RESTORE_FAILED, file.toString());
        }
    }

    // 删除已重新插入数据库的本地快照数据和快照头文件, 并记录文件删除失败.
    private void delete(@NotNull Path file) {
        try {
            this.files.deletePending(file);
        } catch (IOException exception) {
            this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_RESTORE_FAILED, file.toString());
        }
    }

    // 将不可重试的保存结果映射到既有异常目录.
    @NotNull
    private static String directoryOf(@NotNull SaveResult reason) {
        return reason == SaveResult.REJECTED_OVERSIZED ? "oversized" : "malformed";
    }

    /** 将一份本地快照重新插入数据库后的处理结果, 数据库不可用时停止处理剩余快照. */
    private enum RestoreOutcome {
        RESTORED,
        DISCARDED,
        STORAGE_UNAVAILABLE
    }
}