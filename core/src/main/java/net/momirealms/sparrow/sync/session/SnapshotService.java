package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.event.PreApplyEvent;
import net.momirealms.sparrow.sync.event.SnapshotSaveEvent;
import net.momirealms.sparrow.sync.event.SyncCompleteEvent;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.*;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplier;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveOutcome;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.util.EventUtils;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class SnapshotService {
    private final SparrowSync plugin;
    private SyncLogger logger;
    private DataRegistry dataRegistry;
    private SnapshotApplier snapshotApplier;
    private StorageProvider storage;
    private SnapshotStash snapshotStash;
    private PlayerSerialExecutor playerExecutor;

    private final ConcurrentHashMap<UUID, Long> lastSnapshotAt = new ConcurrentHashMap<>();  // 每玩家上次分配的快照时间戳.
    private final ConcurrentHashMap<CompletableFuture<SnapshotSaveOutcome>, SaveAttempt> inflight = new ConcurrentHashMap<>();  // 尚未完成的保存任务, 关服清算用.

    public SnapshotService(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    SnapshotService(@NotNull SparrowSync plugin, @NotNull DataRegistry dataRegistry, @NotNull StorageProvider storage, @NotNull SnapshotStash snapshotStash, @NotNull SyncLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.dataRegistry = dataRegistry;
        this.snapshotApplier = new SnapshotApplier(dataRegistry, logger);
        this.storage = storage;
        this.snapshotStash = snapshotStash;
    }

    /** 绑定快照服务依赖. */
    public void onLoad() {
        this.logger = this.plugin.logger();
        this.dataRegistry = this.plugin.dataRegistry();
        this.snapshotApplier = this.plugin.snapshotApplier();
        this.storage = this.plugin.storageProvider();
        this.snapshotStash = this.plugin.snapshotStash();
        this.playerExecutor = this.plugin.playerExecutor();
    }

    /** 冻结数据类型注册表并编译固定槽位. */
    public void onDelayedEnable() {
        // 冻结注册表并编译拓扑槽位
        this.dataRegistry.freeze();
        // 记录实际运行时的数据源
        StringJoiner activeTypes = new StringJoiner(", ");
        List<DataKey> applyOrder = this.snapshotApplier.applyOrder();
        int dataTypeCount = applyOrder.size();
        for (int i = 0; i < dataTypeCount; i++) {
            activeTypes.add(applyOrder.get(i).asString());
        }
        this.logger.info(TranslationManager.console(LogConstants.PLUGIN_REGISTRY_FROZEN, String.valueOf(dataTypeCount), activeTypes.toString()));
    }

    /**
     * 读取玩家最新的快照并在调用线程上预解码.
     * 任意线程可调用, 关键数据解不开则整份不应用.
     */
    @NotNull
    public CompletableFuture<PreparedOutcome> loadAndPrepare(@NotNull UUID player, @NotNull String playerName) {
        long loadStart = System.nanoTime();
        return this.storage.latestSnapshot(player)
                .thenApply(latest -> {
                    // 没有历史的新玩家, 本服状态即权威
                    return latest.map(snapshot -> this.prepare(snapshot, player, playerName, loadStart))
                            .orElseGet(() -> {
                                this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_EMPTY, playerName);
                                return new PreparedOutcome.Empty();
                            });
                })
                .whenComplete((outcome, throwable) -> {
                    if (throwable != null)
                        this.logger.error(LogCategory.APPLY, player, playerName, throwable, LogConstants.SYNC_LOAD_FAILED, playerName, String.valueOf(throwable));
                });
    }

    // 预解码一份快照并记录结果
    private PreparedOutcome prepare(Snapshot snapshot, UUID player, String playerName, long loadStart) {
        return switch (this.snapshotApplier.prepare(snapshot)) {
            case SnapshotApplier.PreparedSnapshot.Ready ready -> {
                long asyncNanos = System.nanoTime() - loadStart;
                this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_READY, playerName, snapshot.meta().id().toString(), millis(0, asyncNanos));
                yield new PreparedOutcome.Ready(snapshot, ready, asyncNanos);
            }
            case SnapshotApplier.PreparedSnapshot.Failed failed -> {
                String detail = failed.key().asString() + ": " + failed.detail();
                this.logger.error(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_FAILED, playerName, detail);
                yield new PreparedOutcome.Failed(detail);
            }
        };
    }

    /**
     * 把预解码结果应用到玩家. <strong>必须在玩家线程上调用</strong>.
     */
    @NotNull
    public LoadOutcome applyPrepared(@NotNull Player player, @NotNull PreparedOutcome.Ready ready) {
        long applyStart = System.nanoTime();
        this.logger.file(LogCategory.APPLY, player.getUniqueId(), player.getName(), LogConstants.SYNC_APPLY_STARTED, player.getName());
        PreApplyEvent preApplyEvent = new PreApplyEvent(player, ready.snapshot(), ready.prepared().values());
        EventUtils.fireAndForget(preApplyEvent);
        SnapshotApplier.PreparedSnapshot.Ready prepared = this.snapshotApplier.afterEvent(preApplyEvent.decoded(), ready.prepared());
        return switch (this.snapshotApplier.apply(player, prepared)) {
            case SnapshotApplier.ApplyResult.Success success -> {
                PlayerSession session = this.plugin.sessionManager().session(player.getUniqueId());
                if (session != null) {
                    session.passthroughData(prepared.passthrough()); // 缓存无法解析的数据类型到 session
                }
                this.logger.info(LogCategory.APPLY, player.getUniqueId(), player.getName(),
                        LogConstants.SYNC_APPLIED,
                        player.getName(),
                        String.valueOf(success.applied().size()),
                        String.valueOf(success.skipped().size()),
                        millis(0, ready.asyncNanos()),
                        millis(applyStart, System.nanoTime())
                );
                EventUtils.fireAndForget(new SyncCompleteEvent(player, ready.snapshot(), success.applied(), success.skipped()));
                yield new LoadOutcome.Applied(success.applied().size(), success.skipped().size());
            }
            case SnapshotApplier.ApplyResult.Failure failure -> new LoadOutcome.Failed(failure.failedKey().asString() + ": " + failure.detail());
        };
    }

    /**
     * {@link #loadAndPrepare} 与 {@link #applyPrepared} 的组合便捷,
     * 应用段自动回到玩家的拥有线程, 调度前玩家已离开时以 {@link LoadOutcome.Gone} 完成.
     * 供命令和快照恢复等即时应用场景使用.
     */
    @NotNull
    public CompletableFuture<LoadOutcome> loadAndApply(@NotNull Player player) {
        return this.loadAndPrepare(player.getUniqueId(), player.getName()).thenCompose(outcome -> switch (outcome) {
            case PreparedOutcome.Empty ignored -> CompletableFuture.completedFuture(new LoadOutcome.Empty());
            case PreparedOutcome.Failed failed -> CompletableFuture.completedFuture(new LoadOutcome.Failed(failed.detail()));
            case PreparedOutcome.Ready ready -> {
                CompletableFuture<LoadOutcome> applied = new CompletableFuture<>();
                player.getScheduler().run(this.plugin.javaPlugin(), task -> {
                    try {
                        applied.complete(this.applyPrepared(player, ready));
                    } catch (Throwable throwable) {
                        applied.completeExceptionally(throwable);
                    }
                }, () -> applied.complete(new LoadOutcome.Gone()));
                yield applied;
            }
        });
    }

    /**
     * 在调用线程立即采集玩家状态, 再把编码和保存阶段提交到玩家串行线程.
     * <strong>调用线程必须允许读取玩家状态</strong>.
     */
    @NotNull
    public CompletableFuture<SnapshotSaveOutcome> captureAndSubmit(@NotNull Player player, @NotNull SaveCause cause) {
        return this.captureAndSubmitAccepted(player, cause);
    }

    /**
     * 把玩家采集、编码和保存阶段一起提交到玩家串行线程.
     */
    @NotNull
    public CompletableFuture<SnapshotSaveOutcome> submitDeferredCapture(@NotNull Player player, @NotNull SaveCause cause) {
        return this.submitDeferredCaptureAccepted(player, cause);
    }

    // SessionManager 已完成接纳检查, 这里立即取得目标状态并提交后续阶段
    @NotNull
    CompletableFuture<SnapshotSaveOutcome> captureAndSubmitAccepted(@NotNull Player player, @NotNull SaveCause cause) {
        SaveRequest request = this.requestOf(player, cause);
        if (!(this.snapshotApplier.capture(player) instanceof SnapshotApplier.CaptureResult.Ready captured)) {
            return CompletableFuture.failedFuture(new IllegalStateException("critical data of " + player.getName() + " could not be captured"));
        }
        CompletableFuture<SnapshotSaveOutcome> outcome = new CompletableFuture<>();
        this.enqueue(request.meta().player(), () -> this.encodeAndSave(request, captured, outcome), outcome);
        return outcome;
    }

    // SessionManager 已完成接纳检查, capture 与后续阶段在同一个玩家任务内执行
    @NotNull
    CompletableFuture<SnapshotSaveOutcome> submitDeferredCaptureAccepted(@NotNull Player player, @NotNull SaveCause cause) {
        SaveRequest request = this.requestOf(player, cause);
        CompletableFuture<SnapshotSaveOutcome> outcome = new CompletableFuture<>();
        this.enqueue(request.meta().player(), () -> {
            if (!(this.snapshotApplier.capture(player) instanceof SnapshotApplier.CaptureResult.Ready captured)) {
                outcome.completeExceptionally(new IllegalStateException("critical data of " + request.playerName() + " could not be captured"));
                return;
            }
            this.encodeAndSave(request, captured, outcome);
        }, outcome);
        return outcome;
    }

    // 保存任务到这里已经持有脱离 Player 的采集值
    private void encodeAndSave(SaveRequest request, SnapshotApplier.CaptureResult.Ready captured, CompletableFuture<SnapshotSaveOutcome> outcome) {
        if (!(this.snapshotApplier.encode(captured) instanceof SnapshotApplier.EncodeResult.Ready encoded)) {
            outcome.completeExceptionally(new IllegalStateException("critical data of " + request.playerName() + " could not be encoded"));
            return;
        }
        Snapshot snapshot = new Snapshot(request.meta(), mergeCapturedData(request.passthrough(), encoded.data()));
        SnapshotSaveEvent event = new SnapshotSaveEvent(request.playerName(), snapshot, outcome.minimalCompletionStage());
        try {
            if (EventUtils.fireAndCheckCancel(event)) {
                this.logger.file(LogCategory.SAVE, request.meta().player(), request.playerName(), LogConstants.SYNC_SAVE_CANCELLED_BY_EVENT, request.playerName(), request.meta().cause().name(), request.meta().id().toString());
                outcome.complete(new SnapshotSaveOutcome.Cancelled());
                return;
            }
        } catch (RuntimeException | Error throwable) {
            outcome.completeExceptionally(throwable);
            throw throwable;
        }

        try {
            this.logger.file(LogCategory.SAVE, request.meta().player(), request.playerName(), LogConstants.SYNC_SAVE_STARTED, request.playerName(), request.meta().cause().name(), request.meta().id().toString());
            SaveAttempt attempt = SaveAttempt.first(snapshot, request.playerName(), PluginConfig.synchronization$maxSaveRetries(), request.requestStart());
            this.inflight.put(outcome, attempt);
            outcome.whenComplete((result, throwable) -> this.inflight.remove(outcome));
            this.submitSave(attempt, outcome);
        } catch (RuntimeException | Error throwable) {
            outcome.completeExceptionally(throwable);
            throw throwable;
        }
    }

    // 同一玩家的保存阶段只由 PlayerSerialExecutor 执行, 异常同时交给 Future 与执行器边界
    private void enqueue(UUID player, Runnable task, CompletableFuture<SnapshotSaveOutcome> outcome) {
        try {
            this.playerExecutor.submit(player, () -> {
                try {
                    task.run();
                } catch (RuntimeException | Error throwable) {
                    outcome.completeExceptionally(throwable);
                    throw throwable;
                }
            });
        } catch (RejectedExecutionException exception) {
            outcome.completeExceptionally(exception);
        }
    }

    @NotNull
    private SaveRequest requestOf(@NotNull Player player, @NotNull SaveCause cause) {
        long requestStart = System.nanoTime();
        PlayerSession session = this.plugin.sessionManager().session(player.getUniqueId());
        Map<DataKey, Tag> passthrough = session == null ? Map.of() : session.passthroughData();
        return new SaveRequest(this.metaOf(player, cause), player.getName(), passthrough, requestStart);
    }

    // 将未加载的和采集的 Data 进行合并, 当前已启用类型的采集值优先, 同名旧值不盖回玩家刚产生的新状态.
    @NotNull
    static Map<DataKey, Tag> mergeCapturedData(@NotNull Map<DataKey, Tag> passthrough, @NotNull Map<DataKey, Tag> captured) {
        if (passthrough.isEmpty()) return captured;
        Map<DataKey, Tag> merged = new LinkedHashMap<>(passthrough.size() + captured.size());
        merged.putAll(passthrough);
        merged.putAll(captured);
        return merged;
    }

    // 提交落库请求, 落库失败且可重试时把同一份快照排到该玩家队列的队尾, 直到写进去, 用完重试次数, 或者遇上重试解决不了的失败.
    private void submitSave(SaveAttempt attempt, CompletableFuture<SnapshotSaveOutcome> outcome) {
        long submitAt = System.nanoTime();
        CompletableFuture<SaveOutcome> save;
        try {
            save = this.storage.saveSnapshotOutcome(attempt.snapshot());
        } catch (RejectedExecutionException exception) {
            // 关服排空期间执行器拒收新任务, 这份快照留给本地备份
            this.abandon(attempt, SaveResult.RETRY_LATER, null, outcome);
            return;
        }
        save.whenComplete((saved, throwable) -> {
            // 保存失败
            if (throwable != null) {
                this.logger.error(LogCategory.SAVE, attempt.player(), attempt.playerName(), throwable, LogConstants.SYNC_SAVE_FAILED, attempt.playerName());
                outcome.completeExceptionally(throwable);
                return;
            }
            SaveResult result = saved.result();
            // 已保存, 记录日志并轮转快照
            if (result.stored()) {
                this.logger.info(LogCategory.SAVE, attempt.player(), attempt.playerName(), LogConstants.SYNC_SAVED, attempt.playerName(), attempt.cause(), result.name(), millis(attempt.requestStart(), submitAt), millis(submitAt, System.nanoTime()));
                this.rotate(attempt.snapshot().meta().player(), attempt.playerName());
                outcome.complete(new SnapshotSaveOutcome.Completed(result));
                return;
            }
            // 可重试
            if (result.retriable()) {
                logRetry(this.logger, attempt, saved.failure());
                if (attempt.retryAllowed()) {
                    this.scheduleRetry(attempt.next(), outcome);
                    return;
                }
            }
            this.abandon(attempt, result, saved.failure(), outcome);
        });
    }

    static void logRetry(@NotNull SyncLogger logger, @NotNull SaveAttempt attempt, @Nullable Throwable failure) {
        if (!attempt.retryAllowed() || !attempt.worthLogging()) return;
        if (attempt.number() == 1 && failure != null) {
            logger.warnWithFileCause(LogCategory.RETRY, attempt.player(), attempt.playerName(), failure, LogConstants.SYNC_SAVE_PENDING_RETRY, attempt.playerName(), String.valueOf(attempt.number()));
            return;
        }
        logger.warn(LogCategory.RETRY, attempt.player(), attempt.playerName(), LogConstants.SYNC_SAVE_PENDING_RETRY, attempt.playerName(), String.valueOf(attempt.number()));
    }

    static void logFinalFailure(@NotNull SyncLogger logger, @NotNull SaveAttempt attempt, @NotNull SaveResult result, @Nullable Throwable failure) {
        String key = result.retriable() ? LogConstants.SYNC_SAVE_RETRIES_EXHAUSTED : LogConstants.SYNC_SAVE_NEEDS_ATTENTION;
        if (failure != null) {
            logger.errorWithFileCause(LogCategory.RETRY, attempt.player(), attempt.playerName(), failure, key, attempt.playerName(), attempt.cause(), String.valueOf(attempt.number()));
            return;
        }
        logger.error(LogCategory.RETRY, attempt.player(), attempt.playerName(), key, attempt.playerName(), attempt.cause(), String.valueOf(attempt.number()));
    }

    // 冷却交给执行器, 任务带着就绪时刻排在队尾, 到点之前不占线程, 同桶的其他玩家照常推进
    private void scheduleRetry(SaveAttempt attempt, CompletableFuture<SnapshotSaveOutcome> outcome) {
        try {
            this.plugin.playerExecutor().submitDelayed(attempt.snapshot().meta().player(),
                    () -> this.submitSave(attempt, outcome), attempt.cooldownMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            // 关服排空期间执行器拒收新任务, 这份快照留给本地备份
            this.abandon(attempt, SaveResult.RETRY_LATER, null, outcome);
        }
    }

    // 重试到此为止, 快照落盘本地, 可重试的进 pending 下次启动插回, 其余进 exception 等管理员处置
    private void abandon(SaveAttempt attempt, SaveResult result, @Nullable Throwable failure, CompletableFuture<SnapshotSaveOutcome> outcome) {
        logFinalFailure(this.logger, attempt, result, failure);
        this.snapshotStash.stash(attempt.snapshot(), attempt.playerName(), result);
        outcome.complete(new SnapshotSaveOutcome.Completed(result));
    }

    // 轮转快照
    private void rotate(UUID player, String playerName) {
        try {
            this.storage.rotate(player, PluginConfig.synchronization$maxSnapshots()).whenComplete((deleted, throwable) -> {
                if (throwable != null) {
                    this.logger.file(LogCategory.STORAGE, player, playerName, throwable, LogConstants.SYNC_ROTATE_FAILED, playerName);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    // 构建 SnapshotMeta.
    private SnapshotMeta metaOf(Player player, SaveCause cause) {
        return SnapshotMeta.builder()
                .player(player.getUniqueId())
                .timestamp(this.nextTimestamp(player.getUniqueId()))
                .cause(cause)
                .server(ServerConfig.serverId())
                .mcDataVersion(VersionHelper.WORLD_VERSION)
                .build();
    }

    // 保存请求被接纳时分配逻辑时间戳, 并发调用通过 merge 保持同玩家严格递增
    private long nextTimestamp(UUID player) {
        return this.lastSnapshotAt.merge(player, System.currentTimeMillis(), (last, now) -> Math.max(now, last + 1));
    }

    /**
     * 执行器排空超时后调用, 把没等到 settle 的保存落盘到 pending.
     */
    public void stashUnsettled() {
        for (var entry : this.inflight.entrySet()) {
            SaveAttempt attempt = entry.getValue();
            // complete 的原子性保证与 worker 尾段收工的那份不落两次盘.
            if (entry.getKey().complete(new SnapshotSaveOutcome.Completed(SaveResult.RETRY_LATER))) {
                this.snapshotStash.stash(attempt.snapshot(), attempt.playerName(), SaveResult.RETRY_LATER);
            }
        }
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }

    /**
     * 一次落库尝试.
     *
     * @param number     第几次尝试, 从 1 开始
     * @param maxRetries 首次失败后还能重排队尾几次, -1 表示一直重试到数据库回来
     */
    record SaveAttempt(@NotNull Snapshot snapshot, @NotNull String playerName, int number, int maxRetries, long requestStart) {
        private static final int FREE_ATTEMPTS = 5;     // 前几次不等, 抖动和主从切换通常几十毫秒就过去了
        private static final long COOLDOWN_STEP_MILLIS = 100;
        private static final long MAX_COOLDOWN_MILLIS = 1000;
        private static final int LOG_INTERVAL = 10;

        @NotNull
        static SaveAttempt first(@NotNull Snapshot snapshot, @NotNull String playerName, int maxRetries, long requestStart) {
            return new SaveAttempt(snapshot, playerName, 1, maxRetries, requestStart);
        }

        @NotNull
        String cause() {
            return this.snapshot.meta().cause().name();
        }

        @NotNull
        UUID player() {
            return this.snapshot.meta().player();
        }

        boolean retryAllowed() {
            return this.maxRetries < 0 || this.number <= this.maxRetries;
        }

        /**
         * 本次尝试前要等多久.
         * 前 {@value FREE_ATTEMPTS} 次不等, 之后每次多等 100 毫秒, 封顶 1 秒.
         */
        long cooldownMillis() {
            if (this.number <= FREE_ATTEMPTS) return 0;
            return Math.min((this.number - FREE_ATTEMPTS) * COOLDOWN_STEP_MILLIS, MAX_COOLDOWN_MILLIS);
        }

        // 重试速率被冷却封顶, 日志按次数收敛就等于按时间收敛
        boolean worthLogging() {
            return this.number == 1 || this.number % LOG_INTERVAL == 0;
        }

        @NotNull
        SaveAttempt next() {
            return new SaveAttempt(this.snapshot, this.playerName, this.number + 1, this.maxRetries, this.requestStart);
        }
    }

    private record SaveRequest(@NotNull SnapshotMeta meta, @NotNull String playerName, @NotNull Map<DataKey, Tag> passthrough, long requestStart) {
    }

    /** 一次读取预解码的结果, 配置阶段产出, 应用段消费. */
    public sealed interface PreparedOutcome {

        /** 预解码完成, 携带待应用的数据. */
        record Ready(@NotNull Snapshot snapshot, @NotNull SnapshotApplier.PreparedSnapshot.Ready prepared, long asyncNanos) implements PreparedOutcome {
        }

        /** 玩家没有历史快照, 本服状态即权威. */
        record Empty() implements PreparedOutcome {
        }

        /** 读库失败或关键数据解码失败, 不应放行. */
        record Failed(@NotNull String detail) implements PreparedOutcome {
        }
    }

    /** 一次读取应用的结果. */
    public sealed interface LoadOutcome {

        /** 应用完成, 携带应用与跳过的类型数. */
        record Applied(int applied, int skipped) implements LoadOutcome {
        }

        /** 玩家没有历史快照. */
        record Empty() implements LoadOutcome {
        }

        /** 应用段调度前玩家已离开. */
        record Gone() implements LoadOutcome {
        }

        /** 关键数据解码或应用失败, 玩家状态未同步. */
        record Failed(@NotNull String detail) implements LoadOutcome {
        }
    }

    /** 一次采集保存的最终结果. */
    public sealed interface SnapshotSaveOutcome {

        /** 保存链已经结束, 携带存储层给出的结果. */
        record Completed(@NotNull SaveResult result) implements SnapshotSaveOutcome {
        }

        /** 快照保存被事件监听器取消. */
        record Cancelled() implements SnapshotSaveOutcome {
        }

    }
}
