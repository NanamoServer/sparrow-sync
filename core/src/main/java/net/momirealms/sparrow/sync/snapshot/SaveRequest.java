package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotStash;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class SaveRequest {
    private final SnapshotMeta meta; // 请求接收时分配的快照 ID、时间和保存原因
    private final String playerName; // 请求接收时的玩家名, 用于事件、日志和异常快照头
    private final SnapshotData retainedData; // 本服未注册的数据, 保存时与新数据合并
    private final MapType mapType; // 请求接收时确定的地图模式, 未启用或 RESTORE 时为 null
    private final CompletableFuture<SnapshotSaveResult> completion = new CompletableFuture<>(); // 保存或本地暂存结束后完成
    private final Player logoutPlayer; // 退出请求保留离线玩家, 供关服线程补采集
    private volatile PlayerDataPipeline.CaptureResult captured; // 关服请求已完成的采集结果, 供超时收尾编码
    private volatile Snapshot snapshot; // 准备好的完整快照, 编码前为 null, 开始结束处理后不再替换
    private long captureNanos; // 采集耗时, 单位纳秒, 在快照可见前写入
    private long encodeNanos;  // 编码耗时, 单位纳秒, 在快照可见前写入, RESTORE 为 0
    private volatile boolean finishing; // 是否已有线程开始结束此请求

    SaveRequest(@NotNull SnapshotMeta meta, @NotNull String playerName, @NotNull SnapshotData retainedData, @Nullable MapType mapType, @Nullable Player logoutPlayer) {
        this.meta = meta;
        this.playerName = playerName;
        this.retainedData = retainedData;
        this.mapType = mapType;
        this.logoutPlayer = logoutPlayer;
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

    /** 保留关服请求的采集结果, 须在提交串行编码任务前调用. */
    void captured(@NotNull PlayerDataPipeline.CaptureResult captured) {
        this.captured = captured;
    }

    /**
     * 在关服线程补齐尚未生成的快照, 跳过地图处理和保存事件.
     * <strong>调用方须已通过 beginFinish 取得请求的结束权</strong>.
     * @param pipeline 玩家数据采集与编码流程
     * @return 编码结果, 未持有完整采集结果或离线玩家时返回 null
     */
    @Nullable
    Encoded produceUnsettled(@NotNull PlayerDataPipeline pipeline) {
        PlayerDataPipeline.CaptureResult source = this.captured;
        if (source == null && this.logoutPlayer != null) {
            source = pipeline.capture(this.logoutPlayer, CaptureMode.OFFLINE);
        }
        if (source == null) return null;
        return this.encode(pipeline, source);
    }

    // 编码成功采集的类型, 合并未注册数据, 同时保留采集和编码的关键失败类型
    @NotNull
    Encoded encode(@NotNull PlayerDataPipeline pipeline, @NotNull PlayerDataPipeline.CaptureResult captureResult) {
        // 编码结果独立返回, 仅完整结果可登记为请求的待入库快照
        List<DataKey> failedKeys = new ArrayList<>();
        PlayerDataPipeline.CaptureResult.Ready captured = switch (captureResult) {
            case PlayerDataPipeline.CaptureResult.Ready ready -> ready;
            case PlayerDataPipeline.CaptureResult.Failed failed -> {
                failedKeys.addAll(failed.failedKeys());
                yield failed.partial();
            }
            case PlayerDataPipeline.CaptureResult.Pending ignored -> throw new IllegalStateException("capture is not finished");
        };
        long encodeStarted = System.nanoTime();
        PlayerDataPipeline.EncodeResult result = pipeline.encode(captured);
        this.encodeNanos = System.nanoTime() - encodeStarted;
        PlayerDataPipeline.EncodeResult.Ready encoded = switch (result) {
            case PlayerDataPipeline.EncodeResult.Ready ready -> ready;
            case PlayerDataPipeline.EncodeResult.Failed failed -> {
                failedKeys.addAll(failed.failedKeys());
                yield failed.partial();
            }
        };
        this.captureNanos = captured.captureNanos();
        return new Encoded(new Snapshot(this.meta, this.retainedData.with(encoded.data())), List.copyOf(failedKeys));
    }

    /**
     * 留存退出或关服请求的残缺数据, 随后以关键类型失败结束请求.
     * <strong>调用方须已通过 beginFinish 取得请求的结束权</strong>.
     * @param stash 本地快照留存服务
     * @param encoded 含关键失败类型的编码结果
     */
    void finishIncomplete(@NotNull SnapshotStash stash, @NotNull Encoded encoded) {
        if (this.meta.cause() == SaveCause.DISCONNECT || this.meta.cause() == SaveCause.SHUTDOWN) {
            stash.stashIncomplete(encoded.snapshot(), this.playerName, encoded.failedKeys());
        }
        this.completion.completeExceptionally(new IllegalStateException("critical data of " + this.playerName + " could not be captured or encoded: " + encoded.failedKeys()));
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

    record Encoded(@NotNull Snapshot snapshot, @NotNull List<DataKey> failedKeys) {
    }
}
