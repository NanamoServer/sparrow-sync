package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

final class SaveRequest {
    private final SnapshotMeta meta; // 请求接收时分配的快照 ID、时间和保存原因
    private final String playerName; // 请求接收时的玩家名, 用于事件、日志和异常快照头
    private final SnapshotData retainedData; // 本服未注册的数据, 保存时与新数据合并
    private final MapType mapType; // 请求接收时确定的地图模式, 未启用或 RESTORE 时为 null
    private final CompletableFuture<SnapshotSaveResult> completion = new CompletableFuture<>(); // 保存或本地暂存结束后完成
    private volatile Snapshot snapshot; // 准备好的完整快照, 编码前为 null, 开始结束处理后不再替换
    private long captureNanos; // 采集耗时, 单位纳秒, 在快照可见前写入
    private long encodeNanos;  // 编码耗时, 单位纳秒, 在快照可见前写入, RESTORE 为 0
    private volatile boolean finishing; // 是否已有线程开始结束此请求

    SaveRequest(@NotNull SnapshotMeta meta, @NotNull String playerName, @NotNull SnapshotData retainedData, @Nullable MapType mapType) {
        this.meta = meta;
        this.playerName = playerName;
        this.retainedData = retainedData;
        this.mapType = mapType;
    }

    @NotNull
    SnapshotMeta meta() {
        return this.meta;
    }

    @NotNull
    String playerName() {
        return this.playerName;
    }

    @NotNull
    SnapshotData retainedData() {
        return this.retainedData;
    }

    @Nullable
    MapType mapType() {
        return this.mapType;
    }

    @NotNull
    CompletableFuture<SnapshotSaveResult> completion() {
        return this.completion;
    }

    @Nullable
    Snapshot snapshot() {
        return this.snapshot;
    }

    /**
     * 更新请求中的完整快照, 供停服时暂存.
     * <strong>开始写入数据库后不得再更新</strong>; 请求已进入结束处理时返回 false.
     */
    synchronized boolean updateSnapshot(@NotNull Snapshot snapshot) {
        if (this.finishing) return false;
        this.snapshot = snapshot;
        return true;
    }

    long captureNanos() {
        return this.captureNanos;
    }

    void captureNanos(long captureNanos) {
        this.captureNanos = captureNanos;
    }

    long encodeNanos() {
        return this.encodeNanos;
    }

    void encodeNanos(long encodeNanos) {
        this.encodeNanos = encodeNanos;
    }

    boolean finishing() {
        return this.finishing;
    }

    synchronized boolean beginFinish() {
        if (this.finishing) return false;
        this.finishing = true;
        return true;
    }

    void fail(@NotNull Throwable throwable) {
        if (this.beginFinish()) {
            this.completion.completeExceptionally(throwable);
        }
    }
}
