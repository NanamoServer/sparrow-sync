package net.momirealms.sparrow.sync.session;

import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.event.PreApplyEvent;
import net.momirealms.sparrow.sync.event.SnapshotSaveEvent;
import net.momirealms.sparrow.sync.event.SyncCompleteEvent;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.sparrow.sync.session.operation.*;
import net.momirealms.sparrow.sync.snapshot.*;
import net.momirealms.sparrow.sync.snapshot.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionArchives;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
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

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SnapshotService {
    private final SparrowSync plugin;
    private SyncLogger logger;
    private DataRegistry dataRegistry;
    private PlayerDataPipeline playerDataPipeline;
    private PlayerSerialExecutor serialExecutor;
    private StorageProvider storage;
    private SnapshotWriter writer;
    private SnapshotFiles files;
    private ExceptionArchives exceptions;
    private SnapshotDetails details;
    private volatile boolean operationsClosed;
    private final Set<UUID> offlineRestores = ConcurrentHashMap.newKeySet(); // 持有离线恢复任务的玩家, 供登录和交接查询
    private final ConcurrentHashMap<UUID, Long> lastTimestampByPlayer = new ConcurrentHashMap<>();
    private final SnapshotHandoffTracker handoffs = new SnapshotHandoffTracker();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> mapSaves = new ConcurrentHashMap<>(); // 同一玩家最后提交的等待地图完成的保存任务, 用于衔接异步准备结果
    private final ConcurrentHashMap<SaveRequest, PendingMapSnapshot> pendingMapSnapshots = new ConcurrentHashMap<>(); // 尚未交给写入器的原快照, 关服超时后可落入 stash

    public SnapshotService(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
        this.logger = this.plugin.logger();
        this.dataRegistry = this.plugin.dataRegistry();
        this.playerDataPipeline = this.plugin.playerDataPipeline();
        this.serialExecutor = this.plugin.playerExecutor();
        this.storage = this.plugin.storageProvider();
        this.files = new SnapshotFiles(this.plugin.dataFolderPath(), this.plugin.binaryCodec());
        this.exceptions = new ExceptionArchives(this.files, this.plugin.scheduler().async());
        this.details = new SnapshotDetails(this.storage, this.files, this.exceptions, this.dataRegistry, this.plugin.scheduler().async());
        this.writer = new SnapshotWriter(this.logger, this.storage, this.plugin.snapshotStash(), this.serialExecutor);
    }

    /**
     * 读取玩家最新快照, 等 NMS 地图数据就绪后异步预解码玩家数据.
     * 任意线程可调用, 关键数据无法解码时返回失败结果.
     */
    @NotNull
    CompletableFuture<SnapshotLoadResult> loadLatest(@NotNull UUID player, @NotNull String playerName) {
        long loadStart = System.nanoTime();
        return this.storage.latestSnapshot(player)
                .thenCompose(latest -> latest
                        .map(snapshot -> this.prepareSnapshot(snapshot, playerName, loadStart))
                        .orElseGet(() -> {
                            this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_EMPTY, playerName, millis(loadStart, System.nanoTime()));
                            return CompletableFuture.completedFuture(new SnapshotLoadResult.Empty());
                        }))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        this.logger.error(LogCategory.APPLY, player, playerName, throwable, LogConstants.SYNC_LOAD_FAILED, playerName, millis(loadStart, System.nanoTime()), String.valueOf(throwable));
                    }
                });
    }

    // 指定历史快照的恢复复用地图准备和数据预解码, 不查询 latest.
    private CompletableFuture<SnapshotLoadResult> prepareSnapshot(Snapshot snapshot, String playerName) {
        return this.prepareSnapshot(snapshot, playerName, System.nanoTime());
    }

    private CompletableFuture<SnapshotLoadResult> prepareSnapshot(Snapshot snapshot, String playerName, long started) {
        // Pipeline 解码本服可用内容, 加载结果继续携带原始快照供事件和 RESTORE 保存使用.
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

    /** 在 Gate 阶段把远端快照写入原版登录数据源. */
    @NotNull
    Optional<CompoundTag> applyNative(@NotNull PlayerSession session, @NotNull Optional<CompoundTag> localData, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.playerDataPipeline.applyNative(session, localData, loaded.context());
    }

    // Join 消费登录准备留下的 pending 数据和 Native 交接回调.
    @NotNull
    SnapshotApplyResult applyOnJoin(@NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.applyData(player, loaded);
    }

    // 在线应用只处理已有快照, Future 完成后由 restore 决定是否保存新记录.
    private CompletableFuture<SnapshotApplyResult> applyOnline(Player player, Snapshot snapshot) {
        PlayerSession session = this.plugin.sessionManager().find(player.getUniqueId());
        if (this.operationsClosed || session == null || session.state() != SessionState.ACTIVE) {
            return CompletableFuture.completedFuture(new SnapshotApplyResult.Rejected());
        }
        return this.prepareSnapshot(snapshot, session.playerName()).thenCompose(loaded -> {
            if (!(loaded instanceof SnapshotLoadResult.Ready ready)) {
                return CompletableFuture.completedFuture(new SnapshotApplyResult.Failed("snapshot preparation failed"));
            }
            CompletableFuture<SnapshotApplyResult> completion = new CompletableFuture<>();
            Runnable apply = () -> {
                try {
                    this.applyOnlineNow(session, player, ready).whenComplete((result, failure) -> {
                        if (failure != null) completion.completeExceptionally(failure);
                        else completion.complete(result);
                    });
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            };
            // 死亡移出世界后连接仍归当前区域, Paper 的实体包装层会通过 retired 回调交付.
            Runnable retired = () -> {
                if (player.isConnected() && player.isDead()) apply.run();
                else completion.complete(new SnapshotApplyResult.Rejected());
            };
            if (this.plugin.scheduler().entity().run(player, apply, retired) == null) {
                completion.complete(new SnapshotApplyResult.Rejected());
            }
            return completion;
        });
    }

    // 事件与原生重生前后检查同一会话, 数据应用从重生完成后的玩家状态开始.
    private CompletableFuture<SnapshotApplyResult> applyOnlineNow(PlayerSession session, Player player, SnapshotLoadResult.Ready loaded) {
        if (!this.canApplyOnline(session, player)) return CompletableFuture.completedFuture(new SnapshotApplyResult.Rejected());
        PluginConfig.OnlineRestoreOptions options = PluginConfig.synchronization$onlineRestore();
        PreApplyEvent event = new PreApplyEvent(player, loaded.snapshot(), loaded.context().pendingValues());
        if (!options.syncHealth()) event.decoded().remove(HealthDataType.HEALTH);
        if (!options.syncLocation()) event.decoded().remove(LocationDataType.LOCATION);
        EventUtils.fireAndForget(event);
        if (!this.canApplyOnline(session, player)) return CompletableFuture.completedFuture(new SnapshotApplyResult.Rejected());
        SnapshotApplyContext context = loaded.context();
        context.acceptEventValues(event.decoded());
        HealthDataType.Health health = (HealthDataType.Health) context.takePending(HealthDataType.HEALTH);
        LocationDataType.PlayerLocation location = (LocationDataType.PlayerLocation) context.takePending(LocationDataType.LOCATION);
        CompletableFuture<Void> respawn = health != null && health.health() > 0 && player.isDead()
                ? this.respawn(player) : CompletableFuture.completedFuture(null);
        return respawn.thenCompose(ignored -> this.applyOnlineData(session, player, loaded, health, location));
    }

    // 原生重生负责世界登记、客户端和事件; Folia 的完成回调位于目标玩家区域.
    private CompletableFuture<Void> respawn(Player player) {
        if (!VersionHelper.isFolia()) {
            player.spigot().respawn();
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ServerPlayerProxy.INSTANCE.respawn(((CraftPlayer) player).getHandle(), ignored -> completion.complete(null), PlayerRespawnEvent.RespawnReason.PLUGIN);
        return completion;
    }

    // 正血量先于位置应用, 零血量在其余数据和传送完成后触发原生死亡.
    @SuppressWarnings("unchecked")
    private CompletableFuture<SnapshotApplyResult> applyOnlineData(PlayerSession session, Player player, SnapshotLoadResult.Ready loaded, @Nullable HealthDataType.Health health, @Nullable LocationDataType.PlayerLocation location) {
        if (!this.canApplyOnline(session, player)) return CompletableFuture.completedFuture(new SnapshotApplyResult.Rejected());
        if (health != null && health.health() > 0 && player.isDead()) {
            return CompletableFuture.completedFuture(new SnapshotApplyResult.Failed("player did not respawn"));
        }
        SnapshotApplyResult applied = this.applyData(player, loaded);
        if (!(applied instanceof SnapshotApplyResult.Applied)) return CompletableFuture.completedFuture(applied);
        SnapshotApplyContext context = loaded.context();
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
            if (!this.canApplyOnline(session, player)) return new SnapshotApplyResult.Rejected();
            if (!moved) return new SnapshotApplyResult.Failed("location teleport was rejected: " + location.world());
            if (location != null) context.appliedPlayer(LocationDataType.LOCATION);
            if (health != null && health.health() <= 0) {
                if (!player.isDead()) player.setHealth(0);
                context.appliedPlayer(HealthDataType.HEALTH);
            }
            if (!this.canApplyOnline(session, player)) return new SnapshotApplyResult.Rejected();
            session.retainedData(context.passthrough());
            SnapshotApplyResult.Applied result = new SnapshotApplyResult.Applied(context.applied(), context.skipped(), context.failures());
            EventUtils.fireAndForget(new SyncCompleteEvent(player, loaded.snapshot(), result.applied(), result.skipped()));
            return result;
        });
    }

    // 相同 UUID 重新登录后会产生新会话, 旧的异步应用不能继续写入.
    private boolean canApplyOnline(PlayerSession session, Player player) {
        synchronized (session) {
            return player.isOnline() && this.plugin.sessionManager().find(session.uuid()) == session && session.state() == SessionState.ACTIVE;
        }
    }

    // 在玩家线程应用普通数据类型, Join 与在线应用共用数据管线和日志.
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

    /** 在玩家线程立即采集 ACTIVE 玩家的当前状态, Future 等待保存结果. */
    @NotNull
    public CompletableFuture<SnapshotCaptureResult> capture(@NotNull Player player) {
        if (this.operationsClosed) return CompletableFuture.completedFuture(new SnapshotCaptureResult.Offline());
        CompletableFuture<SnapshotCaptureResult> result = new CompletableFuture<>();
        Runnable capture = () -> {
            try {
                PlayerSession session = this.plugin.sessionManager().find(player.getUniqueId());
                if (!player.isOnline() || session == null || session.state() != SessionState.ACTIVE) {
                    result.complete(new SnapshotCaptureResult.Offline());
                    return;
                }
                CompletableFuture<SnapshotSaveResult> saved = this.plugin.sessionManager().captureNowAndSave(session, player, SaveCause.COMMAND);
                if (saved == null) {
                    result.complete(new SnapshotCaptureResult.Offline());
                    return;
                }
                saved.whenComplete((outcome, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(switch (outcome) {
                            case SnapshotSaveResult.Cancelled ignored -> new SnapshotCaptureResult.Cancelled();
                            case SnapshotSaveResult.Settled settled -> settled.result().stored()
                                    ? new SnapshotCaptureResult.Captured(settled.id()) : new SnapshotCaptureResult.Failed();
                        });
                    }
                });
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        };
        if (this.plugin.scheduler().entity().isOwnedByCurrentRegion(player)) {
            capture.run();
        } else if (this.plugin.scheduler().entity().run(player, capture, () -> result.complete(new SnapshotCaptureResult.Offline())) == null) {
            result.complete(new SnapshotCaptureResult.Offline());
        }
        return result;
    }

    // 在当前玩家线程立即采集玩家状态, 地图处理、编码与保存进入玩家串行线程.
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureNowAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        SaveRequest request = new SaveRequest();
        SaveContext context = this.newContext(player, cause, retainedData);
        if (!(this.playerDataPipeline.capture(player, CaptureMode.SYNC) instanceof PlayerDataPipeline.CaptureResult.Ready captured)) {
            request.fail(new IllegalStateException("critical data of " + player.getName() + " could not be captured"));
            return request.completion;
        }
        this.submitSerial(context.meta().player(), () -> this.encodeAndSubmit(context, captured, request), request);
        return request.completion;
    }

    // 先在玩家线程完成对应类型的采集, 再由串行线程补齐其余类型并处理地图、编码保存.
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureLaterAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        SaveRequest request = new SaveRequest();
        SaveContext context = this.newContext(player, cause, retainedData);
        if (!(this.playerDataPipeline.capture(player, CaptureMode.ASYNC) instanceof PlayerDataPipeline.CaptureResult.Pending pending)) {
            request.fail(new IllegalStateException("critical data of " + context.playerName() + " could not be captured"));
            return request.completion;
        }
        this.submitSerial(context.meta().player(), () -> {
            if (!(this.playerDataPipeline.captureAsync(player, pending) instanceof PlayerDataPipeline.CaptureResult.Ready captured)) {
                request.fail(new IllegalStateException("critical data of " + context.playerName() + " could not be captured"));
                return;
            }
            this.encodeAndSubmit(context, captured, request);
        }, request);
        return request.completion;
    }

    // 将已退出玩家交给离线串行任务, 物品采集与地图处理都在该任务内完成.
    @NotNull
    CompletableFuture<SnapshotSaveResult> captureOfflineAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        SaveRequest request = new SaveRequest();
        SaveContext context = this.newContext(player, cause, retainedData);
        this.submitSerial(context.meta().player(), () -> {
            if (!(this.playerDataPipeline.capture(player, CaptureMode.OFFLINE) instanceof PlayerDataPipeline.CaptureResult.Ready captured)) {
                request.fail(new IllegalStateException("critical data of " + context.playerName() + " could not be captured"));
                return;
            }
            this.encodeAndSubmit(context, captured, request);
        }, request);
        return request.completion;
    }

    @NotNull
    private SaveContext newContext(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        // 请求接受时分配逻辑时间戳, 同一玩家在并发调用下仍严格递增.
        Long ts = this.lastTimestampByPlayer.merge(player.getUniqueId(), System.currentTimeMillis(), (last, now) -> Math.max(now, last + 1));
        SnapshotMeta snapshotMeta = SnapshotMeta.builder()
                .player(player.getUniqueId())
                .timestamp(ts)
                .cause(cause)
                .server(ServerConfig.serverId())
                .mcDataVersion(VersionHelper.WORLD_VERSION)
                .build();
        // 重载只影响随后开始的保存, 排队中的任务继续使用接受时的地图模式.
        MapType mapType = this.playerDataPipeline.mapMode();
        return new SaveContext(snapshotMeta, player.getName(), retainedData, mapType);
    }

    // 编码独立玩家数据, 等待地图物品编码后按同一玩家的保存顺序提交快照.
    private void encodeAndSubmit(SaveContext context, PlayerDataPipeline.CaptureResult.Ready captured, SaveRequest request) {
        if (!(this.playerDataPipeline.encode(captured) instanceof PlayerDataPipeline.EncodeResult.Ready encoded)) {
            request.fail(new IllegalStateException("critical data of " + context.playerName() + " could not be encoded"));
            return;
        }
        Snapshot snapshot = new Snapshot(context.meta(), mergeData(context.retainedData(), encoded.data()));
        // 地图等待闭包只持有耗时值, 采集对象的生命周期在本次编码任务内结束.
        long captureNanos = captured.captureNanos();
        if (context.mapType() == null) {
            this.writePrepared(context, snapshot, captureNanos, request);
            return;
        }
        CompletableFuture<Snapshot> prepared = this.playerDataPipeline.prepareForStorage(snapshot, context.mapType(), context.playerName());
        // 记录尚未交给写入器的原快照, 地图等待超出关服期限时仍有可暂存内容
        this.pendingMapSnapshots.put(request, new PendingMapSnapshot(snapshot, context.playerName()));
        // 地图准备允许并行, 同一玩家提交快照仍沿 encode 的先后顺序衔接, 不阻塞桶内其他玩家.
        UUID player = context.meta().player();
        CompletableFuture<Void> submitted = this.mapSaves.compute(player, (key, previous) -> {
            CompletableFuture<Void> tail = previous == null ? CompletableFuture.completedFuture(null) : previous.handle((value, failure) -> null);
            return tail.thenCompose(ignored -> prepared).thenAcceptAsync(value -> {
                if (request.completion.isDone()) return;
                this.writePrepared(context, value, captureNanos, request);
                this.pendingMapSnapshots.remove(request);
            }, this.serialExecutor.executor(player));
        });
        submitted.whenComplete((ignored, failure) -> {
            this.mapSaves.remove(player, submitted);
            if (failure != null) {
                request.fail(failure);
            }
        });
    }

    // 异常同时交给调用方 Future 与执行器的统一异常出口.
    private void submitSerial(UUID player, Runnable task, SaveRequest request) {
        try {
            this.serialExecutor.submit(player, () -> {
                try {
                    task.run();
                } catch (RuntimeException | Error throwable) {
                    request.fail(throwable);
                    throw throwable;
                }
            });
        } catch (RejectedExecutionException exception) {
            request.fail(exception);
        }
    }

    // 触发保存事件并把完整快照交给写入器, 成功交接后释放关服等待计数.
    private void writePrepared(SaveContext context, Snapshot snapshot, long captureNanos, SaveRequest request) {
        SnapshotSaveEvent event = new SnapshotSaveEvent(context.playerName(), snapshot, request.completion.minimalCompletionStage());
        if (EventUtils.fireAndCheckCancel(event)) {
            this.logger.file(LogCategory.SAVE, context.meta().player(), context.playerName(), LogConstants.SYNC_SAVE_CANCELLED_BY_EVENT, context.playerName(), context.meta().cause().name(), context.meta().id().toString());
            request.cancel();
            return;
        }
        // write 返回时首次存储任务已入队或快照已转交 stash, 最终 settle 继续走 completion
        this.writer.write(snapshot, context.playerName(), captureNanos, request.completion);
        request.handedOff();
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

    /** 读取指定历史快照并在线应用, 成功后保存新的 RESTORE 记录. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restore(@NotNull Player player, @NotNull UUID snapshotId) {
        if (this.operationsClosed) return CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
        return this.storage.snapshot(snapshotId).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(new SnapshotRestoreResult.NotFound());
            if (!found.get().meta().player().equals(player.getUniqueId())) return CompletableFuture.completedFuture(new SnapshotRestoreResult.WrongPlayer());
            return this.restore(player, found.get());
        });
    }

    // 在线应用完成后再提交新记录, 对外回执等待保存完成.
    private CompletableFuture<SnapshotRestoreResult> restore(Player player, Snapshot snapshot) {
        return this.applyOnline(player, snapshot).thenCompose(applied -> switch (applied) {
            case SnapshotApplyResult.Rejected ignored -> CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
            case SnapshotApplyResult.Failed ignored -> CompletableFuture.completedFuture(new SnapshotRestoreResult.Failed());
            case SnapshotApplyResult.Applied ignored -> this.saveRestored(snapshot, player.getName()).thenApply(saved -> switch (saved) {
                case SnapshotSaveResult.Cancelled cancelled -> new SnapshotRestoreResult.Cancelled();
                case SnapshotSaveResult.Settled settled -> settled.result().stored()
                        ? new SnapshotRestoreResult.Restored(settled.id()) : new SnapshotRestoreResult.Failed();
            });
        });
    }

    /** 持有玩家会话锁时写入 RESTORE 新记录, 供离线玩家下次登录加载. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restoreOffline(@NotNull PlayerIdentity player, @NotNull UUID snapshotId) {
        return this.storage.snapshot(snapshotId).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(new SnapshotRestoreResult.NotFound());
            if (!found.get().meta().player().equals(player.uuid())) return CompletableFuture.completedFuture(new SnapshotRestoreResult.WrongPlayer());
            if (this.operationsClosed || !this.offlineRestores.add(player.uuid())) return CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
            // 先登记在途写入, 持锁期间交接探测持续回答 SAVING.
            return CompletableFuture.completedFuture(null).thenCompose(ignored -> this.plugin.sessionLock().tryAcquire(player.uuid())).thenCompose(acquired -> {
                if (!(acquired instanceof SessionLock.AcquireOutcome.Acquired lock)) return CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
                return CompletableFuture.completedFuture(null).thenCompose(ignored -> this.saveRestored(found.get(), player.name()))
                        .handle((saved, failure) -> new OfflineSave(saved, failure))
                        .thenCompose(outcome -> this.plugin.sessionLock().release(player.uuid(), lock.value()).thenApply(ignored -> {
                            if (outcome.failure() != null) throw new CompletionException(outcome.failure());
                            return (SnapshotRestoreResult) switch (outcome.saved()) {
                                case SnapshotSaveResult.Cancelled cancelled -> new SnapshotRestoreResult.Cancelled();
                                case SnapshotSaveResult.Settled settled -> settled.result().stored()
                                        ? new SnapshotRestoreResult.RestoredOffline(settled.id()) : new SnapshotRestoreResult.Failed();
                            };
                        }));
            }).whenComplete((result, failure) -> this.offlineRestores.remove(player.uuid()));
        });
    }

    /** 将历史内容保存为一份新的 RESTORE 记录, 与普通保存共用逻辑时间及写入队列. */
    @NotNull
    private CompletableFuture<SnapshotSaveResult> saveRestored(@NotNull Snapshot source, @NotNull String playerName) {
        UUID player = source.meta().player();
        long now = Math.max(System.currentTimeMillis(), source.meta().timestamp() + 1);
        long timestamp = this.lastTimestampByPlayer.merge(player, now, (last, current) -> Math.max(current, last + 1));
        SnapshotMeta meta = SnapshotMeta.builder().player(player).timestamp(timestamp).cause(SaveCause.RESTORE)
                .server(ServerConfig.serverId()).mcDataVersion(source.meta().mcDataVersion()).build();
        Snapshot restored = new Snapshot(meta, source.data());
        SaveRequest request = new SaveRequest();
        SaveContext context = new SaveContext(meta, playerName, Map.of(), null);
        this.submitSerial(player, () -> this.writePrepared(context, restored, 0, request), request);
        return request.completion;
    }

    public boolean restoringOffline(@NotNull UUID player) {
        return this.offlineRestores.contains(player);
    }

    /** 固定指定快照, 已固定时返回 Unchanged. */
    @NotNull
    public CompletableFuture<SnapshotPinResult> pin(@NotNull UUID snapshotId) {
        return this.storage.setPinned(snapshotId, true).thenCompose(changed -> {
            if (changed) return CompletableFuture.completedFuture(new SnapshotPinResult.Pinned());
            return this.storage.snapshot(snapshotId).thenApply(current -> current.isEmpty() ? new SnapshotPinResult.NotFound() : new SnapshotPinResult.Unchanged());
        });
    }

    /** 取消固定指定快照, 未固定时返回 Unchanged. */
    @NotNull
    public CompletableFuture<SnapshotUnpinResult> unpin(@NotNull UUID snapshotId) {
        return this.storage.setPinned(snapshotId, false).thenCompose(changed -> {
            if (changed) return CompletableFuture.completedFuture(new SnapshotUnpinResult.Unpinned());
            return this.storage.snapshot(snapshotId).thenApply(current -> current.isEmpty() ? new SnapshotUnpinResult.NotFound() : new SnapshotUnpinResult.Unchanged());
        });
    }

    /** 删除指定快照, 已固定的快照也可删除. */
    @NotNull
    public CompletableFuture<SnapshotDeleteResult> delete(@NotNull UUID snapshotId) {
        return this.storage.deleteSnapshot(snapshotId).thenApply(deleted -> deleted ? new SnapshotDeleteResult.Deleted() : new SnapshotDeleteResult.NotFound());
    }

    /** 将指定快照导出到发送者对应的本服目录. */
    @NotNull
    public CompletableFuture<SnapshotExportResult> export(@NotNull UUID snapshotId, @NotNull SnapshotFiles.Format format, boolean playerSender) {
        return this.storage.snapshot(snapshotId).thenApplyAsync(found -> {
            if (found.isEmpty()) return new SnapshotExportResult.NotFound();
            Snapshot snapshot = found.get();
            try {
                return new SnapshotExportResult.Exported(snapshotId, this.files.export(snapshot, format, playerSender));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, this.plugin.scheduler().async());
    }

    /** 导入本地快照并保留原身份与时间, 相同 ID 的不同内容返回冲突. */
    @NotNull
    public CompletableFuture<SnapshotImportResult> importFile(@NotNull String relative) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.files.read(relative);
            } catch (IOException | IllegalArgumentException failure) {
                throw new CompletionException(failure);
            }
        }, this.plugin.scheduler().async()).thenCompose(decoded -> {
            if (!(decoded instanceof DecodedSnapshot.Valid valid)) return CompletableFuture.completedFuture(new SnapshotImportResult.InvalidFile());
            Snapshot snapshot = valid.snapshot();
            UUID snapshotId = snapshot.meta().id();
            return this.storage.snapshot(snapshotId).thenCompose(existing -> {
                if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get().equals(snapshot) ? new SnapshotImportResult.Unchanged(snapshotId) : new SnapshotImportResult.Conflict(snapshotId));
                // 导入保持原身份和时间, 直接入库, 自动轮转属于正常保存流程.
                return this.storage.saveSnapshot(snapshot).thenCompose(saved -> {
                    if (saved == StorageProvider.SaveResult.DUPLICATE) {
                        return this.storage.snapshot(snapshotId).thenApply(current -> current.filter(snapshot::equals).isPresent() ? new SnapshotImportResult.Unchanged(snapshotId) : new SnapshotImportResult.Conflict(snapshotId));
                    }
                    return CompletableFuture.completedFuture(saved.stored() ? new SnapshotImportResult.Imported(snapshotId) : new SnapshotImportResult.Failed());
                });
            });
        });
    }

    @NotNull
    public SnapshotFiles files() {
        return this.files;
    }

    @NotNull
    public ExceptionArchives exceptions() {
        return this.exceptions;
    }

    @NotNull
    public SnapshotDetails details() {
        return this.details;
    }

    /** 停止接受主动采集和恢复, 已接受的写入继续完成. */
    public void stopOperations() {
        this.operationsClosed = true;
    }

    // 停止接受新快照并等待已接受请求完成首次存储提交.
    public boolean sealAndAwaitHandoffs(long timeout, @NotNull TimeUnit unit) {
        return this.handoffs.sealAndAwait(timeout, unit);
    }

    /**
     * 将写入器和地图等待阶段尚未完成的快照留到本地 pending, 供下次启动继续保存.
     * <p>地图准备中的请求保存输入物品快照, 该快照尚未依赖未完成发布的负数引用.
     */
    public void stashUnsettled() {
        if (this.writer != null) this.writer.stashUnsettled();
        // 地图准备尚未交给 SnapshotWriter 时, 原始物品快照也必须进入本地 pending.
        for (var entry : this.pendingMapSnapshots.entrySet()) {
            if (!this.pendingMapSnapshots.remove(entry.getKey(), entry.getValue())) continue;
            PendingMapSnapshot pending = entry.getValue();
            this.plugin.snapshotStash().stash(pending.snapshot(), pending.playerName(), StorageProvider.SaveResult.RETRY_LATER);
            entry.getKey().handedOff();
            entry.getKey().completion.complete(new SnapshotSaveResult.Settled(StorageProvider.SaveResult.RETRY_LATER, pending.snapshot().meta().id()));
        }
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }

    private record OfflineSave(SnapshotSaveResult saved, Throwable failure) {
    }

    private record SaveContext(@NotNull SnapshotMeta meta, @NotNull String playerName, @NotNull Map<DataKey, Tag> retainedData, @Nullable MapType mapType) {
    }

    private record PendingMapSnapshot(Snapshot snapshot, String playerName) {
    }

    private final class SaveRequest {
        private final CompletableFuture<SnapshotSaveResult> completion = new CompletableFuture<>();
        private final AtomicBoolean handedOff = new AtomicBoolean();

        private SaveRequest() {
            SnapshotService.this.handoffs.accept();
        }

        private void handedOff() {
            if (this.handedOff.compareAndSet(false, true)) {
                SnapshotService.this.handoffs.handedOff();
            }
        }

        private void cancel() {
            this.handedOff();
            this.completion.complete(new SnapshotSaveResult.Cancelled());
        }

        private void fail(Throwable throwable) {
            this.handedOff();
            this.completion.completeExceptionally(throwable);
        }
    }
}
