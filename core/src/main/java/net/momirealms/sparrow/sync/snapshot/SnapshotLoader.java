package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.cluster.cache.SnapshotCache;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotLoadResult;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class SnapshotLoader {
    private final SyncLogger logger;
    private final PlayerDataPipeline playerDataPipeline;
    private final StorageProvider storage;
    private final SnapshotCache cache;

    SnapshotLoader(SparrowSync plugin) {
        this.logger = plugin.logger();
        this.playerDataPipeline = plugin.playerDataPipeline();
        this.storage = plugin.storageProvider();
        this.cache = plugin.snapshotCache();
    }

    /** 读取最新快照并准备地图和类型数据, 没有历史快照时返回 Empty. */
    @NotNull
    CompletableFuture<SnapshotLoadResult> loadLatest(@NotNull UUID player, @NotNull String playerName) {
        long loadStart = System.nanoTime();
        return this.cachedLatest(player, playerName, loadStart)
                .thenCompose(latest -> latest
                        .map(snapshot -> this.prepareSnapshot(snapshot, playerName, loadStart))
                        .orElseGet(() -> {
                            this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_EMPTY, playerName, millis(loadStart, System.nanoTime()));
                            return CompletableFuture.completedFuture(SnapshotLoadResult.EMPTY);
                        }))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        this.logger.error(LogCategory.APPLY, player, playerName, throwable, LogConstants.SYNC_LOAD_FAILED, playerName, millis(loadStart, System.nanoTime()), String.valueOf(throwable));
                    }
                });
    }

    // 优先读取跨服缓存, 缓存关闭, 未命中或读取失败时查询数据库
    @NotNull
    private CompletableFuture<Optional<Snapshot>> cachedLatest(@NotNull UUID player, @NotNull String playerName, long loadStart) {
        if (!PluginConfig.synchronization$snapshotCache().enabled()) return this.storage.latestSnapshot(player);
        return this.cache.consume(player).thenCompose(cached -> {
            if (cached.isEmpty()) return this.storage.latestSnapshot(player);
            Snapshot snapshot = cached.get();
            this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_CACHE_HIT, playerName, snapshot.meta().id().toString(), millis(loadStart, System.nanoTime()));
            return CompletableFuture.completedFuture(cached);
        });
    }

    /** 将历史快照准备为本服可应用的数据, 保留原快照, 关键类型解码失败时返回 Failed. */
    @NotNull
    CompletableFuture<SnapshotLoadResult> prepareSnapshot(@NotNull Snapshot snapshot, @NotNull String playerName) {
        return this.prepareSnapshot(snapshot, playerName, System.nanoTime());
    }

    // 先处理地图, 再解码各类型并记录应用进度
    @NotNull
    private CompletableFuture<SnapshotLoadResult> prepareSnapshot(@NotNull Snapshot snapshot, @NotNull String playerName, long started) {
        return this.playerDataPipeline.decodeAsync(snapshot).thenApply(result -> switch (result) {
            case PlayerDataPipeline.DecodeResult.Ready ready -> {
                long loadNanos = System.nanoTime() - started;
                this.logger.file(LogCategory.APPLY, snapshot.meta().player(), playerName, LogConstants.SYNC_LOAD_READY, playerName, snapshot.meta().id().toString(), millis(0, loadNanos));
                yield new SnapshotLoadResult.Ready(snapshot, ready.context(), loadNanos);
            }
            case PlayerDataPipeline.DecodeResult.Failed failed -> {
                String detail = failed.key().asString() + ": " + failed.detail();
                this.logger.error(LogCategory.APPLY, snapshot.meta().player(), playerName, LogConstants.SYNC_LOAD_FAILED, playerName, millis(started, System.nanoTime()), detail);
                yield new SnapshotLoadResult.Failed(detail);
            }
        });
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }
}