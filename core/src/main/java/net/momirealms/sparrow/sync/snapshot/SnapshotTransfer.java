package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.session.operation.SnapshotExportResult;
import net.momirealms.sparrow.sync.session.operation.SnapshotImportResult;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@ApiStatus.Internal
public final class SnapshotTransfer {
    private final StorageProvider storage; // 导入的直接存储与导出的正文来源
    private final SnapshotFiles files; // 普通快照文件及路径规则
    private final Executor executor; // 文件 I/O 执行器

    public SnapshotTransfer(@NotNull StorageProvider storage, @NotNull SnapshotFiles files, @NotNull Executor executor) {
        this.storage = storage;
        this.files = files;
        this.executor = executor;
    }

    /**
     * 将指定数据库快照导出为完整文件, 按发送者类型选择输出目录.
     *
     * @param snapshotId 明确选定的快照 ID
     * @param format 导出文件格式
     * @return 导出的快照 ID 与路径, 或不存在结果
     */
    @NotNull
    public CompletableFuture<SnapshotExportResult> export(@NotNull UUID snapshotId, @NotNull SnapshotFiles.Format format) {
        return this.storage.snapshot(snapshotId).thenApplyAsync(found -> {
            if (found.isEmpty()) return new SnapshotExportResult.NotFound();
            Snapshot snapshot = found.get();
            try {
                return new SnapshotExportResult.Exported(snapshotId, this.files.export(snapshot, format));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, this.executor);
    }

    /**
     * 导入保留原身份与时间的完整快照, 同 ID 的不同内容作为冲突返回.
     *
     * @param relative 选定文件在本服目录内的相对路径
     * @return 导入、幂等、冲突、无效文件或保存失败结果
     */
    @NotNull
    public CompletableFuture<SnapshotImportResult> importFile(@NotNull String relative) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.files.read(relative);
            } catch (IOException | IllegalArgumentException failure) {
                throw new CompletionException(failure);
            }
        }, this.executor).thenCompose(decoded -> {
            if (!(decoded instanceof DecodedSnapshot.Valid valid)) return CompletableFuture.completedFuture(new SnapshotImportResult.InvalidFile());
            Snapshot snapshot = valid.snapshot();
            UUID snapshotId = snapshot.meta().id();
            return this.storage.snapshot(snapshotId).thenCompose(existing -> {
                if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get().equals(snapshot) ? new SnapshotImportResult.Unchanged(snapshotId) : new SnapshotImportResult.Conflict(snapshotId));
                // 导入保持原身份和时间, 直接入库, 自动轮转属于正常保存流程.
                return this.storage.saveSnapshot(snapshot).thenCompose(saved -> {
                    if (saved == StorageProvider.SaveResult.DUPLICATE) {
                        return this.storage.snapshot(snapshotId).thenApply(current -> current.filter(snapshot::equals).isPresent() ? new SnapshotImportResult.Unchanged(snapshotId) : new SnapshotImportResult.Conflict(snapshotId));
                    }
                    return CompletableFuture.completedFuture(saved.stored() ? new SnapshotImportResult.Imported(snapshotId) : new SnapshotImportResult.Failed());
                });
            });
        });
    }
}
