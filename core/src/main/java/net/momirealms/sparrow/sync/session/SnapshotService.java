package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.configuration.ServerConfig;
import net.momirealms.sparrow.sync.data.SnapshotApplier;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveOutcome;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class SnapshotService {
    private final SparrowSync plugin;
    private final SyncLogger logger;
    private final SnapshotApplier applier;
    private final StorageProvider storage;
    private final SnapshotStash stash;
    private final ConcurrentHashMap<UUID, Long> lastCaptureAt = new ConcurrentHashMap<>();  // 每玩家上次分配的采集时间戳.
    private final ConcurrentHashMap<CompletableFuture<SaveResult>, SaveAttempt> inflight = new ConcurrentHashMap<>();  // 尚未 settle 的保存, 关服清算用.

    public SnapshotService(@NotNull SparrowSync plugin, @NotNull SnapshotApplier applier, @NotNull StorageProvider storage, @NotNull SnapshotStash stash, @NotNull SyncLogger logger) {
        this.plugin = plugin;
        this.applier = applier;
        this.storage = storage;
        this.stash = stash;
        this.logger = logger;
    }

    /**
     * 读取玩家最新的快照并在调用线程上预解码.
     * 任意线程可调用, 关键数据解不开则整份不应用.
     */
    @NotNull
    public CompletableFuture<PreparedOutcome> loadAndPrepare(@NotNull UUID player, @NotNull String playerName) {
        long loadStart = System.nanoTime();
        return this.storage.latestSnapshot(player).<PreparedOutcome>thenApply(latest -> {
            // 没有历史的新玩家, 本服状态即权威
            return latest.<PreparedOutcome>map(snapshot ->
                    switch (this.applier.prepare(snapshot)) {
                        case SnapshotApplier.PreparedSnapshot.Ready ready -> {
                            long asyncNanos = System.nanoTime() - loadStart;
                            this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_READY, playerName, snapshot.meta().id().toString(), millis(0, asyncNanos));
                            yield new PreparedOutcome.Ready(ready, asyncNanos);
                        }
                        case SnapshotApplier.PreparedSnapshot.Failed failed -> {
                            String detail = failed.key().asString() + ": " + failed.detail();
                            this.logger.error(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_FAILED, playerName, detail);
                            yield new PreparedOutcome.Failed(detail);
                        }
                    }
            ).orElseGet(() -> {
                this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_EMPTY, playerName);
                return new PreparedOutcome.Empty();
            });
        }).whenComplete((outcome, throwable) -> {
            if (throwable != null) this.logger.error(LogCategory.APPLY, player, playerName, throwable, LogConstants.SYNC_LOAD_FAILED, playerName, String.valueOf(throwable));
        });
    }

    /**
     * 把预解码结果应用到玩家. <strong>必须在玩家线程上调用</strong>.
     */
    @NotNull
    public LoadOutcome applyPrepared(@NotNull Player player, @NotNull SnapshotApplier.PreparedSnapshot.Ready ready, long asyncNanos) {
        long applyStart = System.nanoTime();
        this.logger.file(LogCategory.APPLY, player.getUniqueId(), player.getName(), LogConstants.SYNC_APPLY_STARTED, player.getName());
        return switch (this.applier.apply(player, ready)) {
            case SnapshotApplier.ApplyResult.Success success -> {
                this.logger.info(LogCategory.APPLY, player.getUniqueId(), player.getName(),
                        LogConstants.SYNC_APPLIED,
                        player.getName(),
                        String.valueOf(success.applied().size()),
                        String.valueOf(success.skipped().size()),
                        millis(0, asyncNanos),
                        millis(applyStart, System.nanoTime())
                );
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
                        applied.complete(this.applyPrepared(player, ready.prepared(), ready.asyncNanos()));
                    } catch (Throwable throwable) {
                        applied.completeExceptionally(throwable);
                    }
                }, () -> applied.complete(new LoadOutcome.Gone()));
                yield applied;
            }
        });
    }

    /**
     * 采集玩家当前状态并投递落库, <strong>必须在玩家线程上调用</strong>.
     */
    @NotNull
    public CompletableFuture<SaveResult> captureAndSave(@NotNull Player player, @NotNull SaveCause cause) {
        long captureStart = System.nanoTime();
        // 关键数据采集不出来时不产出快照.
        if (!(this.applier.capture(player) instanceof SnapshotApplier.CaptureResult.Ready ready)) {
            return CompletableFuture.failedFuture(new IllegalStateException("critical data of " + player.getName() + " could not be captured"));
        }
        Snapshot snapshot = new Snapshot(this.metaOf(player, cause), ready.data());
        this.logger.file(LogCategory.SAVE, player.getUniqueId(), player.getName(), LogConstants.SYNC_SAVE_STARTED, player.getName(), cause.name(), snapshot.meta().id().toString());
        CompletableFuture<SaveResult> outcome = new CompletableFuture<>();
        SaveAttempt attempt = SaveAttempt.first(snapshot, player.getName(), PluginConfig.synchronization$maxSaveRetries(), captureStart);
        this.inflight.put(outcome, attempt);
        outcome.whenComplete((result, throwable) -> this.inflight.remove(outcome));
        this.submitSave(attempt, outcome);
        return outcome;
    }

    // 提交落库请求, 落库失败且可重试时把同一份快照排到该玩家队列的队尾, 直到写进去, 用完重试次数, 或者遇上重试解决不了的失败.
    private void submitSave(SaveAttempt attempt, CompletableFuture<SaveResult> outcome) {
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
            // 已保存, 轮转快照
            if (result.stored()) {
                this.logger.info(LogCategory.SAVE, attempt.player(), attempt.playerName(), LogConstants.SYNC_SAVED, attempt.playerName(), attempt.cause(), result.name(), millis(attempt.captureStart(), submitAt), millis(submitAt, System.nanoTime()));
                this.rotate(attempt.snapshot().meta().player(), attempt.playerName());
                outcome.complete(result);
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
    private void scheduleRetry(SaveAttempt attempt, CompletableFuture<SaveResult> outcome) {
        try {
            this.plugin.playerExecutor().submitDelayed(attempt.snapshot().meta().player(),
                    () -> this.submitSave(attempt, outcome), attempt.cooldownMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            // 关服排空期间执行器拒收新任务, 这份快照留给本地备份
            this.abandon(attempt, SaveResult.RETRY_LATER, null, outcome);
        }
    }

    // 重试到此为止, 快照落盘本地, 可重试的进 pending 下次启动插回, 其余进 exception 等管理员处置
    private void abandon(SaveAttempt attempt, SaveResult result, @Nullable Throwable failure, CompletableFuture<SaveResult> outcome) {
        logFinalFailure(this.logger, attempt, result, failure);
        this.stash.stash(attempt.snapshot(), attempt.playerName(), result);
        outcome.complete(result);
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

    // 分配采集时间戳.
    private long nextTimestamp(UUID player) {
        return this.lastCaptureAt.merge(player, System.currentTimeMillis(), (last, now) -> Math.max(now, last + 1));
    }

    /**
     * 执行器排空超时后调用, 把没等到 settle 的保存落盘到 pending.
     */
    public void stashUnsettled() {
        for (var entry : this.inflight.entrySet()) {
            SaveAttempt attempt = entry.getValue();
            // complete 的原子性保证与 worker 尾段收工的那份不落两次盘.
            if (entry.getKey().complete(SaveResult.RETRY_LATER)) {
                this.stash.stash(attempt.snapshot(), attempt.playerName(), SaveResult.RETRY_LATER);
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
    record SaveAttempt(@NotNull Snapshot snapshot, @NotNull String playerName, int number, int maxRetries, long captureStart) {
        private static final int FREE_ATTEMPTS = 5;     // 前几次不等, 抖动和主从切换通常几十毫秒就过去了
        private static final long COOLDOWN_STEP_MILLIS = 100;
        private static final long MAX_COOLDOWN_MILLIS = 1000;
        private static final int LOG_INTERVAL = 10;

        @NotNull
        static SaveAttempt first(@NotNull Snapshot snapshot, @NotNull String playerName, int maxRetries, long captureStart) {
            return new SaveAttempt(snapshot, playerName, 1, maxRetries, captureStart);
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
            return new SaveAttempt(this.snapshot, this.playerName, this.number + 1, this.maxRetries, this.captureStart);
        }
    }

    /** 一次读取预解码的结果, 配置阶段产出, 应用段消费. */
    public sealed interface PreparedOutcome {

        /** 预解码完成, 携带待应用的数据. */
        record Ready(@NotNull SnapshotApplier.PreparedSnapshot.Ready prepared, long asyncNanos) implements PreparedOutcome {
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
}
