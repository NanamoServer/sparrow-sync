package net.momirealms.sparrow.sync.session;

import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.event.SnapshotSaveEvent;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.*;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplyContext;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.util.EventUtils;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** 快照的读取、应用、采集和编码流水线. */
public final class SnapshotService {
    private final SparrowSync plugin;
    private SyncLogger logger;
    private DataRegistry dataRegistry;
    private PlayerDataPipeline playerDataPipeline;
    private PlayerSerialExecutor serialExecutor;
    private StorageProvider storage;
    private SnapshotWriter writer;
    private final ConcurrentHashMap<UUID, Long> lastTimestampByPlayer = new ConcurrentHashMap<>();
    private final SnapshotHandoffTracker handoffs = new SnapshotHandoffTracker();

    public SnapshotService(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
        this.logger = this.plugin.logger();
        this.dataRegistry = this.plugin.dataRegistry();
        this.playerDataPipeline = this.plugin.playerDataPipeline();
        this.serialExecutor = this.plugin.playerExecutor();
        this.storage = this.plugin.storageProvider();
        this.writer = new SnapshotWriter(this.logger, this.storage, this.plugin.snapshotStash(), this.serialExecutor);
    }

    public void onDelayedEnable() {
        // 冻结数据类型注册表并记录最终装配顺序.
        this.dataRegistry.freeze();
        StringJoiner activeTypes = new StringJoiner(", ");
        List<DataKey> applyOrder = this.playerDataPipeline.applyOrder();
        int dataTypeCount = applyOrder.size();
        for (int i = 0; i < dataTypeCount; i++) {
            activeTypes.add(applyOrder.get(i).asString());
        }
        this.logger.info(TranslationManager.console(LogConstants.PLUGIN_REGISTRY_FROZEN, String.valueOf(dataTypeCount), activeTypes.toString()));
    }

    /** 在 Gate 阶段把远端快照写入原版登录数据源. */
    @NotNull
    Optional<CompoundTag> applyNative(@NotNull PlayerSession session, @NotNull Optional<CompoundTag> localData, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.playerDataPipeline.applyNative(session, localData, loaded.context());
    }

    /**
     * 读取玩家最新的快照并在当前线程预解码.
     * 任意线程可调用, 关键数据无法解码时返回失败结果.
     */
    @NotNull
    CompletableFuture<SnapshotLoadResult> loadLatest(@NotNull UUID player, @NotNull String playerName) {
        long loadStart = System.nanoTime();
        return this.storage.latestSnapshot(player)
                .thenApply(latest -> latest
                        .map(snapshot -> this.prepare(snapshot, player, playerName, loadStart))
                        .orElseGet(() -> {
                            this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_EMPTY, playerName, millis(loadStart, System.nanoTime()));
                            return new SnapshotLoadResult.Empty();
                        }))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        this.logger.error(LogCategory.APPLY, player, playerName, throwable, LogConstants.SYNC_LOAD_FAILED, playerName, millis(loadStart, System.nanoTime()), String.valueOf(throwable));
                    }
                });
    }

    private SnapshotLoadResult prepare(Snapshot snapshot, UUID player, String playerName, long loadStart) {
        return switch (this.playerDataPipeline.prepare(snapshot)) {
            case PlayerDataPipeline.PrepareResult.Ready ready -> {
                long loadNanos = System.nanoTime() - loadStart;
                this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_READY, playerName, snapshot.meta().id().toString(), millis(0, loadNanos));
                yield new SnapshotLoadResult.Ready(snapshot, ready.context(), loadNanos);
            }
            case PlayerDataPipeline.PrepareResult.Failed failed -> {
                String detail = failed.key().asString() + ": " + failed.detail();
                this.logger.error(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_FAILED, playerName, millis(loadStart, System.nanoTime()), detail);
                yield new SnapshotLoadResult.Failed(detail);
            }
        };
    }

    /** 把预解码数据应用到玩家. <strong>必须在玩家的拥有线程上调用</strong>. */
    @NotNull
    SnapshotApplyResult apply(@NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
        long applyStart = System.nanoTime();
        this.logger.file(LogCategory.APPLY, player.getUniqueId(), player.getName(), LogConstants.SYNC_APPLY_STARTED, player.getName());
        SnapshotApplyContext context = loaded.context();
        return switch (this.playerDataPipeline.apply(player, context)) {
            case PlayerDataPipeline.ApplyResult.Success success -> {
                this.logger.file(LogCategory.APPLY, player.getUniqueId(), player.getName(),
                        LogConstants.SYNC_APPLIED,
                        player.getName(),
                        String.valueOf(success.applied().size()),
                        String.valueOf(success.skipped().size()),
                        millis(0, loaded.loadNanos()),
                        millis(applyStart, System.nanoTime())
                );
                yield new SnapshotApplyResult.Applied(success.applied(), success.skipped(), success.failures());
            }
            case PlayerDataPipeline.ApplyResult.Failure failure -> new SnapshotApplyResult.Failed(failure.failedKey().asString() + ": " + failure.detail());
        };
    }

    /**
     * 在当前线程立即采集玩家状态, 编码和保存阶段进入玩家串行线程.
     * 调用方负责保证当前线程允许读取玩家状态.
     */
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureNowAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        SaveRequest request = new SaveRequest();
        SaveContext context = this.newContext(player, cause, retainedData);
        if (!(this.playerDataPipeline.capture(player, CaptureMode.SYNC) instanceof PlayerDataPipeline.CaptureResult.Ready captured)) {
            request.fail(new IllegalStateException("critical data of " + player.getName() + " could not be captured"));
            return request.completion;
        }
        this.submitSerial(context.meta().player(), () -> this.encodeAndSubmit(context, captured, request), request);
        return request.completion;
    }

    /**
     * 玩家线程完成同步组采集, 串行线程补齐异步组并保存.
     */
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureLaterAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        SaveRequest request = new SaveRequest();
        SaveContext context = this.newContext(player, cause, retainedData);
        if (!(this.playerDataPipeline.capture(player, CaptureMode.ASYNC) instanceof PlayerDataPipeline.CaptureResult.Pending pending)) {
            request.fail(new IllegalStateException("critical data of " + context.playerName() + " could not be captured"));
            return request.completion;
        }
        this.submitSerial(context.meta().player(), () -> {
            if (!(this.playerDataPipeline.captureAsync(player, pending) instanceof PlayerDataPipeline.CaptureResult.Ready captured)) {
                request.fail(new IllegalStateException("critical data of " + context.playerName() + " could not be captured"));
                return;
            }
            this.encodeAndSubmit(context, captured, request);
        }, request);
        return request.completion;
    }

    // 调用方已跨过 Quit 后下一 Region tick, 借用值在本次串行任务内完成编码
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureOfflineAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        SaveRequest request = new SaveRequest();
        SaveContext context = this.newContext(player, cause, retainedData);
        this.submitSerial(context.meta().player(), () -> {
            if (!(this.playerDataPipeline.capture(player, CaptureMode.OFFLINE) instanceof PlayerDataPipeline.CaptureResult.Ready captured)) {
                request.fail(new IllegalStateException("critical data of " + context.playerName() + " could not be captured"));
                return;
            }
            this.encodeAndSubmit(context, captured, request);
        }, request);
        return request.completion;
    }

    // encode 的结果已经独立, 后续事件和存储只持有快照 Tag
    private void encodeAndSubmit(SaveContext context, PlayerDataPipeline.CaptureResult.Ready captured, SaveRequest request) {
        if (!(this.playerDataPipeline.encode(captured) instanceof PlayerDataPipeline.EncodeResult.Ready encoded)) {
            request.fail(new IllegalStateException("critical data of " + context.playerName() + " could not be encoded"));
            return;
        }
        Snapshot snapshot = new Snapshot(context.meta(), mergeData(context.retainedData(), encoded.data()));
        SnapshotSaveEvent event = new SnapshotSaveEvent(context.playerName(), snapshot, request.completion.minimalCompletionStage());
        if (EventUtils.fireAndCheckCancel(event)) {
            this.logger.file(LogCategory.SAVE, context.meta().player(), context.playerName(), LogConstants.SYNC_SAVE_CANCELLED_BY_EVENT, context.playerName(), context.meta().cause().name(), context.meta().id().toString());
            request.cancel();
            return;
        }
        // write 返回时首次存储任务已入队或快照已转交 stash, 最终 settle 继续走 completion
        this.writer.write(snapshot, context.playerName(), captured.captureNanos(), request.completion);
        request.handedOff();
    }

    // 异常同时交给调用方 Future 与执行器的统一异常出口.
    private void submitSerial(UUID player, Runnable task, SaveRequest request) {
        try {
            this.serialExecutor.submit(player, () -> {
                try {
                    task.run();
                } catch (RuntimeException | Error throwable) {
                    request.fail(throwable);
                    throw throwable;
                }
            });
        } catch (RejectedExecutionException exception) {
            request.fail(exception);
        }
    }

    @NotNull
    private SaveContext newContext(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        return new SaveContext(this.newMetadata(player, cause), player.getName(), retainedData);
    }

    private SnapshotMeta newMetadata(Player player, SaveCause cause) {
        return SnapshotMeta.builder()
                .player(player.getUniqueId())
                .timestamp(this.reserveTimestamp(player.getUniqueId()))
                .cause(cause)
                .server(ServerConfig.serverId())
                .mcDataVersion(VersionHelper.WORLD_VERSION)
                .build();
    }

    // 请求接纳时分配逻辑时间戳, 同一玩家在并发调用下仍严格递增.
    private long reserveTimestamp(UUID player) {
        return this.lastTimestampByPlayer.merge(player, System.currentTimeMillis(), (last, now) -> Math.max(now, last + 1));
    }

    // 已关闭或未安装的数据原样保留, 当前采集值覆盖同名旧值.
    @NotNull
    static Map<DataKey, Tag> mergeData(@NotNull Map<DataKey, Tag> retainedData, @NotNull Map<DataKey, Tag> capturedData) {
        if (retainedData.isEmpty()) return capturedData;
        Map<DataKey, Tag> merged = new LinkedHashMap<>(retainedData.size() + capturedData.size());
        merged.putAll(retainedData);
        merged.putAll(capturedData);
        return merged;
    }

    /** 执行器排空超时后, 把尚未 settle 的快照留到本地 pending. */
    public void stashUnsettled() {
        if (this.writer != null) this.writer.stashUnsettled();
    }

    /**
     * 停止接纳新快照并等待已接纳请求完成首次存储提交.
     *
     * @return 是否在限时内完成全部交接
     */
    public boolean sealAndAwaitHandoffs(long timeout, @NotNull TimeUnit unit) {
        return this.handoffs.sealAndAwait(timeout, unit);
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }

    private record SaveContext(@NotNull SnapshotMeta meta, @NotNull String playerName, @NotNull Map<DataKey, Tag> retainedData) {
    }

    private final class SaveRequest {
        private final CompletableFuture<SnapshotSaveResult> completion = new CompletableFuture<>();

        private SaveRequest() {
            SnapshotService.this.handoffs.accept();
        }

        private void handedOff() {
            SnapshotService.this.handoffs.handedOff();
        }

        private void cancel() {
            this.handedOff();
            this.completion.complete(new SnapshotSaveResult.Cancelled());
        }

        private void fail(Throwable throwable) {
            this.handedOff();
            this.completion.completeExceptionally(throwable);
        }
    }
}
