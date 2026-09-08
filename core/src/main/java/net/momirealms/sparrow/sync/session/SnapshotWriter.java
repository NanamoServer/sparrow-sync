package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.session.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveOutcome;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** 负责快照落库、重试、轮转和关服留存. */
final class SnapshotWriter {
    private final SyncLogger logger;
    private final StorageProvider storage;
    private final SnapshotStash stash;
    private final PlayerSerialExecutor serialExecutor;
    private final ConcurrentHashMap<CompletableFuture<SnapshotSaveResult>, WriteAttempt> unsettledWrites = new ConcurrentHashMap<>();

    SnapshotWriter(@NotNull SyncLogger logger, @NotNull StorageProvider storage, @NotNull SnapshotStash stash, @NotNull PlayerSerialExecutor serialExecutor) {
        this.logger = logger;
        this.storage = storage;
        this.stash = stash;
        this.serialExecutor = serialExecutor;
    }

    /** 开始写入已经编码完成的快照, 并收敛到调用方提供的完成结果. */
    void write(@NotNull Snapshot snapshot, @NotNull String playerName, long captureNanos, @NotNull CompletableFuture<SnapshotSaveResult> completion) {
        this.logger.file(LogCategory.SAVE, snapshot.meta().player(), playerName, LogConstants.SYNC_SAVE_STARTED, playerName, snapshot.meta().cause().name(), snapshot.meta().id().toString());
        WriteAttempt attempt = WriteAttempt.first(snapshot, playerName, PluginConfig.synchronization$maxSaveRetries(), captureNanos);
        this.unsettledWrites.put(completion, attempt);
        completion.whenComplete((result, throwable) -> this.unsettledWrites.remove(completion));
        this.write(attempt, completion);
    }

    // 失败且允许重试时, 同一份快照重新排到玩家队尾; 不占住玩家 lane 等待数据库恢复.
    private void write(WriteAttempt attempt, CompletableFuture<SnapshotSaveResult> completion) {
        long submittedAt = System.nanoTime();
        CompletableFuture<SaveOutcome> save;
        try {
            save = this.storage.saveSnapshotOutcome(attempt.snapshot());
        } catch (RejectedExecutionException exception) {
            this.stashFailed(attempt, SaveResult.RETRY_LATER, null, completion);
            return;
        }
        save.whenComplete((outcome, throwable) -> {
            if (throwable != null) {
                this.logger.error(LogCategory.SAVE, attempt.player(), attempt.playerName(), throwable, LogConstants.SYNC_SAVE_FAILED, attempt.playerName());
                completion.completeExceptionally(throwable);
                return;
            }
            SaveResult result = outcome.result();
            if (result.stored()) {
                this.logSaved(attempt, result, System.nanoTime() - submittedAt);
                this.rotateHistory(attempt.player(), attempt.playerName());
                completion.complete(new SnapshotSaveResult.Settled(result, attempt.snapshot().meta().id()));
                return;
            }
            if (result.retriable()) {
                logRetry(this.logger, attempt, outcome.failure());
                if (attempt.canRetry()) {
                    this.retryLater(attempt.next(), completion);
                    return;
                }
            }
            this.stashFailed(attempt, result, outcome.failure(), completion);
        });
    }

    private void logSaved(WriteAttempt attempt, SaveResult result, long storeNanos) {
        SaveCause cause = attempt.snapshot().meta().cause();
        String captureMillis = millis(0, attempt.captureNanos());
        String storeMillis = millis(0, storeNanos);
        if (cause == SaveCause.DISCONNECT) {
            if (PluginConfig.logging$consoleSave(cause)) {
                this.logger.info(LogCategory.QUIT, attempt.player(), attempt.playerName(), LogConstants.SYNC_DISCONNECT_SAVED,
                        attempt.playerName(), captureMillis, storeMillis);
            } else {
                this.logger.file(LogCategory.QUIT, attempt.player(), attempt.playerName(), LogConstants.SYNC_DISCONNECT_SAVED,
                        attempt.playerName(), captureMillis, storeMillis);
            }
            return;
        }
        if (cause == SaveCause.SHUTDOWN) {
            if (PluginConfig.logging$consoleSave(cause)) {
                this.logger.info(LogCategory.SAVE, attempt.player(), attempt.playerName(), LogConstants.SYNC_SHUTDOWN_PLAYER_SAVED,
                        attempt.playerName(), captureMillis, storeMillis);
            } else {
                this.logger.file(LogCategory.SAVE, attempt.player(), attempt.playerName(), LogConstants.SYNC_SHUTDOWN_PLAYER_SAVED,
                        attempt.playerName(), captureMillis, storeMillis);
            }
            return;
        }
        if (PluginConfig.logging$consoleSave(cause)) {
            this.logger.info(LogCategory.SAVE, attempt.player(), attempt.playerName(), LogConstants.SYNC_SAVED, attempt.playerName(), attempt.cause(), result.name(), captureMillis, storeMillis);
        } else {
            this.logger.file(LogCategory.SAVE, attempt.player(), attempt.playerName(), LogConstants.SYNC_SAVED, attempt.playerName(), attempt.cause(), result.name(), captureMillis, storeMillis);
        }
    }

    static void logRetry(@NotNull SyncLogger logger, @NotNull WriteAttempt attempt, @Nullable Throwable failure) {
        if (!attempt.canRetry() || !attempt.shouldLog()) return;
        if (attempt.number() == 1 && failure != null) {
            logger.warnWithFileCause(LogCategory.RETRY, attempt.player(), attempt.playerName(), failure, LogConstants.SYNC_SAVE_PENDING_RETRY, attempt.playerName(), String.valueOf(attempt.number()));
            return;
        }
        logger.warn(LogCategory.RETRY, attempt.player(), attempt.playerName(), LogConstants.SYNC_SAVE_PENDING_RETRY, attempt.playerName(), String.valueOf(attempt.number()));
    }

    static void logFinalFailure(@NotNull SyncLogger logger, @NotNull WriteAttempt attempt, @NotNull SaveResult result, @Nullable Throwable failure) {
        String key = result.retriable() ? LogConstants.SYNC_SAVE_RETRIES_EXHAUSTED : LogConstants.SYNC_SAVE_NEEDS_ATTENTION;
        if (failure != null) {
            logger.errorWithFileCause(LogCategory.RETRY, attempt.player(), attempt.playerName(), failure, key, attempt.playerName(), attempt.cause(), String.valueOf(attempt.number()));
            return;
        }
        logger.error(LogCategory.RETRY, attempt.player(), attempt.playerName(), key, attempt.playerName(), attempt.cause(), String.valueOf(attempt.number()));
    }

    // 延迟任务携带就绪时刻回到玩家队尾, 冷却期间不占 worker.
    private void retryLater(WriteAttempt attempt, CompletableFuture<SnapshotSaveResult> completion) {
        try {
            this.serialExecutor.submitDelayed(attempt.player(), () -> this.write(attempt, completion), attempt.retryDelayMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            this.stashFailed(attempt, SaveResult.RETRY_LATER, null, completion);
        }
    }

    // 无法继续写入的快照落到本地, 等下次启动重放或由管理员处理.
    private void stashFailed(WriteAttempt attempt, SaveResult result, @Nullable Throwable failure, CompletableFuture<SnapshotSaveResult> completion) {
        logFinalFailure(this.logger, attempt, result, failure);
        this.stash.stash(attempt.snapshot(), attempt.playerName(), result);
        completion.complete(new SnapshotSaveResult.Settled(result, attempt.snapshot().meta().id()));
    }

    private void rotateHistory(UUID player, String playerName) {
        try {
            this.storage.rotate(player, PluginConfig.synchronization$maxSnapshots()).whenComplete((deleted, throwable) -> {
                if (throwable != null) {
                    this.logger.file(LogCategory.STORAGE, player, playerName, throwable, LogConstants.SYNC_ROTATE_FAILED, playerName);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** 执行器排空超时后, 把尚未 settle 的快照留到本地 pending. */
    void stashUnsettled() {
        for (var entry : this.unsettledWrites.entrySet()) {
            WriteAttempt attempt = entry.getValue();
            if (entry.getKey().complete(new SnapshotSaveResult.Settled(SaveResult.RETRY_LATER, attempt.snapshot().meta().id()))) {
                this.stash.stash(attempt.snapshot(), attempt.playerName(), SaveResult.RETRY_LATER);
            }
        }
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }

    /** 一次快照写入尝试. */
    record WriteAttempt(@NotNull Snapshot snapshot, @NotNull String playerName, int number, int maxRetries, long captureNanos) {
        private static final int FREE_ATTEMPTS = 5;
        private static final long RETRY_DELAY_STEP_MILLIS = 100;
        private static final long MAX_RETRY_DELAY_MILLIS = 1000;
        private static final int LOG_INTERVAL = 10;

        @NotNull
        static WriteAttempt first(@NotNull Snapshot snapshot, @NotNull String playerName, int maxRetries, long captureNanos) {
            return new WriteAttempt(snapshot, playerName, 1, maxRetries, captureNanos);
        }

        @NotNull
        String cause() {
            return this.snapshot.meta().cause().name();
        }

        @NotNull
        UUID player() {
            return this.snapshot.meta().player();
        }

        boolean canRetry() {
            return this.maxRetries < 0 || this.number <= this.maxRetries;
        }

        long retryDelayMillis() {
            if (this.number <= FREE_ATTEMPTS) return 0;
            return Math.min((this.number - FREE_ATTEMPTS) * RETRY_DELAY_STEP_MILLIS, MAX_RETRY_DELAY_MILLIS);
        }

        boolean shouldLog() {
            return this.number == 1 || this.number % LOG_INTERVAL == 0;
        }

        @NotNull
        WriteAttempt next() {
            return new WriteAttempt(this.snapshot, this.playerName, this.number + 1, this.maxRetries, this.captureNanos);
        }
    }
}
