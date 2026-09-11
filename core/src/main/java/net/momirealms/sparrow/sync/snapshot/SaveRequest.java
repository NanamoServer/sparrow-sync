package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class SaveRequest {
    private final SnapshotMeta meta; // 接受请求时分配的身份、逻辑时间及保存原因
    private final String playerName; // 接受请求时的玩家名, 用于事件、日志和档案头
    private final Map<DataKey, Tag> retainedData; // 本服未注册类型的原始数据, 组装正文时与新编码值合并
    private final MapType mapType; // 接受请求时固定的地图模式, 未启用或 RESTORE 时为空
    private final CompletableFuture<SnapshotSaveResult> completion = new CompletableFuture<>(); // 全部最终处理结束后的唯一回执
    private volatile Snapshot snapshot; // 当前已发布的完整正文, 编码前为空, 收尾后冻结
    private long captureNanos; // 采集阶段耗时, 单位为纳秒, 在发布第一份正文前写入
    private long encodeNanos;  // 类型编码阶段耗时, 单位为纳秒, 在发布第一份正文前写入, RESTORE 为零
    private volatile boolean finishing; // 一次性收尾标记, 为 true 时已有调用方负责结束请求

    SaveRequest(@NotNull SnapshotMeta meta, @NotNull String playerName, @NotNull Map<DataKey, Tag> retainedData, @Nullable MapType mapType) {
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
    Map<DataKey, Tag> retainedData() {
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
     * 发布完整编码结果或地图准备结果, 使停服线程能够取得它.
     * <p><strong>实际写库开始后不得再次发布正文.</strong>
     *
     * @param snapshot 已构造完成的快照
     * @return 是否成功发布, 已进入收尾时返回 false, 调用方应结束后续准备
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
