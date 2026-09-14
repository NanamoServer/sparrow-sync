package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.api.event.SnapshotSaveEvent;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionState;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotCaptureResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.util.EventUtils;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

final class SnapshotSaver {
    private final SparrowSync plugin;
    private final SyncLogger logger;
    private final PlayerDataPipeline playerDataPipeline; // 采集、编码和地图处理
    private final PlayerSerialExecutor serialExecutor;   // 同一玩家的任务按序执行
    private final SnapshotWriter writer; // 登记保存请求并负责写入、重试和停服暂存
    private volatile boolean operationsClosed; // 停止主动采集请求, 仍接受关服保存
    private final ConcurrentHashMap<UUID, Long> lastTimestampByPlayer = new ConcurrentHashMap<>(); // 本次运行中各玩家最后分配的毫秒时间戳

    SnapshotSaver(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
        this.logger = plugin.logger();
        this.playerDataPipeline = plugin.playerDataPipeline();
        this.serialExecutor = plugin.playerExecutor();
        this.writer = new SnapshotWriter(this.logger, plugin.storageProvider(), plugin.snapshotStash(), this.serialExecutor, plugin.snapshotCache());
    }

    // 在玩家线程采集 ACTIVE 会话, 等待保存结果
    @NotNull
    CompletableFuture<SnapshotCaptureResult> capture(@NotNull Player player, @NotNull SaveCause cause) {
        if (this.operationsClosed) return CompletableFuture.completedFuture(SnapshotCaptureResult.OFFLINE);
        PlayerSession session = this.plugin.sessionManager().find(player.getUniqueId());
        if (session == null || session.state() != SessionState.ACTIVE) return CompletableFuture.completedFuture(SnapshotCaptureResult.OFFLINE);
        CompletableFuture<SnapshotCaptureResult> result = new CompletableFuture<>();
        Runnable capture = () -> {
            try {
                if (this.operationsClosed || !player.isOnline() || this.plugin.sessionManager().find(session.uuid()) != session || session.state() != SessionState.ACTIVE) {
                    result.complete(SnapshotCaptureResult.OFFLINE);
                    return;
                }
                CompletableFuture<SnapshotSaveResult> saved = this.plugin.sessionManager().captureNowAndSave(session, player, cause);
                if (saved == null) {
                    result.complete(SnapshotCaptureResult.OFFLINE);
                    return;
                }
                saved.whenComplete((outcome, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(switch (outcome) {
                            case SnapshotSaveResult.Cancelled ignored -> SnapshotCaptureResult.CANCELLED;
                            case SnapshotSaveResult.Settled settled -> settled.result().stored()
                                    ? new SnapshotCaptureResult.Captured(settled.id())
                                    : SnapshotCaptureResult.FAILED;
                        });
                    }
                });
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        };
        if (this.plugin.scheduler().entity().isOwnedByCurrentRegion(player)) {
            capture.run();
        } else if (this.plugin.scheduler().entity().run(player, capture, () -> result.complete(SnapshotCaptureResult.OFFLINE)) == null) {
            result.complete(SnapshotCaptureResult.OFFLINE);
        }
        return result;
    }

    // 在玩家线程采集全部类型, 再由串行线程编码、处理地图并保存
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureNowAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        SaveRequest request = this.newRequest(player, cause, retainedData);
        try {
            if (request.finishing()) return request.completion();
            if (!(this.playerDataPipeline.capture(player, CaptureMode.SYNC) instanceof PlayerDataPipeline.CaptureResult.Ready captured)) {
                request.fail(new IllegalStateException("critical data of " + request.playerName() + " could not be captured"));
                return request.completion();
            }
            this.submitSerial(request, () -> this.encodeAndSubmit(request, captured));
        } catch (RuntimeException | Error failure) {
            request.fail(failure);
            throw failure;
        }
        return request.completion();
    }

    // 先在玩家线程采集同步类型, 再由串行线程补齐异步类型并保存
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureLaterAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        SaveRequest request = this.newRequest(player, cause, retainedData);
        try {
            if (request.finishing()) return request.completion();
            if (!(this.playerDataPipeline.capture(player, CaptureMode.ASYNC) instanceof PlayerDataPipeline.CaptureResult.Pending pending)) {
                request.fail(new IllegalStateException("critical data of " + request.playerName() + " could not be captured"));
                return request.completion();
            }
            this.submitSerial(request, () -> {
                if (!(this.playerDataPipeline.captureAsync(player, pending) instanceof PlayerDataPipeline.CaptureResult.Ready captured)) {
                    request.fail(new IllegalStateException("critical data of " + request.playerName() + " could not be captured"));
                    return;
                }
                this.encodeAndSubmit(request, captured);
            });
        } catch (RuntimeException | Error failure) {
            request.fail(failure);
            throw failure;
        }
        return request.completion();
    }

    // 在异步线程采集已退出的玩家并保存
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureLogoutAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        SaveRequest request = this.newRequest(player, cause, retainedData);
        this.submitSerial(request, () -> {
            if (!(this.playerDataPipeline.capture(player, CaptureMode.OFFLINE) instanceof PlayerDataPipeline.CaptureResult.Ready captured)) {
                request.fail(new IllegalStateException("critical data of " + request.playerName() + " could not be captured"));
                return;
            }
            this.encodeAndSubmit(request, captured);
        });
        return request.completion();
    }

    // 在玩家串行线程编码并等待地图处理, 随后发送事件并保存
    private void encodeAndSubmit(@NotNull SaveRequest request, @NotNull PlayerDataPipeline.CaptureResult.Ready captured) {
        if (request.finishing()) return;
        long encodeStarted = System.nanoTime();
        PlayerDataPipeline.EncodeResult result = this.playerDataPipeline.encode(captured);
        request.encodeNanos(System.nanoTime() - encodeStarted);
        if (!(result instanceof PlayerDataPipeline.EncodeResult.Ready encoded)) {
            request.fail(new IllegalStateException("critical data of " + request.playerName() + " could not be encoded"));
            return;
        }
        Snapshot snapshot = new Snapshot(request.meta(), request.retainedData().with(encoded.data()));
        request.captureNanos(captured.captureNanos());
        // 等待地图前先保存完整快照引用, 停服时可将这份数据暂存到本地
        if (!request.updateSnapshot(snapshot)) return;
        if (request.mapType() != null) {
            if (request.finishing()) return;
            try {
                // 等待这批地图处理结束, 单张地图的失败由地图处理流程负责
                snapshot = this.playerDataPipeline.prepareForStorage(snapshot, request.mapType(), request.playerName()).get();
            } catch (InterruptedException exception) {
                // 关服等待超时后恢复中断标记, 完整快照交给 Writer 暂存
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException exception) {
                if (request.beginFinish()) {
                    this.logger.error(LogCategory.SAVE, request.meta().player(), request.playerName(), exception.getCause(), LogConstants.SYNC_SAVE_FAILED, request.playerName());
                    request.completion().completeExceptionally(exception.getCause());
                }
                return;
            }
            if (!request.updateSnapshot(snapshot)) return;
        }
        this.writePrepared(request, snapshot);
    }

    // 将下一阶段加入玩家队列, 提交或执行失败时结束请求
    private void submitSerial(@NotNull SaveRequest request, @NotNull Runnable task) {
        if (request.finishing()) return;
        try {
            this.serialExecutor.submit(request.meta().player(), () -> {
                if (request.finishing()) return;
                try {
                    task.run();
                } catch (RuntimeException | Error throwable) {
                    // 结束请求并移出待完成集合, 原异常交给执行器记录
                    request.fail(throwable);
                    throw throwable;
                }
            });
        } catch (RejectedExecutionException exception) {
            // 任务未能入队, 在提交线程结束请求
            request.fail(exception);
        }
    }

    // 发送保存事件, 未取消时开始写入数据库
    private void writePrepared(@NotNull SaveRequest request, @NotNull Snapshot snapshot) {
        if (request.finishing()) return;
        SnapshotSaveEvent event = new SnapshotSaveEvent(request.playerName(), snapshot, request.completion().minimalCompletionStage());
        if (EventUtils.fireAndCheckCancel(event)) {
            if (request.beginFinish()) {
                this.logger.file(LogCategory.SAVE, request.meta().player(), request.playerName(), LogConstants.SYNC_SAVE_CANCELLED_BY_EVENT, request.playerName(), request.meta().cause().name(), request.meta().id().toString());
                request.completion().complete(SnapshotSaveResult.CANCELLED);
            }
            return;
        }
        // 保存结果异步返回, 当前线程继续处理队列
        this.writer.write(request);
    }

    // 保留历史数据内容, 分配新的 ID 和时间, 通过保存事件后写入 RESTORE 快照
    @NotNull
    CompletableFuture<SnapshotSaveResult> saveRestored(@NotNull Snapshot source, @NotNull String playerName) {
        UUID player = source.meta().player();
        long now = Math.max(System.currentTimeMillis(), source.meta().timestamp() + 1);
        long timestamp = this.lastTimestampByPlayer.merge(player, now, (last, current) -> Math.max(current, last + 1));
        SnapshotMeta meta = SnapshotMeta.builder()
                .player(player)
                .timestamp(timestamp)
                .cause(SaveCause.RESTORE)
                .server(ServerConfig.serverId())
                .mcDataVersion(VersionHelper.WORLD_VERSION)
                .build();
        Snapshot restored = new Snapshot(meta, source.content());
        SaveRequest request = new SaveRequest(meta, playerName, EagerSnapshotData.EMPTY, null);
        request.updateSnapshot(restored);
        this.writer.register(request);
        this.submitSerial(request, () -> this.writePrepared(request, restored));
        return request.completion();
    }

    /** 停止新管理操作, 已接收的保存和关服保存继续执行. */
    public void stopOperations() {
        this.operationsClosed = true;
    }

    /**
     * 停止接收新保存并等待已有请求结束, 每次有进展时重新计时.
     * @param timeout 连续无进展的最长等待时间, 非正数表示不等待
     * @param unit 时间单位
     * @return 所有请求均已结束时为 true, 异常完成也算结束
     */
    public boolean sealAndAwaitSaves(long timeout, @NotNull TimeUnit unit) {
        return this.writer.sealAndAwaitSaves(timeout, unit);
    }

    /** 暂存未完成请求的完整快照, 尚未编码的请求按超时结束. */
    public void stashUnsettled() {
        this.writer.stashUnsettled();
    }

    /**
     * 分配快照 ID 和时间, 固定地图模式, 并登记保存请求.
     * @throws RejectedExecutionException Writer 已停止接收请求时
     */
    @NotNull
    private SaveRequest newRequest(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        // 接收请求时分配时间戳, 同一玩家的时间戳在并发调用下也严格递增
        Long ts = this.lastTimestampByPlayer.merge(player.getUniqueId(), System.currentTimeMillis(), (last, now) -> Math.max(now, last + 1));
        SnapshotMeta snapshotMeta = SnapshotMeta.builder()
                .player(player.getUniqueId())
                .timestamp(ts)
                .cause(cause)
                .server(ServerConfig.serverId())
                .mcDataVersion(VersionHelper.WORLD_VERSION)
                .build();
        // 重载只影响新请求, 已排队的请求沿用原地图模式
        SaveRequest request = new SaveRequest(snapshotMeta, player.getName(), retainedData, this.playerDataPipeline.mapMode());
        this.writer.register(request);
        return request;
    }
}
