package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.configuration.ServerConfig;
import net.momirealms.sparrow.sync.data.SnapshotApplier;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

public final class SnapshotService implements AutoCloseable {
    private final SparrowSync plugin;
    private final PluginLogger logger;
    private final SnapshotApplier applier;
    private final StorageProvider storage;
    private final ConcurrentHashMap<UUID, Long> lastCaptureAt = new ConcurrentHashMap<>();  // 每玩家上次分配的采集时间戳.

    public SnapshotService(@NotNull SparrowSync plugin, @NotNull SnapshotApplier applier, @NotNull StorageProvider storage, @NotNull PluginLogger logger) {
        this.plugin = plugin;
        this.applier = applier;
        this.storage = storage;
        this.logger = logger;
    }




    // join 流程走完 (应用成功或确认无历史) 的玩家才允许保存, 半加载状态存出去会覆盖好数据, todo 未来删除
    private final Set<UUID> syncedPlayers = ConcurrentHashMap.newKeySet();

    // todo 未来删除
    /** 标记该玩家的 join 同步流程已完成, 此后允许为其保存快照. */
    public void markSynced(@NotNull UUID player) {
        this.syncedPlayers.add(player);
    }

    // todo 未来删除
    /**
     * 会话结束时清除同步标记.
     *
     * @return 该玩家此前是否处于已同步状态
     */
    public boolean forgetSynced(@NotNull UUID player) {
        return this.syncedPlayers.remove(player);
    }




    /**
     * 读取玩家最新的快照并应用. 任意线程可调用, 应用段自动回到玩家的拥有线程;
     * 应用前玩家已离开时以 {@link LoadOutcome.Gone} 完成.
     */
    @NotNull
    public CompletableFuture<LoadOutcome> loadAndApply(@NotNull Player player) {
        long loadStart = System.nanoTime();
        return this.storage.latestSnapshot(player.getUniqueId()).thenCompose(latest -> {
            // 没有历史的新玩家, 本服状态即权威
            if (latest.isEmpty()) {
                this.logger.info(TranslationManager.console(LogConstants.SYNC_NO_SNAPSHOT, player.getName()));
                return CompletableFuture.completedFuture(new LoadOutcome.Empty());
            }
            // 在读库线程上预解码, 关键数据解不开则整份不应用
            return switch (this.applier.prepare(latest.get())) {
                case SnapshotApplier.PreparedSnapshot.Failed failed -> {
                    String detail = failed.key().asString() + ": " + failed.detail();
                    this.logger.error(TranslationManager.console(LogConstants.SYNC_LOAD_FAILED, player.getName(), detail));
                    yield CompletableFuture.completedFuture(new LoadOutcome.Failed(detail));
                }
                // 应用段回玩家拥有线程, 调度前玩家离开则以 Gone 收尾
                case SnapshotApplier.PreparedSnapshot.Ready ready -> {
                    CompletableFuture<LoadOutcome> outcome = new CompletableFuture<>();
                    player.getScheduler().run(this.plugin.javaPlugin(), task -> {
                        try {
                            outcome.complete(this.applyPrepared(player, ready, loadStart));
                        } catch (Throwable throwable) {
                            outcome.completeExceptionally(throwable);
                        }
                    }, () -> outcome.complete(new LoadOutcome.Gone()));
                    yield outcome;
                }
            };
        }).whenComplete((outcome, throwable) -> {
            if (throwable != null) {
                this.logger.error(TranslationManager.console(LogConstants.SYNC_LOAD_FAILED, player.getName(), String.valueOf(throwable)), throwable);
            }
        });
    }

    private LoadOutcome applyPrepared(Player player, SnapshotApplier.PreparedSnapshot.Ready ready, long loadStart) {
        return switch (this.applier.apply(player, ready)) {
            case SnapshotApplier.ApplyResult.Success success -> {
                this.logger.info(TranslationManager.console(
                        LogConstants.SYNC_APPLIED,
                        player.getName(),
                        String.valueOf(success.applied().size()),
                        String.valueOf(success.skipped().size()),
                        millis(loadStart, System.nanoTime())
                ));
                yield new LoadOutcome.Applied(success.applied().size(), success.skipped().size());
            }
            case SnapshotApplier.ApplyResult.Failure failure -> new LoadOutcome.Failed(failure.failedKey().asString() + ": " + failure.detail());
        };
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
        CompletableFuture<SaveResult> outcome = new CompletableFuture<>();
        this.submitSave(new SaveAttempt(snapshot, player.getName(), 1, PluginConfig.synchronization$maxSaveRetries(), captureStart), outcome);
        return outcome;
    }

    // 落库失败且可重试时把同一份快照排到该玩家队列的队尾, 直到写进去, 用完重试次数, 或者遇上重试解决不了的失败
    private void submitSave(SaveAttempt attempt, CompletableFuture<SaveResult> outcome) {
        long submitAt = System.nanoTime();
        CompletableFuture<SaveResult> save;
        try {
            save = this.storage.saveSnapshot(attempt.snapshot());
        } catch (RejectedExecutionException exception) {
            // 关服排空期间执行器拒收新任务, 这份快照留给本地备份
            this.abandon(attempt, SaveResult.RETRY_LATER, outcome);
            return;
        }
        save.whenComplete((result, throwable) -> {
            if (throwable != null) {
                this.logger.error(TranslationManager.console(LogConstants.SYNC_SAVE_FAILED, attempt.playerName()), throwable);
                outcome.completeExceptionally(throwable);
                return;
            }
            if (result.stored()) {
                this.logger.info(TranslationManager.console(LogConstants.SYNC_SAVED, attempt.playerName(), attempt.cause(), result.name(), millis(attempt.captureStart(), submitAt), millis(submitAt, System.nanoTime())));
                this.rotate(attempt.snapshot().meta().player(), attempt.playerName());
                outcome.complete(result);
                return;
            }
            if (result.retriable() && attempt.retryAllowed()) {
                if (attempt.worthLogging()) {
                    this.logger.warn(TranslationManager.console(LogConstants.SYNC_SAVE_PENDING_RETRY, attempt.playerName(), String.valueOf(attempt.number())));
                }
                this.submitSave(attempt.next(), outcome);
                return;
            }
            this.abandon(attempt, result, outcome);
        });
    }

    // 重试到此为止, 快照交给本地备份 (第四步接上), 在那之前至少让运维知道这份数据卡在哪
    private void abandon(SaveAttempt attempt, SaveResult result, CompletableFuture<SaveResult> outcome) {
        String key = result.retriable() ? LogConstants.SYNC_SAVE_RETRIES_EXHAUSTED : LogConstants.SYNC_SAVE_NEEDS_ATTENTION;
        this.logger.error(TranslationManager.console(key, attempt.playerName(), attempt.cause(), String.valueOf(attempt.number())));
        outcome.complete(result);
    }

    // 轮转快照
    private void rotate(UUID player, String playerName) {
        try {
            this.storage.rotate(player, PluginConfig.synchronization$maxSnapshots()).whenComplete((deleted, throwable) -> {
                if (throwable != null) {
                    this.logger.warn(TranslationManager.console(LogConstants.SYNC_ROTATE_FAILED, playerName), throwable);
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

    @Override
    public void close() {
        int submitted = 0;
        // 为已同步的在线玩家各投递一份 SHUTDOWN 快照.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!this.syncedPlayers.contains(player.getUniqueId())) continue;
            try {
                this.captureAndSave(player, SaveCause.SHUTDOWN);
                submitted++;
            } catch (Throwable throwable) {
                this.logger.error(TranslationManager.console(LogConstants.SYNC_SAVE_FAILED, player.getName()), throwable);
            }
        }
        if (submitted > 0) {
            this.logger.info(TranslationManager.console(LogConstants.SYNC_SHUTDOWN_SAVED, String.valueOf(submitted)));
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
        private static final int LOG_INTERVAL = 10;

        @NotNull
        String cause() {
            return this.snapshot.meta().cause().name();
        }

        boolean retryAllowed() {
            return this.maxRetries < 0 || this.number <= this.maxRetries;
        }

        // 数据库不可达时每次尝试都要等选主超时, 重试自带节奏, 日志仍按次数收敛免得刷屏
        boolean worthLogging() {
            return this.number == 1 || this.number % LOG_INTERVAL == 0;
        }

        @NotNull
        SaveAttempt next() {
            return new SaveAttempt(this.snapshot, this.playerName, this.number + 1, this.maxRetries, this.captureStart);
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
