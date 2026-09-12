package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.event.SnapshotSaveEvent;
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
    private final PlayerDataPipeline playerDataPipeline; // 三种采集模式、类型编码与地图准备
    private final PlayerSerialExecutor serialExecutor;   // 同玩家采集后处理及地图等待所在的 worker
    private final SnapshotWriter writer; // 持有全部在途请求并执行写入、重试与停服暂存
    private volatile boolean operationsClosed; // 是否关闭主动命令采集, 最终 SHUTDOWN 保存仍可接收
    private final ConcurrentHashMap<UUID, Long> lastTimestampByPlayer = new ConcurrentHashMap<>(); // 本次插件运行期间各玩家最后分配的毫秒逻辑时间

    SnapshotSaver(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
        this.logger = plugin.logger();
        this.playerDataPipeline = plugin.playerDataPipeline();
        this.serialExecutor = plugin.playerExecutor();
        this.writer = new SnapshotWriter(this.logger, plugin.storageProvider(), plugin.snapshotStash(), this.serialExecutor, plugin.snapshotCache());
    }

    /**
     * 在玩家线程采集 ACTIVE 玩家的当前状态.
     *
     * @param player 当前操作绑定的玩家对象
     * @return 采集成功的快照 ID, 或取消、离线、保存失败结果
     */
    @NotNull
    public CompletableFuture<SnapshotCaptureResult> capture(@NotNull Player player) {
        if (this.operationsClosed) return CompletableFuture.completedFuture(SnapshotCaptureResult.OFFLINE);
        CompletableFuture<SnapshotCaptureResult> result = new CompletableFuture<>();
        Runnable capture = () -> {
            try {
                PlayerSession session = this.plugin.sessionManager().find(player.getUniqueId());
                if (!player.isOnline() || session == null || session.state() != SessionState.ACTIVE) {
                    result.complete(SnapshotCaptureResult.OFFLINE);
                    return;
                }
                CompletableFuture<SnapshotSaveResult> saved = this.plugin.sessionManager().captureNowAndSave(session, player, SaveCause.COMMAND);
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

    // 在玩家线程采集全部数据类型, 然后由 worker 编码, 处理地图并保存.
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

    // 在玩家线程采集必须同步的数据类型, 然后投递到 worker 补齐采集异步类型数据后保存.
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

    // 在异步线程采集离线玩家并保存数据.
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

    // 在玩家 worker 线程编码并等待地图结果, 随后继续派发事件和提交写入.
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
        // 原正文在地图等待前发布, 停服线程通过 Writer 持有的同一请求取得它.
        if (!request.updateSnapshot(snapshot)) return;
        if (request.mapType() != null) {
            if (request.finishing()) return;
            try {
                // 逐图去重、并行发布、超时及回退由地图管线完成, 当前 worker 等整批结果后继续.
                snapshot = this.playerDataPipeline.prepareForStorage(snapshot, request.mapType(), request.playerName()).get();
            } catch (InterruptedException exception) {
                // 执行器在停服预算耗尽后中断等待, 完整正文留在 Writer 中统一暂存.
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

    // 把请求的下一阶段投递到玩家队列, 提交或执行失败时结束同一份结果回执.
    private void submitSerial(@NotNull SaveRequest request, @NotNull Runnable task) {
        if (request.finishing()) return;
        try {
            this.serialExecutor.submit(request.meta().player(), () -> {
                if (request.finishing()) return;
                try {
                    task.run();
                } catch (RuntimeException | Error throwable) {
                    // 执行器负责报告原异常, 保存请求在这里完成回执并退出在途集合.
                    request.fail(throwable);
                    throw throwable;
                }
            });
        } catch (RejectedExecutionException exception) {
            // 拒绝发生在提交线程, 任务尚未入队, 不会进入上面的执行阶段收尾.
            request.fail(exception);
        }
    }

    // 对完整正文派发保存事件, 通过后启动 Writer 的实际写入.
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
        // 写库任务可能排在当前任务之后, 最终结果交给 Future 反馈, 当前 worker 继续处理队列.
        this.writer.write(request);
    }

    // 以新身份和逻辑时间登记历史原内容, 经过保存事件后提交 RESTORE 记录.
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
                .mcDataVersion(source.meta().mcDataVersion())
                .build();
        Snapshot restored = new Snapshot(meta, source.content());
        SaveRequest request = new SaveRequest(meta, playerName, EagerSnapshotData.EMPTY, null);
        request.updateSnapshot(restored);
        this.writer.register(request);
        this.submitSerial(request, () -> this.writePrepared(request, restored));
        return request.completion();
    }

    /**
     * 停止接收新的管理操作, 已接收的保存及会话最终 SHUTDOWN 保存继续完成.
     */
    public void stopOperations() {
        this.operationsClosed = true;
    }

    /**
     * 封闭保存入口并等待最终结果, 期间继续重试, 完成数增加时重置停滞计时.
     *
     * @param timeout 连续无进展的最长等待时间, 非正数表示不等待
     * @param unit 等待时间的单位
     * @return 是否结束全部已接收请求, 单份异常结束也计为结束
     */
    public boolean sealAndAwaitSaves(long timeout, @NotNull TimeUnit unit) {
        return this.writer.sealAndAwaitSaves(timeout, unit);
    }

    /**
     * 由 Writer 暂存所有已生成的在途正文, 尚未编码的请求以超时失败结束.
     */
    public void stashUnsettled() {
        this.writer.stashUnsettled();
    }

    /**
     * 分配快照身份与逻辑时间并登记请求, 固定本次使用的地图模式.
     *
     * @param player 当前操作绑定的玩家对象
     * @param cause 本次保存原因
     * @param retainedData 会话保留的未注册类型原数据
     * @return 已交给 Writer 登记的保存请求
     * @throws RejectedExecutionException 当 Writer 已停止接收保存时
     */
    @NotNull
    private SaveRequest newRequest(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        // 请求接受时分配逻辑时间戳, 同一玩家在并发调用下仍严格递增.
        Long ts = this.lastTimestampByPlayer.merge(player.getUniqueId(), System.currentTimeMillis(), (last, now) -> Math.max(now, last + 1));
        SnapshotMeta snapshotMeta = SnapshotMeta.builder()
                .player(player.getUniqueId())
                .timestamp(ts)
                .cause(cause)
                .server(ServerConfig.serverId())
                .mcDataVersion(VersionHelper.WORLD_VERSION)
                .build();
        // 重载只影响随后开始的保存, 排队中的任务继续使用接受时的地图模式.
        SaveRequest request = new SaveRequest(snapshotMeta, player.getName(), retainedData, this.playerDataPipeline.mapMode());
        this.writer.register(request);
        return request;
    }
}
