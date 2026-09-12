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
    private final StorageProvider storage; // 导入的直接存储与导出的正文来源
    private final SnapshotFiles files; // 普通快照文件及路径规则
    private final Executor executor; // 文件 I/O 执行器
    private final SnapshotCache cache;

    public SnapshotTransfer(@NotNull StorageProvider storage, @NotNull SnapshotFiles files, @NotNull Executor executor, @NotNull SnapshotCache cache) {
        this.storage = storage;
        this.files = files;
        this.executor = executor;
        this.cache = cache;
    }

    /**
     * 将指定数据库快照导出到 output 目录.
     *
     * @param snapshotId 明确选定的快照 ID
     * @param format 导出文件格式
     * @return 导出的快照 ID 与路径, 或不存在结果
     */
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
     * 导入保留原身份与时间的完整快照, 覆盖相同 ID 的记录.
     * 写库前读取全部类型, 校验块头、CRC、解压长度与 NBT 结构, 任一块无效时保留原记录.
     * 保存成功后清除所属玩家的缓存, 删除尝试结束后再返回导入结果.
     *
     * @param relative 选定文件在本服目录内的相对路径
     * @return 导入、无效文件或保存失败结果
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
            // 容器解码只读出元数据和索引, 这里逐块读取, 本服未注册的类型起码得能解码出来.
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
