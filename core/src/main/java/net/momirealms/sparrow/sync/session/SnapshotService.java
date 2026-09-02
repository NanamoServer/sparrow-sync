package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.event.PreApplyEvent;
import net.momirealms.sparrow.sync.event.SnapshotSaveEvent;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplier;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.util.EventUtils;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/** 快照的读取、应用、采集和编码流水线. */
public final class SnapshotService {
    private final SparrowSync plugin;
    private SyncLogger logger;
    private DataRegistry dataRegistry;
    private SnapshotApplier playerData;
    private PlayerSerialExecutor serialExecutor;
    private StorageProvider storage;
    private SnapshotWriter writer;
    private final ConcurrentHashMap<UUID, Long> lastTimestampByPlayer = new ConcurrentHashMap<>();

    public SnapshotService(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
        this.logger = this.plugin.logger();
        this.dataRegistry = this.plugin.dataRegistry();
        this.playerData = this.plugin.snapshotApplier();
        this.serialExecutor = this.plugin.playerExecutor();
        this.storage = this.plugin.storageProvider();
        this.writer = new SnapshotWriter(this.logger, this.storage, this.plugin.snapshotStash(), this.serialExecutor);
    }

    public void onDelayedEnable() {
        // 冻结数据类型注册表并记录最终装配顺序.
        this.dataRegistry.freeze();
        StringJoiner activeTypes = new StringJoiner(", ");
        List<DataKey> applyOrder = this.playerData.applyOrder();
        int dataTypeCount = applyOrder.size();
        for (int i = 0; i < dataTypeCount; i++) {
            activeTypes.add(applyOrder.get(i).asString());
        }
        this.logger.info(TranslationManager.console(LogConstants.PLUGIN_REGISTRY_FROZEN, String.valueOf(dataTypeCount), activeTypes.toString()));
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
                            this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_EMPTY, playerName);
                            return new SnapshotLoadResult.Empty();
                        }))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        this.logger.error(LogCategory.APPLY, player, playerName, throwable, LogConstants.SYNC_LOAD_FAILED, playerName, String.valueOf(throwable));
                    }
                });
    }

    private SnapshotLoadResult prepare(Snapshot snapshot, UUID player, String playerName, long loadStart) {
        return switch (this.playerData.prepare(snapshot)) {
            case SnapshotApplier.PreparedSnapshot.Ready ready -> {
                long loadNanos = System.nanoTime() - loadStart;
                this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_READY, playerName, snapshot.meta().id().toString(), millis(0, loadNanos));
                yield new SnapshotLoadResult.Ready(snapshot, ready, loadNanos);
            }
            case SnapshotApplier.PreparedSnapshot.Failed failed -> {
                String detail = failed.key().asString() + ": " + failed.detail();
                this.logger.error(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_FAILED, playerName, detail);
                yield new SnapshotLoadResult.Failed(detail);
            }
        };
    }

    /** 把预解码数据应用到玩家. <strong>必须在玩家的拥有线程上调用</strong>. */
    @NotNull
    SnapshotApplyResult apply(@NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
        long applyStart = System.nanoTime();
        this.logger.file(LogCategory.APPLY, player.getUniqueId(), player.getName(), LogConstants.SYNC_APPLY_STARTED, player.getName());
        PreApplyEvent event = new PreApplyEvent(player, loaded.snapshot(), loaded.data().values());
        EventUtils.fireAndForget(event);
        SnapshotApplier.PreparedSnapshot.Ready data = this.playerData.afterEvent(event.decoded(), loaded.data());
        return switch (this.playerData.apply(player, data)) {
            case SnapshotApplier.ApplyResult.Success success -> {
                this.logger.info(LogCategory.APPLY, player.getUniqueId(), player.getName(),
                        LogConstants.SYNC_APPLIED,
                        player.getName(),
                        String.valueOf(success.applied().size()),
                        String.valueOf(success.skipped().size()),
                        millis(0, loaded.loadNanos()),
                        millis(applyStart, System.nanoTime())
                );
                yield new SnapshotApplyResult.Applied(success.applied(), success.skipped());
            }
            case SnapshotApplier.ApplyResult.Failure failure -> new SnapshotApplyResult.Failed(failure.failedKey().asString() + ": " + failure.detail());
        };
    }

    /**
     * 在当前线程立即采集玩家状态, 编码和保存阶段进入玩家串行线程.
     * 调用方负责保证当前线程允许读取玩家状态.
     */
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureNowAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        SaveContext context = this.newContext(player, cause, retainedData);
        if (!(this.playerData.capture(player) instanceof SnapshotApplier.CaptureResult.Ready captured)) {
            return CompletableFuture.failedFuture(new IllegalStateException("critical data of " + player.getName() + " could not be captured"));
        }
        CompletableFuture<SnapshotSaveResult> completion = new CompletableFuture<>();
        this.submitSerial(context.meta().player(), () -> this.encodeAndSubmit(context, captured, completion), completion);
        return completion;
    }

    /**
     * 把采集、编码和保存阶段一起提交到玩家串行异步线程.
     */
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureLaterAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        SaveContext context = this.newContext(player, cause, retainedData);
        CompletableFuture<SnapshotSaveResult> completion = new CompletableFuture<>();
        this.submitSerial(context.meta().player(), () -> {
            if (!(this.playerData.capture(player) instanceof SnapshotApplier.CaptureResult.Ready captured)) {
                completion.completeExceptionally(new IllegalStateException("critical data of " + context.playerName() + " could not be captured"));
                return;
            }
            this.encodeAndSubmit(context, captured, completion);
        }, completion);
        return completion;
    }

    // 进入这里时已经只持有脱离 Player 的采集值.
    private void encodeAndSubmit(SaveContext context, SnapshotApplier.CaptureResult.Ready captured, CompletableFuture<SnapshotSaveResult> completion) {
        if (!(this.playerData.encode(captured) instanceof SnapshotApplier.EncodeResult.Ready encoded)) {
            completion.completeExceptionally(new IllegalStateException("critical data of " + context.playerName() + " could not be encoded"));
            return;
        }
        Snapshot snapshot = new Snapshot(context.meta(), mergeData(context.retainedData(), encoded.data()));
        SnapshotSaveEvent event = new SnapshotSaveEvent(context.playerName(), snapshot, completion.minimalCompletionStage());
        if (EventUtils.fireAndCheckCancel(event)) {
            this.logger.file(LogCategory.SAVE, context.meta().player(), context.playerName(), LogConstants.SYNC_SAVE_CANCELLED_BY_EVENT, context.playerName(), context.meta().cause().name(), context.meta().id().toString());
            completion.complete(new SnapshotSaveResult.Cancelled());
            return;
        }
        this.writer.write(snapshot, context.playerName(), context.acceptedAtNanos(), completion);
    }

    // 异常同时交给调用方 Future 与执行器的统一异常出口.
    private void submitSerial(UUID player, Runnable task, CompletableFuture<SnapshotSaveResult> completion) {
        try {
            this.serialExecutor.submit(player, () -> {
                try {
                    task.run();
                } catch (RuntimeException | Error throwable) {
                    completion.completeExceptionally(throwable);
                    throw throwable;
                }
            });
        } catch (RejectedExecutionException exception) {
            completion.completeExceptionally(exception);
        }
    }

    @NotNull
    private SaveContext newContext(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        long acceptedAtNanos = System.nanoTime();
        return new SaveContext(this.newMetadata(player, cause), player.getName(), retainedData, acceptedAtNanos);
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
        this.writer.stashUnsettled();
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }

    private record SaveContext(@NotNull SnapshotMeta meta, @NotNull String playerName, @NotNull Map<DataKey, Tag> retainedData, long acceptedAtNanos) {
    }
}
