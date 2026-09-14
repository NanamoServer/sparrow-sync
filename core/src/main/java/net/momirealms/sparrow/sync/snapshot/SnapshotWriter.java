package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.cluster.cache.SnapshotCache;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotStash;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveOutcome;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class SnapshotWriter {
    private static final long SHUTDOWN_REPORT_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final SyncLogger logger;
    private final StorageProvider storage;
    private final SnapshotStash stash;
    private final PlayerSerialExecutor serialExecutor;
    private final SnapshotCache cache;
    private final Set<SaveRequest> pending = new HashSet<>(); // 尚未完成的保存请求, 并发访问时锁住此集合
    private boolean sealed; // 在 pending 锁内访问, 为 true 时只继续处理已有请求

    SnapshotWriter(@NotNull SyncLogger logger, @NotNull StorageProvider storage, @NotNull SnapshotStash stash, @NotNull PlayerSerialExecutor serialExecutor, @NotNull SnapshotCache cache) {
        this.logger = logger;
        this.storage = storage;
        this.stash = stash;
        this.serialExecutor = serialExecutor;
        this.cache = cache;
    }

    // 采集前登记请求, 关服时也要等待尚未编码的请求
    void register(@NotNull SaveRequest request) {
        synchronized (this.pending) {
            if (this.sealed) throw new RejectedExecutionException("snapshot writer is sealed");
            this.pending.add(request);
        }
        request.completion().whenComplete((result, failure) -> {
            synchronized (this.pending) {
                this.pending.remove(request);
            }
        });
    }

    /**
     * 开始保存已通过事件检查的快照, 最终结果由 request.completion() 返回.
     * <strong>请求中的快照须已完成地图处理</strong>.
     */
    void write(@NotNull SaveRequest request) {
        if (request.finishing()) return;
        this.logger.file(LogCategory.SAVE, request.meta().player(), request.playerName(), LogConstants.SYNC_SAVE_STARTED, request.playerName(), request.meta().cause().name(), request.meta().id().toString());
        this.write(WriteAttempt.first(request, PluginConfig.synchronization$maxSaveRetries()));
    }

    // 尝试写入数据库, 按结果重试或结束请求
    private void write(WriteAttempt attempt) {
        SaveRequest request = attempt.request();
        if (request.finishing()) return;
        long submittedAt = System.nanoTime();
        CompletableFuture<SaveOutcome> save;
        try {
            save = this.storage.saveSnapshotOutcome(request.snapshot());
        } catch (RejectedExecutionException exception) {
            this.stashFailed(attempt, SaveResult.RETRY_LATER, null);
            return;
        } catch (RuntimeException | Error failure) {
            // 任务提交失败时结束请求, 原异常交给执行器记录
            request.fail(failure);
            throw failure;
        }
        save.whenComplete((outcome, throwable) -> {
            if (request.finishing()) return;
            if (throwable != null) {
                if (request.beginFinish()) {
                    this.logger.error(LogCategory.SAVE, attempt.player(), request.playerName(), throwable, LogConstants.SYNC_SAVE_FAILED, request.playerName());
                    request.completion().completeExceptionally(throwable);
                }
                return;
            }
            SaveResult result = outcome.result();
            if (result.stored()) {
                if (!request.beginFinish()) return;
                this.logSaved(attempt, result, System.nanoTime() - submittedAt);
                this.rotateHistory(attempt.player(), request.playerName());
                // 完成请求会触发解锁, 必须先提交缓存更新
                this.publishCache(attempt, result);
                request.completion().complete(new SnapshotSaveResult.Settled(result, request.meta().id()));
                return;
            }
            if (result.retriable()) {
                logRetry(this.logger, attempt, outcome.failure());
                if (attempt.canRetry()) {
                    this.retryLater(attempt.next());
                    return;
                }
            }
            this.stashFailed(attempt, result, outcome.failure());
        });
    }

    // 按保存原因和配置输出保存结果
    private void logSaved(WriteAttempt attempt, SaveResult result, long storeNanos) {
        SaveCause cause = attempt.request().meta().cause();
        String captureMillis = millis(0, attempt.request().captureNanos());
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

    // 首次失败记录完整原因, 后续按重试次数定期报告
    static void logRetry(@NotNull SyncLogger logger, @NotNull WriteAttempt attempt, @Nullable Throwable failure) {
        if (!attempt.canRetry() || !attempt.shouldLog()) return;
        if (attempt.number() == 1 && failure != null) {
            logger.warnWithFileCause(LogCategory.RETRY, attempt.player(), attempt.playerName(), failure, LogConstants.SYNC_SAVE_PENDING_RETRY, attempt.playerName(), String.valueOf(attempt.number()));
            return;
        }
        logger.warn(LogCategory.RETRY, attempt.player(), attempt.playerName(), LogConstants.SYNC_SAVE_PENDING_RETRY, attempt.playerName(), String.valueOf(attempt.number()));
    }

    // 记录重试耗尽或不可重试错误, 随后尝试本地暂存
    static void logFinalFailure(@NotNull SyncLogger logger, @NotNull WriteAttempt attempt, @NotNull SaveResult result, @Nullable Throwable failure) {
        String key = result.retriable() ? LogConstants.SYNC_SAVE_RETRIES_EXHAUSTED : LogConstants.SYNC_SAVE_NEEDS_ATTENTION;
        if (failure != null) {
            logger.errorWithFileCause(LogCategory.RETRY, attempt.player(), attempt.playerName(), failure, key, attempt.playerName(), attempt.cause(), String.valueOf(attempt.number()));
            return;
        }
        logger.error(LogCategory.RETRY, attempt.player(), attempt.playerName(), key, attempt.playerName(), attempt.cause(), String.valueOf(attempt.number()));
    }

    // 延迟后重新加入玩家队列, 等待期间不占用线程
    private void retryLater(WriteAttempt attempt) {
        if (attempt.request().finishing()) return;
        try {
            this.serialExecutor.submitDelayed(attempt.player(), () -> this.write(attempt), attempt.retryDelayMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            this.stashFailed(attempt, SaveResult.RETRY_LATER, null);
        }
    }

    // 尝试本地暂存, 文件写入结束后完成请求
    private void stashFailed(WriteAttempt attempt, SaveResult result, @Nullable Throwable failure) {
        SaveRequest request = attempt.request();
        if (!request.beginFinish()) return;
        logFinalFailure(this.logger, attempt, result, failure);
        this.stash.stash(request.snapshot(), request.playerName(), result);
        request.completion().complete(new SnapshotSaveResult.Settled(result, request.meta().id()));
    }

    // 保存成功后清理旧快照, 保存结果不等待清理完成
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

    // 退出和关服保存可更新跨服缓存, 其他保存清除旧缓存
    private void publishCache(@NotNull WriteAttempt attempt, @NotNull SaveResult result) {
        PluginConfig.SnapshotCacheOptions options = PluginConfig.synchronization$snapshotCache();
        // 关闭缓存发布后仍需清除其他服务器留下的旧缓存
        if (!options.enabled() || !shouldPublish(result, attempt.request().meta().cause())) {
            this.cache.invalidate(attempt.player());
            return;
        }
        Snapshot snapshot = attempt.request().snapshot();
        if (snapshot == null) return;
        int ttlSeconds = options.ttlSeconds();
        this.cache.publish(snapshot, ttlSeconds).whenComplete((ignored, failure) -> {
            if (failure != null) {
                this.logger.file(LogCategory.SAVE, attempt.player(), attempt.playerName(), failure, LogConstants.SYNC_CACHE_PUBLISH_FAILED, attempt.playerName(), String.valueOf(failure.getMessage()));
                return;
            }
            this.logger.file(LogCategory.SAVE, attempt.player(), attempt.playerName(), LogConstants.SYNC_CACHE_PUBLISHED, attempt.playerName(), attempt.request().meta().id().toString(), String.valueOf(ttlSeconds));
        });
    }

    // 仅缓存退出或关服保存中确认最新的快照, 乱序保存结果不发布
    static boolean shouldPublish(@NotNull SaveResult result, @NotNull SaveCause cause) {
        if (!result.stored() || result == SaveResult.SAVED_OUT_OF_ORDER) return false;
        return cause == SaveCause.DISCONNECT || cause == SaveCause.SHUTDOWN;
    }

    /**
     * 停止接收新请求并等待已有请求结束, 连续无进展超时后停止等待.
     * @param timeout 连续无进展的最长等待时间, 非正数表示不等待
     * @param unit 时间单位
     * @return 所有请求均已结束时为 true, 异常完成也算结束
     */
    boolean sealAndAwaitSaves(long timeout, @NotNull TimeUnit unit) {
        CompletableFuture<?>[] completions;
        synchronized (this.pending) {
            this.sealed = true;
            completions = new CompletableFuture<?>[this.pending.size()];
            int index = 0;
            for (SaveRequest request : this.pending) {
                completions[index++] = request.completion();
            }
        }
        if (completions.length == 0) return true;
        CompletableFuture<Void> finished = CompletableFuture.allOf(completions);
        long idleNanos = Math.max(0, unit.toNanos(timeout));
        long lastProgress = System.nanoTime();
        long nextReport = lastProgress;
        int previousCompleted = 0;
        try {
            while (true) {
                int completed = 0;
                for (int i = 0; i < completions.length; i++) {
                    if (completions[i].isDone()) completed++;
                }
                long now = System.nanoTime();
                if (completed > previousCompleted) {
                    lastProgress = now;
                    previousCompleted = completed;
                }
                if (completed == completions.length) return true;
                long stalledNanos = now - lastProgress;
                long remaining = idleNanos - stalledNanos;
                if (now >= nextReport) {
                    if (stalledNanos >= SHUTDOWN_REPORT_NANOS) {
                        long remainingSeconds = remaining > 0 ? TimeUnit.NANOSECONDS.toSeconds(remaining - 1) + 1 : 0;
                        this.logger.info(LogCategory.SAVE, LogConstants.SYNC_SHUTDOWN_STALLED, String.valueOf(completed), String.valueOf(completions.length),
                                String.valueOf(TimeUnit.NANOSECONDS.toSeconds(stalledNanos)), String.valueOf(remainingSeconds));
                    } else {
                        this.logger.info(LogCategory.SAVE, LogConstants.SYNC_SHUTDOWN_PROGRESS, String.valueOf(completed), String.valueOf(completions.length));
                    }
                    nextReport = now + SHUTDOWN_REPORT_NANOS;
                }
                if (remaining <= 0) {
                    this.logger.warn(LogCategory.SAVE, LogConstants.SYNC_SHUTDOWN_TIMEOUT, String.valueOf(completed), String.valueOf(completions.length));
                    return false;
                }
                // 整批结束时立即唤醒; 单个请求失败后继续等待其余请求
                try {
                    finished.get(Math.min(remaining, Math.max(0, nextReport - System.nanoTime())), TimeUnit.NANOSECONDS);
                } catch (ExecutionException | TimeoutException ignored) {
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.logger.warn(LogCategory.SAVE, LogConstants.SYNC_SHUTDOWN_INTERRUPTED);
            return false;
        } finally {
            this.logShutdownSummary(completions);
        }
    }

    // 统计数据库保存结果, 本地暂存结果由 SnapshotStash 另行记录
    private void logShutdownSummary(CompletableFuture<?>[] completions) {
        int stored = 0;
        int cancelled = 0;
        int failed = 0;
        int unfinished = 0;
        for (int i = 0; i < completions.length; i++) {
            CompletableFuture<?> completion = completions[i];
            if (!completion.isDone()) {
                unfinished++;
            } else if (completion.isCancelled()) {
                cancelled++;
            } else if (completion.isCompletedExceptionally()) {
                failed++;
            } else if (completion.getNow(null) == SnapshotSaveResult.CANCELLED) {
                cancelled++;
            } else if (((SnapshotSaveResult.Settled) completion.getNow(null)).result().stored()) {
                stored++;
            } else {
                failed++;
            }
        }
        this.logger.info(LogCategory.SAVE, LogConstants.SYNC_SHUTDOWN_SUMMARY, String.valueOf(stored), String.valueOf(cancelled), String.valueOf(failed), String.valueOf(unfinished));
    }

    /**
     * 执行器结束后暂存剩余的完整快照, 尚未编码的请求按超时结束.
     * 已有线程正在写入本地文件时, 由该线程继续完成.
     */
    void stashUnsettled() {
        SaveRequest[] requests;
        synchronized (this.pending) {
            requests = this.pending.toArray(SaveRequest[]::new);
        }
        for (int i = 0; i < requests.length; i++) {
            SaveRequest request = requests[i];
            if (!request.beginFinish()) continue;
            Snapshot snapshot = request.snapshot();
            if (snapshot == null) {
                TimeoutException failure = new TimeoutException("snapshot was not encoded before shutdown timeout: " + request.meta().id());
                this.logger.error(LogCategory.SAVE, request.meta().player(), request.playerName(), failure, LogConstants.SYNC_SAVE_FAILED, request.playerName());
                request.completion().completeExceptionally(failure);
            } else {
                this.stash.stash(snapshot, request.playerName(), SaveResult.RETRY_LATER);
                request.completion().complete(new SnapshotSaveResult.Settled(SaveResult.RETRY_LATER, request.meta().id()));
            }
        }
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }

    /**
     * 一次写入尝试, 与同一请求的其他尝试共享快照和最终结果.
     * @param number 从 1 开始的尝试次数
     * @param maxRetries 首次写入时确定的重试上限, 负数表示不限
     */
    record WriteAttempt(@NotNull SaveRequest request, int number, int maxRetries) {
        private static final int FREE_ATTEMPTS = 5; // 前五次尝试立即执行
        private static final long RETRY_DELAY_STEP_MILLIS = 100; // 后续每次增加的等待毫秒数
        private static final long MAX_RETRY_DELAY_MILLIS = 1000; // 单次重试等待上限, 单位为毫秒
        private static final int LOG_INTERVAL = 10; // 首次之外每十次尝试记录一次进度

        @NotNull
        static WriteAttempt first(@NotNull SaveRequest request, int maxRetries) {
            return new WriteAttempt(request, 1, maxRetries);
        }

        @NotNull
        String cause() {
            return this.request.meta().cause().name();
        }

        @NotNull
        UUID player() {
            return this.request.meta().player();
        }

        @NotNull
        String playerName() {
            return this.request.playerName();
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
            return new WriteAttempt(this.request, this.number + 1, this.maxRetries);
        }
    }
}
