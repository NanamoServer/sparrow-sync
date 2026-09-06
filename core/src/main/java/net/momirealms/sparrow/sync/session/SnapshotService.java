package net.momirealms.sparrow.sync.session;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.event.SnapshotSaveEvent;
import net.momirealms.sparrow.sync.map.message.MapInvalidationMessage;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.*;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplyContext;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.util.EventUtils;
import net.momirealms.sparrow.sync.util.VersionHelper;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.network.NMSPacketListener;
import net.momirealms.sparrow.ui.network.NMSPacketEvent;
import net.momirealms.sparrow.ui.network.NetworkUser;
import net.momirealms.sparrow.ui.network.PacketFlow;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SnapshotService {
    private final SparrowSync plugin;
    private SyncLogger logger;
    private DataRegistry dataRegistry;
    private PlayerDataPipeline playerDataPipeline;
    private volatile MapSyncService mapSync;
    private volatile boolean closingMaps;
    private PlayerSerialExecutor serialExecutor;
    private StorageProvider storage;
    private SnapshotWriter writer;
    private final ConcurrentHashMap<UUID, Long> lastTimestampByPlayer = new ConcurrentHashMap<>();
    private final SnapshotHandoffTracker handoffs = new SnapshotHandoffTracker();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> mapSaves = new ConcurrentHashMap<>(); // 同一玩家等待地图后的提交尾任务, 用于衔接异步准备结果
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
        this.writer = new SnapshotWriter(this.logger, this.storage, this.plugin.snapshotStash(), this.serialExecutor);
    }

    public void onDelayedEnable() {
        // 冻结数据类型注册表并记录最终装配顺序.
        this.dataRegistry.freeze();
        StringJoiner activeTypes = new StringJoiner(", ");
        List<DataKey> applyOrder = this.playerDataPipeline.applyOrder();
        int dataTypeCount = applyOrder.size();
        for (int i = 0; i < dataTypeCount; i++) {
            activeTypes.add(applyOrder.get(i).asString());
        }
        this.logger.info(TranslationManager.console(LogConstants.PLUGIN_REGISTRY_FROZEN, String.valueOf(dataTypeCount), activeTypes.toString()));

        // 主世界和数据库就绪后, 在登录入口开放前创建地图服务并注册观察入口.
        PluginConfig.MapOptions options = PluginConfig.synchronization$map();
        if (!options.enabled()) return;
        UUID worldUuid = MinecraftServer.getServer().overworld().getWorld().getUID();
        String ownerId = options.resolveOwnerId(ServerConfig.serverId(), worldUuid);
        MapSyncService maps = new MapSyncService(this.plugin, ownerId);
        this.mapSync = maps;

        // 观察原版地图更新包发现负数 ID, 就将其计入服务器观测的地图ID, 包本身继续沿原版发送路径处理.
        SparrowUI.getInstance().networkManager().registerNMSPacketListener(new NMSPacketListener() {
            @Override
            public void onPacketSend(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
                if (!SnapshotService.this.closingMaps) {
                    maps.observe(((ClientboundMapItemDataPacket) packet).mapId().id());
                }
            }
        }, ClientboundMapItemDataPacket.class, PacketFlow.CLIENTBOUND);
        MapInvalidationMessage.listener(id -> {if (!this.closingMaps) maps.invalidate(id);});
    }

    /** 把预解码数据应用到玩家, <strong>必须在玩家线程上调用</strong>. */
    @NotNull
    SnapshotApplyResult apply(@NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
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

    /** 在 Gate 阶段把远端快照写入原版登录数据源. */
    @NotNull
    Optional<CompoundTag> applyNative(@NotNull PlayerSession session, @NotNull Optional<CompoundTag> localData, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.playerDataPipeline.applyNative(session, localData, loaded.context());
    }

    /**
     * 读取玩家最新快照, 等地图原生数据就绪后异步预解码玩家数据.
     * 任意线程可调用, 关键数据无法解码时返回失败结果.
     */
    @NotNull
    CompletableFuture<SnapshotLoadResult> loadLatest(@NotNull UUID player, @NotNull String playerName) {
        long loadStart = System.nanoTime();
        return this.storage.latestSnapshot(player)
                .thenCompose(latest -> latest
                        .map(snapshot -> this.decodeMaps(snapshot).thenApplyAsync(mapDecoded -> this.decode(snapshot, mapDecoded, player, playerName, loadStart), this.plugin.scheduler().async()))
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

    // 与物品编码在同一玩家串行任务中执行, 不重新读取玩家或回到玩家线程.
    @Nullable
    private MapSyncService.Capture captureAndPublishMaps(PlayerDataPipeline.CaptureResult.Ready captured, SaveContext context) {
        MapSyncService maps = this.mapSync;
        if (maps == null || context.mapType() == null) return null;
        try {
            return maps.captureAndPublish(captured, context.mapType());
        } catch (RuntimeException exception) {
            this.logger.warnWithFileCause(LogCategory.DATA, context.meta().player(), context.playerName(), exception, LogConstants.DATA_MAP_COMPILE_FAILED, context.playerName(), context.meta().id().toString(), String.valueOf(exception.getMessage()));
            return null;
        }
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

    // 在玩家线程采集同步组, 再由玩家串行线程补齐异步组并处理地图、编码保存.
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

    @NotNull
    private SaveContext newContext(@NotNull Player player, @NotNull SaveCause cause, @NotNull Map<DataKey, Tag> retainedData) {
        // 请求接纳时分配逻辑时间戳, 同一玩家在并发调用下仍严格递增.
        Long ts = this.lastTimestampByPlayer.merge(player.getUniqueId(), System.currentTimeMillis(), (last, now) -> Math.max(now, last + 1));
        SnapshotMeta snapshotMeta = SnapshotMeta.builder()
                .player(player.getUniqueId())
                .timestamp(ts)
                .cause(cause)
                .server(ServerConfig.serverId())
                .mcDataVersion(VersionHelper.WORLD_VERSION)
                .build();
        // 重载只影响随后开始的保存, 排队中的任务继续使用接纳时的地图模式.
        MapType mapType = this.mapSync == null ? null : PluginConfig.synchronization$map().type();
        return new SaveContext(snapshotMeta, player.getName(), retainedData, mapType);
    }

    // 编码独立玩家数据, 等待地图编译后按同一玩家的保存顺序提交快照.
    private void encodeAndSubmit(SaveContext context, PlayerDataPipeline.CaptureResult.Ready captured, SaveRequest request) {
        MapSyncService.Capture maps = this.captureAndPublishMaps(captured, context);
        if (!(this.playerDataPipeline.encode(captured) instanceof PlayerDataPipeline.EncodeResult.Ready encoded)) {
            request.fail(new IllegalStateException("critical data of " + context.playerName() + " could not be encoded"));
            return;
        }
        Snapshot snapshot = new Snapshot(context.meta(), mergeData(context.retainedData(), encoded.data()));
        // 地图等待闭包只持有耗时值, 采集对象的生命周期在本次编码任务内结束.
        long captureNanos = captured.captureNanos();
        if (maps == null) {
            this.writePrepared(context, snapshot, captureNanos, request);
            return;
        }
        CompletableFuture<Snapshot> prepared = this.mapSync.compileAsync(snapshot, maps);
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

    // 将地图处理后的快照交给玩家数据解码, 保留原快照供后续流程使用.
    private SnapshotLoadResult decode(Snapshot snapshot, Snapshot mapDecoded, UUID player, String playerName, long loadStart) {
        return switch (this.playerDataPipeline.decode(mapDecoded)) {
            case PlayerDataPipeline.DecodeResult.Ready ready -> {
                long loadNanos = System.nanoTime() - loadStart;
                this.logger.file(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_READY, playerName, snapshot.meta().id().toString(), millis(0, loadNanos));
                yield new SnapshotLoadResult.Ready(snapshot, ready.context(), loadNanos);
            }
            case PlayerDataPipeline.DecodeResult.Failed failed -> {
                String detail = failed.key().asString() + ": " + failed.detail();
                this.logger.error(LogCategory.APPLY, player, playerName, LogConstants.SYNC_LOAD_FAILED, playerName, millis(loadStart, System.nanoTime()), detail);
                yield new SnapshotLoadResult.Failed(detail);
            }
        };
    }

    // 在玩家数据解码前更新地图并选择物品 ID, 启动时未启用地图服务则返回原快照.
    private CompletableFuture<Snapshot> decodeMaps(Snapshot snapshot) {
        MapSyncService maps = this.mapSync;
        if (maps == null) return CompletableFuture.completedFuture(snapshot);
        try {
            return maps.decodeAsync(snapshot);
        } catch (RuntimeException exception) {
            this.logger.warnWithFileCause(LogCategory.DATA, snapshot.meta().player(), null, exception, LogConstants.DATA_MAP_DECODE_FAILED, snapshot.meta().player().toString(), snapshot.meta().id().toString(), String.valueOf(exception.getMessage()));
            return CompletableFuture.completedFuture(snapshot);
        }
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
            entry.getKey().completion.complete(new SnapshotSaveResult.Settled(StorageProvider.SaveResult.RETRY_LATER));
        }
    }

    // 停止接纳新快照并等待已接纳请求完成首次存储提交.
    public boolean sealAndAwaitHandoffs(long timeout, @NotNull TimeUnit unit) {
        return this.handoffs.sealAndAwait(timeout, unit);
    }

    // 停止地图通知和接收更新, 为关服最终保存保留来源发布入口.
    public synchronized void stopMapReceiving() {
        this.closingMaps = true;
        MapInvalidationMessage.listener(null);
        if (this.mapSync != null) {
            this.mapSync.stopReceiving();
        }
    }

    // 在玩家采集交接后排空地图发布, 与其余关服步骤共用剩余期限.
    public void finishMapPublishing(long timeout, @NotNull TimeUnit unit) {
        MapSyncService maps = this.mapSync;
        if (maps != null && !maps.closed()) {
            maps.finishPublishing(timeout, unit);
        }
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
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
