package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.cluster.cache.SnapshotCache;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotExportResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotImportResult;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@ApiStatus.Internal
public final class SnapshotTransfer {
    private final StorageProvider storage; // 导入写入的数据库, 也是导出的数据来源
    private final SnapshotFiles files; // 本地快照文件
    private final Executor executor; // 文件 I/O 执行器
    private final SnapshotCache cache;

    public SnapshotTransfer(@NotNull StorageProvider storage, @NotNull SnapshotFiles files, @NotNull Executor executor, @NotNull SnapshotCache cache) {
        this.storage = storage;
        this.files = files;
        this.executor = executor;
        this.cache = cache;
    }

    /** 将数据库中的快照导出到 output 目录, 返回快照 ID 和文件路径. */
    @NotNull
    public CompletableFuture<SnapshotExportResult> export(@NotNull UUID snapshotId, @NotNull SnapshotFiles.Format format) {
        return this.storage.snapshot(snapshotId).thenApplyAsync(found -> {
            if (found.isEmpty()) return SnapshotExportResult.NOT_FOUND;
            Snapshot snapshot = found.get();
            try {
                return new SnapshotExportResult.Exported(snapshotId, this.files.export(snapshot, format));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, this.executor);
    }

    /**
     * 校验全部数据后导入快照, 保留原 ID 和时间, 覆盖同 ID 记录.
     * 任一数据块无效时不写入; 保存成功后等缓存清理尝试结束再返回.
     * @param relative 本服快照目录内的相对路径
     */
    @NotNull
    public CompletableFuture<SnapshotImportResult> importFile(@NotNull String relative) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.files.read(relative);
            } catch (IOException | IllegalArgumentException failure) {
                throw new CompletionException(failure);
            }
        }, this.executor).thenComposeAsync(decoded -> {
            if (!(decoded instanceof DecodedSnapshot.Valid valid)) return CompletableFuture.completedFuture(SnapshotImportResult.INVALID_FILE);
            Snapshot snapshot = valid.snapshot();
            // 逐块校验全部类型, 包括本服未注册的类型
            try {
                for (var key : snapshot.keys()) {
                    snapshot.data(key);
                }
            } catch (UncheckedIOException failure) {
                return CompletableFuture.completedFuture(SnapshotImportResult.INVALID_FILE);
            }
            return this.storage.importSnapshot(snapshot).thenCompose(saved -> {
                if (!saved.result().stored()) return CompletableFuture.completedFuture(SnapshotImportResult.FAILED);
                return this.cache.invalidate(snapshot.meta().player()).thenApply(ignored -> new SnapshotImportResult.Imported(snapshot.meta().id()));
            });
        }, this.executor);
    }
}
