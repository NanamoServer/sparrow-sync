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
    private final SnapshotCache cache; // 跨服快路径, 未启用或未命中时读数据库
    private volatile boolean operationsClosed; // 停服后拒绝新的在线恢复

    SnapshotApplier(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
        this.logger = plugin.logger();
        this.dataRegistry = plugin.dataRegistry();
        this.playerDataPipeline = plugin.playerDataPipeline();
        this.storage = plugin.storageProvider();
        this.cache = plugin.snapshotCache();
    }

    /**
     * 读取玩家最新快照并完成地图准备与类型解码, 供玩家登录时消费.
     *
     * @param player 目标玩家 UUID
     * @param playerName 日志使用的玩家名
     * @return 异步加载结果, 没有历史快照时为 Empty
     */
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

    // 优先取跨服快路径, 未启用、未命中或 Redis 失败时读数据库.
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

    /**
     * 将选中的历史快照准备为当前服务器可应用的数据, 返回结果保留原始快照.
     *
     * @param snapshot 要加载的历史快照
     * @param playerName 日志和地图处理使用的玩家名
     * @return 已准备的加载结果, 关键类型无法解码时为 Failed
     */
    @NotNull
    CompletableFuture<SnapshotLoadResult> prepareSnapshot(@NotNull Snapshot snapshot, @NotNull String playerName) {
        return this.prepareSnapshot(snapshot, playerName, System.nanoTime());
    }

    // 地图处理和类型解码产出 Context.
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

    /**
     * 在登录 Gate 阶段将待应用值写入原版登录数据源.
     *
     * @param session 本次操作所属的玩家会话
     * @param localData 本地原版登录数据
     * @param loaded 当前请求已准备的快照和应用 Context
     * @return 供原版登录加载使用的数据, 保留空数据的新玩家语义
     */
    @NotNull
    Optional<CompoundTag> applyNative(@NotNull PlayerSession session, @NotNull Optional<CompoundTag> localData, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.playerDataPipeline.applyNative(session, localData, loaded.context());
    }

    /**
     * 在玩家线程应用 Native 阶段尚未处理的数据, 并执行 Native 留下的交接回调.
     *
     * @param player 当前操作绑定的玩家对象
     * @param loaded 当前请求已准备的快照和应用 Context
     * @return 本次 Join 的应用结果
     */
    @NotNull
    SnapshotApplyResult applyOnJoin(@NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.applyData(player, loaded);
    }

    /**
     * 将选中的历史快照应用到在线玩家.
     * 结果会等到玩家线程、必要的重生和传送全部完成.
     *
     * @param player 当前操作绑定的玩家对象
     * @param snapshot 当前选定的完整快照
     * @return 应用、拒绝或失败结果
     */
    @NotNull
    CompletableFuture<SnapshotApplyResult> applyOnline(@NotNull Player player, @NotNull Snapshot snapshot) {
        PlayerSession session = this.plugin.sessionManager().find(player.getUniqueId());
        if (this.operationsClosed || session == null || session.state() != SessionState.ACTIVE) {
            return CompletableFuture.completedFuture(SnapshotApplyResult.REJECTED);
        }
        return this.prepareSnapshot(snapshot, session.playerName()).thenCompose(loaded -> {
            if (!(loaded instanceof SnapshotLoadResult.Ready ready)) {
                return CompletableFuture.completedFuture(new SnapshotApplyResult.Failed("snapshot preparation failed"));
            }
            CompletableFuture<SnapshotApplyResult> completion = new CompletableFuture<>();
            Runnable apply = () -> {
                try {
                    this.applyOnlineNow(session, player, ready).whenComplete((result, failure) -> {
                        if (failure != null) {
                            completion.completeExceptionally(failure);
                        }
                        else {
                            completion.complete(result);
                        }
                    });
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            };
            // 死亡玩家暂时没有可调度的实体. 平台在实体退役后通过 retired 回调交付这项任务.
            Runnable retired = () -> {
                if (player.isConnected() && player.isDead()) {
                    apply.run();
                }
                else {
                    completion.complete(SnapshotApplyResult.REJECTED);
                }
            };
            if (this.plugin.scheduler().entity().run(player, apply, retired) == null) {
                completion.complete(SnapshotApplyResult.REJECTED);
            }
            return completion;
        });
    }

    /**
     * 在玩家线程派发预应用事件, 然后处理重生、一般数据、生命值和位置.
     *
     * @param session 本次操作所属的玩家会话
     * @param player 当前操作绑定的玩家对象
     * @param loaded 当前请求已准备的快照和应用 Context
     * @return 等待必要重生和后续玩家数据应用的结果
     */
    @NotNull
    private CompletableFuture<SnapshotApplyResult> applyOnlineNow(@NotNull PlayerSession session, @NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
        if (!this.canApplyOnline(session, player)) return CompletableFuture.completedFuture(SnapshotApplyResult.REJECTED);
        List<DataKey> skippedData = PluginConfig.synchronization$skipOnlineRestoreData();
        PreApplyEvent event = new PreApplyEvent(player, loaded.snapshot(), loaded.context().pendingValues());
        // 配置先移除默认不恢复的值. 监听器仍可按自己的规则补回这些值.
        for (int i = 0; i < skippedData.size(); i++) {
            event.decoded().remove(skippedData.get(i));
        }
        EventUtils.fireAndForget(event);
        if (!this.canApplyOnline(session, player)) return CompletableFuture.completedFuture(SnapshotApplyResult.REJECTED);
        SnapshotApplyContext context = loaded.context();
        // 事件可能删除、替换或补回值. Context 接收事件最终留下的集合.
        context.acceptEventValues(event.decoded());
        HealthDataType.Health health = (HealthDataType.Health) context.takePending(HealthDataType.HEALTH);
        LocationDataType.PlayerLocation location = (LocationDataType.PlayerLocation) context.takePending(LocationDataType.LOCATION);
        CompletableFuture<Void> respawn = health != null && health.health() > 0 && player.isDead()
                ? this.respawn(player) : CompletableFuture.completedFuture(null);
        return respawn.thenCompose(ignored -> this.applyOnlineData(session, player, loaded, health, location));
    }

    /**
     * 调用平台原生重生.
     * Folia 的完成回调会回到目标玩家所在区域.
     *
     * @param player 当前操作绑定的玩家对象
     * @return 原生重生完成信号
     */
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

    /**
     * 应用一般类型、生命值和位置.
     * 正生命值会在一般类型后设置. 零生命值会等待传送成功后设置.
     *
     * @param session 本次操作所属的玩家会话
     * @param player 当前操作绑定的玩家对象
     * @param loaded 当前请求已准备的快照和应用 Context
     * @param health 本次在线恢复的生命值, 未选择时为 null
     * @param location 本次在线恢复的位置, 未选择时为 null
     * @return 包含最终已应用和跳过类型的结果
     */
    @SuppressWarnings("unchecked")
    @NotNull
    private CompletableFuture<SnapshotApplyResult> applyOnlineData(@NotNull PlayerSession session, @NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded, @Nullable HealthDataType.Health health, @Nullable LocationDataType.PlayerLocation location) {
        if (!this.canApplyOnline(session, player)) return CompletableFuture.completedFuture(SnapshotApplyResult.REJECTED);
        if (health != null && health.health() > 0 && player.isDead()) {
            return CompletableFuture.completedFuture(new SnapshotApplyResult.Failed("player did not respawn"));
        }
        SnapshotApplyResult applied = this.applyData(player, loaded);
        if (!(applied instanceof SnapshotApplyResult.Applied)) return CompletableFuture.completedFuture(applied);
        SnapshotApplyContext context = loaded.context();
        // 正生命值会让死亡玩家保持复活状态, 必须在重生完成后写入.
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
            teleport = player.teleportAsync(new Location(world, location.x(), location.y(), location.z(), location.yaw(), location.pitch()));
        }
        return teleport.thenApply(moved -> {
            if (!this.canApplyOnline(session, player)) return SnapshotApplyResult.REJECTED;
            if (!moved) return new SnapshotApplyResult.Failed("location teleport was rejected: " + location.world());
            if (location != null) {
                context.appliedPlayer(LocationDataType.LOCATION);
            }
            if (health != null && health.health() <= 0) {
                // 先完成传送和一般数据应用, 再触发原生死亡.
                if (!player.isDead()) {
                    player.setHealth(0);
                }
                context.appliedPlayer(HealthDataType.HEALTH);
            }
            if (!this.canApplyOnline(session, player)) return SnapshotApplyResult.REJECTED;
            session.retainedData(context.passthrough());
            SnapshotApplyResult.Applied result = new SnapshotApplyResult.Applied(context.applied(), context.skipped(), context.failures());
            EventUtils.fireAndForget(new SyncCompleteEvent(player, loaded.snapshot(), result.applied(), result.skipped()));
            return result;
        });
    }

    //  确认异步任务仍可操作最初选中的玩家会话.
    private boolean canApplyOnline(@NotNull PlayerSession session, @NotNull Player player) {
        synchronized (session) {
            // 同一 UUID 重新登录会建立新会话. 旧任务不能继续写入新的玩家状态.
            return player.isOnline() && this.plugin.sessionManager().find(session.uuid()) == session && session.state() == SessionState.ACTIVE;
        }
    }

    // 按注册顺序应用 Context 中待处理的类型, 并记录耗时.
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

    /**
     * 停止接受新的在线恢复.
     * 已经投递到玩家线程的任务仍通过会话检查决定是否继续.
     */
    public void stopOperations() {
        this.operationsClosed = true;
    }

    @NotNull
    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }
}
