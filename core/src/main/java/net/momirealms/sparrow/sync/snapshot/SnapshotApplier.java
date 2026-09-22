package net.momirealms.sparrow.sync.snapshot;

import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.sync.api.event.PreApplyEvent;
import net.momirealms.sparrow.sync.api.event.SyncCompleteEvent;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionState;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotApplyResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotLoadResult;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.cluster.cache.SnapshotCache;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplyContext;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.util.EventUtils;
import net.momirealms.sparrow.sync.util.PlayerUtils;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class SnapshotApplier {
    private final SparrowSync plugin;
    private final SyncLogger logger;
    private final DataRegistry dataRegistry;
    private final PlayerDataPipeline playerDataPipeline;
    private final StorageProvider storage;
    private final SnapshotCache cache; // 跨服快照缓存, 未命中时查询数据库
    private volatile boolean operationsClosed; // 停服后拒绝新在线恢复

    SnapshotApplier(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
        this.logger = plugin.logger();
        this.dataRegistry = plugin.dataRegistry();
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

    // 优先读取跨服缓存, 缓存关闭、未命中或读取失败时查询数据库
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

    /** 在登录拦截阶段准备原版要加载的数据, 本地数据为空时保留新玩家语义. */
    @NotNull
    Optional<CompoundTag> applyNative(@NotNull PlayerSession session, @NotNull Optional<CompoundTag> localData, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.playerDataPipeline.applyNative(session, localData, loaded.context());
    }

    /** 在玩家线程应用登录前尚未处理的数据, 并执行交接回调. */
    @NotNull
    SnapshotApplyResult applyOnJoin(@NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.applyData(player, loaded);
    }

    /** 将历史快照应用到在线玩家, 等待数据应用及必要的重生、传送完成. */
    @NotNull
    CompletableFuture<SnapshotApplyResult> applyOnline(@NotNull PlayerSession session, @NotNull Player player, @NotNull Snapshot snapshot) {
        if (this.operationsClosed || this.plugin.sessionManager().find(session.uuid()) != session || session.state() != SessionState.ACTIVE) {
            return CompletableFuture.completedFuture(SnapshotApplyResult.REJECTED);
        }
        return this.prepareSnapshot(snapshot, session.playerName()).thenCompose(loaded -> {
            if (!(loaded instanceof SnapshotLoadResult.Ready ready)) {
                String detail = loaded instanceof SnapshotLoadResult.Failed failed ? failed.detail() : "snapshot preparation failed";
                return CompletableFuture.completedFuture(new SnapshotApplyResult.Failed(detail, false, null, List.of()));
            }
            CompletableFuture<SnapshotApplyResult> completion = new CompletableFuture<>();
            Runnable apply = () -> {
                try {
                    this.applyOnlineNow(session, player, ready).whenComplete((result, failure) -> {
                        if (failure != null) {
                            completion.complete(new SnapshotApplyResult.Failed(failure.toString(), true, failure, ready.context().skipped()));
                        }
                        else {
                            completion.complete(result instanceof SnapshotApplyResult.Failed failed
                                    ? new SnapshotApplyResult.Failed(failed.detail(), true, failed.cause(), ready.context().skipped()) : result);
                        }
                    });
                } catch (Throwable failure) {
                    completion.complete(new SnapshotApplyResult.Failed(failure.toString(), true, failure, ready.context().skipped()));
                }
            };
            // 死亡玩家可能无法调度实体任务, 由 retired 回调继续处理
            Runnable retired = () -> {
                if ((VersionHelper.isPaper() ? player.isConnected() : player.isOnline()) && player.isDead()) {
                    apply.run();
                }
                else {
                    completion.complete(SnapshotApplyResult.REJECTED);
                }
            };
            if (this.plugin.scheduler().platform().runLater(apply, retired, 0, player) == null) {
                completion.complete(SnapshotApplyResult.REJECTED);
            }
            return completion;
        }).exceptionally(failure -> new SnapshotApplyResult.Failed(failure.toString(), false, failure, List.of()));
    }

    /** 在玩家线程发送预应用事件, 再处理重生、数据应用和传送. */
    @NotNull
    private CompletableFuture<SnapshotApplyResult> applyOnlineNow(@NotNull PlayerSession session, @NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
        if (!this.canApplyOnline(session, player)) return CompletableFuture.completedFuture(SnapshotApplyResult.REJECTED);
        List<DataKey> skippedData = PluginConfig.synchronization$skipOnlineRestoreData();
        PreApplyEvent event = new PreApplyEvent(player, loaded.snapshot(), loaded.context().pendingValues());
        // 先按配置排除数据, 事件监听器仍可将其补回
        for (int i = 0; i < skippedData.size(); i++) {
            event.decoded().remove(skippedData.get(i));
        }
        EventUtils.fireAndForget(event);
        if (!this.canApplyOnline(session, player)) return CompletableFuture.completedFuture(SnapshotApplyResult.REJECTED);
        SnapshotApplyContext context = loaded.context();
        // 按事件处理后的结果更新待应用数据
        context.acceptEventValues(event.decoded());
        HealthDataType.Health health = (HealthDataType.Health) context.takePending(HealthDataType.HEALTH);
        LocationDataType.PlayerLocation location = (LocationDataType.PlayerLocation) context.takePending(LocationDataType.LOCATION);
        CompletableFuture<Void> respawn = health != null && health.health() > 0 && player.isDead()
                ? this.respawn(player) : CompletableFuture.completedFuture(null);
        return respawn.thenCompose(ignored -> this.applyOnlineData(session, player, loaded, health, location));
    }

    /** 调用原版重生流程, Folia 完成后回到玩家所在区域. */
    @NotNull
    private CompletableFuture<Void> respawn(@NotNull Player player) {
        if (!VersionHelper.isFolia()) {
            player.spigot().respawn();
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ServerPlayerProxy.INSTANCE.respawn(((CraftPlayer) player).getHandle(), ignored -> completion.complete(null), PlayerRespawnEvent.RespawnReason.PLUGIN);
        return completion;
    }

    /** 依次应用一般数据、生命值和位置; 零生命值等传送完成后再写入. */
    @SuppressWarnings("unchecked")
    @NotNull
    private CompletableFuture<SnapshotApplyResult> applyOnlineData(@NotNull PlayerSession session, @NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded, @Nullable HealthDataType.Health health, @Nullable LocationDataType.PlayerLocation location) {
        if (!this.canApplyOnline(session, player)) return CompletableFuture.completedFuture(new SnapshotApplyResult.Failed("player session ended during restore"));
        if (health != null && health.health() > 0 && player.isDead()) {
            return CompletableFuture.completedFuture(new SnapshotApplyResult.Failed("player did not respawn"));
        }
        SnapshotApplyResult applied = this.applyData(player, loaded);
        if (!(applied instanceof SnapshotApplyResult.Applied)) return CompletableFuture.completedFuture(applied);
        SnapshotApplyContext context = loaded.context();
        // 重生完成后再写入正生命值
        if (health != null && !(health.health() <= 0)) {
            ((PlayerDataType<HealthDataType.Health>) this.dataRegistry.type(HealthDataType.HEALTH)).apply(player, health);
            context.appliedPlayer(HealthDataType.HEALTH);
        }
        CompletableFuture<Boolean> teleport;
        if (location == null) {
            teleport = CompletableFuture.completedFuture(true);
        } else {
            World world = player.getServer().getWorld(location.world());
            if (world == null) return CompletableFuture.completedFuture(new SnapshotApplyResult.Failed("location world is not loaded: " + location.world()));
            teleport = PlayerUtils.teleport(player, new Location(world, location.x(), location.y(), location.z(), location.yaw(), location.pitch()));
        }
        return teleport.thenApply(moved -> {
            if (!this.canApplyOnline(session, player)) return new SnapshotApplyResult.Failed("player session ended during restore");
            if (!moved) return new SnapshotApplyResult.Failed("location teleport was rejected: " + location.world());
            if (location != null) {
                context.appliedPlayer(LocationDataType.LOCATION);
            }
            if (health != null && health.health() <= 0) {
                // 完成数据应用和传送后再触发死亡
                if (!player.isDead()) {
                    player.setHealth(0);
                }
                context.appliedPlayer(HealthDataType.HEALTH);
            }
            if (!this.canApplyOnline(session, player)) return new SnapshotApplyResult.Failed("player session ended during restore");
            session.retainedData(context.passthrough());
            SnapshotApplyResult.Applied result = new SnapshotApplyResult.Applied(context.applied(), context.skipped(), context.failures());
            EventUtils.fireAndForget(new SyncCompleteEvent(player, loaded.snapshot(), result.applied(), result.skipped()));
            return result;
        });
    }

    // 检查异步任务是否仍对应原会话
    private boolean canApplyOnline(@NotNull PlayerSession session, @NotNull Player player) {
        synchronized (session) {
            // 同一 UUID 重新登录后, 旧任务不能修改新会话
            return player.isOnline() && this.plugin.sessionManager().find(session.uuid()) == session && session.state() == SessionState.ACTIVE;
        }
    }

    // 按依赖顺序应用待处理数据并记录耗时
    @NotNull
    private SnapshotApplyResult applyData(@NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
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

    /** 停止新在线恢复, 已排队的任务仍需检查会话是否有效. */
    public void stopOperations() {
        this.operationsClosed = true;
    }

    @NotNull
    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }
}
