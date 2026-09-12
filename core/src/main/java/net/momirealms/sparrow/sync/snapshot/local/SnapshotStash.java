package net.momirealms.sparrow.sync.snapshot.local;

import net.momirealms.sparrow.sync.cluster.cache.SnapshotCache;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
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

public final class SnapshotStash {
    private SparrowSync plugin;
    private SnapshotFiles files; // 与管理查询共用的快照文件操作
    private SyncLogger logger;
    private SnapshotCache cache;

    public SnapshotStash(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public SnapshotStash(@NotNull Path dataFolder, @NotNull BinarySnapshotCodec codec, @NotNull SyncLogger logger, @NotNull SnapshotCache cache) {
        this.files = new SnapshotFiles(dataFolder, codec, logger);
        this.logger = logger;
        this.cache = cache;
    }

    public void onLoad(@NotNull SnapshotFiles files) {
        this.files = files;
        this.logger = this.plugin.logger();
        this.cache = this.plugin.snapshotCache();
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
            Path file = this.files.write(snapshot, playerName, pending ? null : directoryOf(reason));
            String key = pending ? LogConstants.STASH_PENDING : LogConstants.STASH_EXCEPTION;
            this.logger.warn(LogCategory.STASH, snapshot.meta().player(), playerName, key, playerName, file.toString());
        } catch (Throwable throwable) {
            // 最后的防线也失败, 这份数据已经没有去处, 如实报出
            this.logger.error(LogCategory.STASH, snapshot.meta().player(), playerName, throwable, LogConstants.STASH_WRITE_FAILED, playerName);
        }
    }

    /**
     * 启动时将本地快照逐份重新插入数据库, 数据库暂时不可用时保留剩余文件并停止.
     *
     * @param storage 当前存储后端
     */
    public void restorePending(@NotNull StorageProvider storage) {
        List<Path> files = this.listPendingFiles();
        if (files.isEmpty()) {
            return;
        }
        this.logger.info(LogCategory.STASH, LogConstants.STASH_RESTORE_FOUND, String.valueOf(files.size()));
        int restored = 0;
        int leftover = 0;
        for (int i = 0; i < files.size(); i++) {
            RestoreOutcome outcome = this.restoreOne(files.get(i), storage);
            if (outcome == RestoreOutcome.RESTORED) {
                restored++;
            }
            // 数据库又不行了, 剩下的文件原样留给下次启动
            if (outcome == RestoreOutcome.STORAGE_UNAVAILABLE) {
                leftover = files.size() - i;
                this.logger.warn(LogCategory.STASH, LogConstants.STASH_RESTORE_UNAVAILABLE, String.valueOf(leftover));
                break;
            }
        }
        this.logger.info(LogCategory.STASH, LogConstants.STASH_RESTORE_DONE, String.valueOf(restored), String.valueOf(files.size() - restored - leftover), String.valueOf(leftover));
    }

    // 读取一份本地待重试的快照并重新插入数据库, 根据结果删除、转为异常快照或保留.
    private RestoreOutcome restoreOne(@NotNull Path file, @NotNull StorageProvider storage) {
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
            saved = storage.saveSnapshotOutcome(snapshot).join();
        } catch (RuntimeException exception) {
            this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_RESTORE_FAILED, file.getFileName().toString());
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
