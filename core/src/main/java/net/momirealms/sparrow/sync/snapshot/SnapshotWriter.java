package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
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
    private final SnapshotStash stash; // 完整正文的 pending 或异常档案写入入口
    private final PlayerSerialExecutor serialExecutor;
    private final SnapshotCache cache; // 跨服快路径 Redis 缓存
    private final Set<SaveRequest> pending = new HashSet<>(); // 已接收但尚未完成的保存请求, 用 pending 自身当锁协调并发访问
    private boolean sealed; // 在集合监视器内读写, 为 true 时拒绝新请求而继续已有保存

    SnapshotWriter(@NotNull SyncLogger logger, @NotNull StorageProvider storage, @NotNull SnapshotStash stash, @NotNull PlayerSerialExecutor serialExecutor, @NotNull SnapshotCache cache) {
        this.logger = logger;
        this.storage = storage;
        this.stash = stash;
        this.serialExecutor = serialExecutor;
        this.cache = cache;
    }

    // 在采集或投递任务前登记请求, 使停服等待包含尚未生成正文的保存.
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
     * 接收已通过保存事件检查的完整快照, 并启动第一次存储写入.
     * <p>request 必须持有已完成地图处理、可直接写入存储的 Snapshot. 此方法只发起写入，不等待最终保存结果。</p>
     *
     * @param request 已完成准备的在途保存请求
     */
    void write(@NotNull SaveRequest request) {
        if (request.finishing()) return;
        this.logger.file(LogCategory.SAVE, request.meta().player(), request.playerName(), LogConstants.SYNC_SAVE_STARTED, request.playerName(), request.meta().cause().name(), request.meta().id().toString());
        this.write(WriteAttempt.first(request, PluginConfig.synchronization$maxSaveRetries()));
    }

    // 执行一次写入, 根据存储结果继续重试或取得最终收尾权.
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
            // 重试任务也可能在提交阶段失败, 请求回执在此结束, 原异常继续交给执行器报告.
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
                // request.completion() 完成时会释放锁, 缓存更新投递必须排在之前
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

    // 按保存原因和日志配置报告已经确认的存储结果.
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

    // 在首次失败及指定重试次数报告进度, 首次失败保留完整原因.
    static void logRetry(@NotNull SyncLogger logger, @NotNull WriteAttempt attempt, @Nullable Throwable failure) {
        if (!attempt.canRetry() || !attempt.shouldLog()) return;
        if (attempt.number() == 1 && failure != null) {
            logger.warnWithFileCause(LogCategory.RETRY, attempt.player(), attempt.playerName(), failure, LogConstants.SYNC_SAVE_PENDING_RETRY, attempt.playerName(), String.valueOf(attempt.number()));
            return;
        }
        logger.warn(LogCategory.RETRY, attempt.player(), attempt.playerName(), LogConstants.SYNC_SAVE_PENDING_RETRY, attempt.playerName(), String.valueOf(attempt.number()));
    }

    // 报告重试耗尽或永久拒绝的分类, 随后由持有收尾权的调用方暂存正文.
    static void logFinalFailure(@NotNull SyncLogger logger, @NotNull WriteAttempt attempt, @NotNull SaveResult result, @Nullable Throwable failure) {
        String key = result.retriable() ? LogConstants.SYNC_SAVE_RETRIES_EXHAUSTED : LogConstants.SYNC_SAVE_NEEDS_ATTENTION;
        if (failure != null) {
            logger.errorWithFileCause(LogCategory.RETRY, attempt.player(), attempt.playerName(), failure, key, attempt.playerName(), attempt.cause(), String.valueOf(attempt.number()));
            return;
        }
        logger.error(LogCategory.RETRY, attempt.player(), attempt.playerName(), key, attempt.playerName(), attempt.cause(), String.valueOf(attempt.number()));
    }

    // 按原退避规则把下一次尝试排入玩家队列, 冷却期间释放 worker.
    private void retryLater(WriteAttempt attempt) {
        if (attempt.request().finishing()) return;
        try {
            this.serialExecutor.submitDelayed(attempt.player(), () -> this.write(attempt), attempt.retryDelayMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            this.stashFailed(attempt, SaveResult.RETRY_LATER, null);
        }
    }

    // 按存储分类尝试本地留存, 文件写入尝试结束后完成最终回执.
    private void stashFailed(WriteAttempt attempt, SaveResult result, @Nullable Throwable failure) {
        SaveRequest request = attempt.request();
        if (!request.beginFinish()) return;
        logFinalFailure(this.logger, attempt, result, failure);
        this.stash.stash(request.snapshot(), request.playerName(), result);
        request.completion().complete(new SnapshotSaveResult.Settled(result, request.meta().id()));
    }

    // 成功写入后发起历史轮转, 保存回执不等待轮转完成.
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

    // 会话收尾保存的落库结果投递到跨服快路径, 其余落库结果只清掉可能残留的旧条目.
    private void publishCache(@NotNull WriteAttempt attempt, @NotNull SaveResult result) {
        if (!shouldPublish(result, attempt.request().meta().cause())) {
            this.cache.invalidate(attempt.player());
            return;
        }
        PluginConfig.SnapshotCacheOptions options = PluginConfig.synchronization$snapshotCache();
        if (!options.enabled()) return;
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

    // 只有会话收尾保存里确认落在库内最新的结果才投递, SAVED_OUT_OF_ORDER 明确位于历史中段.
    static boolean shouldPublish(@NotNull SaveResult result, @NotNull SaveCause cause) {
        if (!result.stored() || result == SaveResult.SAVED_OUT_OF_ORDER) return false;
        return cause == SaveCause.DISCONNECT || cause == SaveCause.SHUTDOWN;
    }

    /**
     * 封闭接收入口并等待当前请求的最终结果, 连续无进展达到期限时结束等待.
     *
     * @param timeout 连续无进展的最长等待时间, 非正数表示不等待
     * @param unit 等待时间的单位
     * @return 是否等到了整批请求结束, 单份请求异常结束也计为结束
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
                // 整批完成可立即唤醒; 单份异常完成仍需等其他请求, 超时醒来重新观察进度.
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

    // 汇总最终回执, 未落库的结果和异常都归入失败, 本地留存另由 Stash 报告.
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
     * 执行器结束后暂存仍未结束的完整正文, 尚未生成正文的请求以超时失败结束.
     * <p>正在进行最终文件写入的请求由原收尾方继续完成, 重复清理不会再次取得处理权.
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
     * 一次写入的重试进度, 正文和最终结果始终由同一份请求持有.
     *
     * @param request 本次尝试所属的保存请求
     * @param number 从一开始的尝试次数
     * @param maxRetries 首次实际写入时固定的最大重试次数, 负数表示不限次数
     */
    record WriteAttempt(@NotNull SaveRequest request, int number, int maxRetries) {
        private static final int FREE_ATTEMPTS = 5; // 前五次尝试立即执行
        private static final long RETRY_DELAY_STEP_MILLIS = 100; // 后续每次增加的等待毫秒数
        private static final long MAX_RETRY_DELAY_MILLIS = 1000; // 单次重试等待上限, 单位为毫秒
        private static final int LOG_INTERVAL = 10; // 首次之外每十次尝试记录一次进度

        /**
         * 固定一份已准备请求的重试策略并创建首次尝试.
         *
         * @param request 正文已准备的保存请求
         * @param maxRetries 首次写入时的重试配置
         * @return 从第一次开始的写入尝试
         */
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
