package net.momirealms.sparrow.sync.api;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDeleteResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotPinResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotUnpinResult;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SparrowSyncAPI {
    private final SparrowSync plugin;

    @ApiStatus.Internal
    public SparrowSyncAPI(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    /**
     * 查询玩家逻辑时间戳最新的数据库快照, 数据正文沿用 {@link #snapshot(UUID)} 的按需读取契约.
     *
     * @param playerId 玩家 UUID, 玩家可处于任意在线状态
     * @return 最新快照, 没有记录时为 empty
     */
    @NotNull
    public CompletableFuture<Optional<Snapshot>> latestSnapshot(@NotNull UUID playerId) {
        if (!this.plugin.apiReady()) return CompletableFuture.failedFuture(new IllegalStateException("SparrowSync API is not ready"));
        return this.plugin.storageProvider().latestSnapshot(playerId);
    }

    /**
     * 按 ID 查询数据库快照, 类型数据在访问时按需解析, 解析失败由该次访问抛出.
     * <strong>调用方须只读使用返回的 Tag 和数据内容.</strong> 快照对象的内存修改不会写回数据库.
     *
     * @param snapshotId 快照 ID
     * @return 选定快照, 没有记录时为 empty; 存储或读取格式错误使 future 异常完成
     */
    @NotNull
    public CompletableFuture<Optional<Snapshot>> snapshot(@NotNull UUID snapshotId) {
        if (!this.plugin.apiReady()) return CompletableFuture.failedFuture(new IllegalStateException("SparrowSync API is not ready"));
        return this.plugin.storageProvider().snapshot(snapshotId);
    }

    /**
     * 按逻辑时间戳从新到旧查询一页快照元数据, 查询仅加载元数据.
     * 多次分页之间若发生保存或删除, 后续页的位置会随数据库内容变化.
     *
     * @param playerId 玩家 UUID
     * @param offset 跳过的记录数, 从 0 开始
     * @param limit 本页最多返回的记录数, 必须大于 0
     * @return 不可变的元数据列表, 没有匹配记录时为空列表
     * @throws IllegalArgumentException offset 小于 0 或 limit 不大于 0
     */
    @NotNull
    public CompletableFuture<List<SnapshotMeta>> snapshots(@NotNull UUID playerId, int offset, int limit) {
        if (offset < 0 || limit <= 0) {
            throw new IllegalArgumentException("snapshot offset must be non-negative and limit must be positive: " + offset + ", " + limit);
        }
        if (!this.plugin.apiReady()) return CompletableFuture.failedFuture(new IllegalStateException("SparrowSync API is not ready"));
        return this.plugin.storageProvider().listSnapshots(SnapshotQuery.of(playerId).withOffset(offset).withLimit(limit)).thenApply(List::copyOf);
    }

    /**
     * 固定指定快照, 固定记录豁免自动轮转清理.
     *
     * @param snapshotId 快照 ID
     * @return 已固定、原本已固定或快照不存在的结果
     */
    @NotNull
    public CompletableFuture<SnapshotPinResult> pin(@NotNull UUID snapshotId) {
        if (!this.plugin.apiReady()) return CompletableFuture.failedFuture(new IllegalStateException("SparrowSync API is not ready"));
        return this.plugin.snapshotService().pin(snapshotId);
    }

    /**
     * 取消固定指定快照, 记录重新参与后续的自动轮转清理.
     *
     * @param snapshotId 快照 ID
     * @return 已取消固定、原本未固定或快照不存在的结果
     */
    @NotNull
    public CompletableFuture<SnapshotUnpinResult> unpin(@NotNull UUID snapshotId) {
        if (!this.plugin.apiReady()) return CompletableFuture.failedFuture(new IllegalStateException("SparrowSync API is not ready"));
        return this.plugin.snapshotService().unpin(snapshotId);
    }

    /**
     * 删除指定数据库快照并清除该玩家的登录快照缓存, 固定记录也可显式删除.
     *
     * @param snapshotId 快照 ID
     * @return 删除与缓存清理完成, 或快照不存在的结果; 清理失败时记录可能已删除
     */
    @NotNull
    public CompletableFuture<SnapshotDeleteResult> delete(@NotNull UUID snapshotId) {
        if (!this.plugin.apiReady()) return CompletableFuture.failedFuture(new IllegalStateException("SparrowSync API is not ready"));
        return this.plugin.snapshotService().delete(snapshotId);
    }
}
