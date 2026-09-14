package net.momirealms.sparrow.sync.map;

import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.MinecraftServer;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.cache.MapCache;
import net.momirealms.sparrow.sync.map.cache.RedisMapCache;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapArchiveRecord;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.handler.HideMapHandler;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.map.handler.SyncMapHandler;
import net.momirealms.sparrow.sync.map.message.MapInvalidationMessage;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.util.VersionHelper;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.network.NMSPacketEvent;
import net.momirealms.sparrow.ui.network.NMSPacketListener;
import net.momirealms.sparrow.ui.network.NetworkUser;
import net.momirealms.sparrow.ui.network.PacketFlow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class MapSyncService {
    private final SparrowSync plugin;
    private String ownerId;
    private NativeMapAdapter nativeMaps;
    private NativeMapStorage nativeStorage;
    private MapPublisher publisher;
    private MapReceiver receiver;
    private MapCache shared; // 同步与导入共用; 未启用同步时, 首次导入才创建
    private volatile MapPipeline pipeline; // 世界就绪后赋值; 未启用同步时为 null

    public MapSyncService(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    /** 主世界、注册表和数据库就绪后, 初始化地图同步并注册监听器. */
    public void onDelayedEnable() {
        PluginConfig.MapOptions options = PluginConfig.synchronization$map();
        if (!options.enabled()) return;
        MinecraftServer server = MinecraftServer.getServer();
        UUID worldUuid = server.overworld().getWorld().getUID();
        this.ownerId = options.resolveOwnerId(ServerConfig.serverId(), worldUuid);
        this.nativeMaps = new NativeMapAdapter(server.registryAccess(), VersionHelper.WORLD_VERSION);
        this.nativeStorage = new NativeMapStorage(server, VersionHelper.WORLD_VERSION);
        MapStorage storage = this.plugin.storageProvider().maps();
        MapCache shared = new RedisMapCache(this.plugin.redisConnector().connection().async(), this.plugin.messageBrokerManager().broker(), this.plugin.scheduler().async());
        this.shared = shared;
        this.publisher = new MapPublisher(storage, shared, this.ownerId, this.plugin.scheduler().async());
        this.receiver = new MapReceiver(storage, shared, this.nativeMaps, server, this.ownerId, this.plugin.logger());
        this.pipeline = new MapPipeline(this.plugin.dataRegistry(), List.of(new HideMapHandler(), new SyncMapHandler(this.receiver)), this.plugin.logger());
        new MapInteractionListener().register(this.plugin.javaPlugin());
        // 通过原版地图包发现正在使用的地图, 由 receiver 登记负数 ID.
        SparrowUI.getInstance().networkManager().registerNMSPacketListener(new NMSPacketListener() {
            @Override
            public void onPacketSend(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
                MapSyncService.this.observe(((ClientboundMapItemDataPacket) packet).mapId().id());
            }
        }, ClientboundMapItemDataPacket.class, PacketFlow.CLIENTBOUND);
        MapInvalidationMessage.listener(this::invalidate);
    }

    @Nullable
    public MapType mode() {
        return this.pipeline == null ? null : PluginConfig.synchronization$map().synchronization_mode();
    }

    /** 从物品中找出来源地图, 按需采集、发布并生成传输快照. */
    @NotNull
    public CompletableFuture<Snapshot> compileAsync(@NotNull Snapshot snapshot, @NotNull MapType mode, @NotNull String playerName) {
        MapPipeline pipeline = this.pipeline;
        if (pipeline == null) return CompletableFuture.completedFuture(snapshot);
        try {
            return pipeline.encodeAsync(snapshot, mode, this.ownerId, this::captureAndPublish);
        } catch (RuntimeException exception) {
            this.plugin.logger().warnWithFileCause(LogCategory.DATA, snapshot.meta().player(), playerName, exception, LogConstants.DATA_MAP_COMPILE_FAILED, playerName, snapshot.meta().id().toString(), String.valueOf(exception.getMessage()));
            return CompletableFuture.completedFuture(snapshot);
        }
    }

    /** 准备本服地图和物品 ID, 返回可用于玩家数据解码的快照. */
    @NotNull
    public CompletableFuture<Snapshot> decodeAsync(@NotNull Snapshot snapshot) {
        MapPipeline pipeline = this.pipeline;
        if (pipeline == null) return CompletableFuture.completedFuture(snapshot);
        try {
            return pipeline.decodeAsync(snapshot, this.ownerId);
        } catch (RuntimeException exception) {
            this.plugin.logger().warnWithFileCause(LogCategory.DATA, snapshot.meta().player(), null, exception, LogConstants.DATA_MAP_DECODE_FAILED, snapshot.meta().player().toString(), snapshot.meta().id().toString(), String.valueOf(exception.getMessage()));
            return CompletableFuture.completedFuture(snapshot);
        }
    }

    // 在玩家串行线程中采集物品引用的来源地图, 同次保存由管线按 ID 去重.
    private CompletableFuture<StoredMap> captureAndPublish(int nativeId) {
        try {
            MapSource source = new MapSource(this.ownerId, nativeId);
            MapData data = this.nativeMaps.capture(this.nativeStorage, nativeId);
            if (data == null) {
                throw new IllegalStateException("source map does not exist: " + source);
            }
            return this.publisher.publish(source, data);
        } catch (IOException | RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public void observe(int globalId) {
        this.receiver.observe(globalId);
    }

    public void invalidate(int globalId) {
        this.receiver.refresh(globalId);
    }

    /**
     * 地图导入数据库后, 覆盖 Redis 缓存并通知其他服务器刷新.
     *
     * @return 缓存写入、广播发送和本服刷新请求提交完成的结果; 地图副本随后异步刷新
     */
    @NotNull
    public CompletableFuture<Void> importedMap(@NotNull MapArchiveRecord record) {
        try {
            StoredMap map = new StoredMap(record.identity(), new MapData(record.dataVersion(), NBT.fromBytes(record.data())));
            // 数据库已被导入内容覆盖, 清除本服用于比较的旧发布记录.
            if (this.publisher != null) this.publisher.invalidate(record.identity().source());
            MapCache cache = this.shared;
            if (cache == null) {
                // 未启用地图同步时, 导入仍需更新共享缓存.
                cache = new RedisMapCache(this.plugin.redisConnector().connection().async(), this.plugin.messageBrokerManager().broker(), this.plugin.scheduler().async());
                this.shared = cache;
            }
            return cache.publish(map).thenRun(() -> {
                // 缓存写入和广播完成后, 再请求本服刷新.
                if (this.receiver != null) this.receiver.refresh(record.identity().globalId());
            });
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    // 停止接收和更新副本, 保留来源发布功能供关服最终保存使用.
    public void stopReceiving() {
        MapInvalidationMessage.listener(null);
        if (this.receiver != null) this.receiver.close();
    }

    // 限时等待已采集的地图发布完成, 然后关闭服务.
    public void finishPublishing(long timeout, @NotNull TimeUnit unit) {
        if (this.publisher == null) return;
        if (!this.publisher.sealAndAwait(timeout, unit)) {
            this.plugin.logger().warn(LogCategory.DATA, LogConstants.DATA_MAP_PUBLISH_UNFINISHED, this.ownerId);
        }
        this.close();
    }

    // 释放待处理的物品快照并停止服务, 保留数据库映射和本服地图副本.
    public void close() {
        if (this.pipeline != null) this.pipeline.close();
        this.stopReceiving();
        if (this.publisher != null) this.publisher.close();
    }
}
