package net.momirealms.sparrow.sync.map;

import net.minecraft.server.MinecraftServer;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.cache.MapCache;
import net.momirealms.sparrow.sync.map.cache.RedisMapCache;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.map.handler.HideMapHandler;
import net.momirealms.sparrow.sync.map.handler.SyncMapHandler;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class MapSyncService {
    private final SparrowSync plugin;
    private final String ownerId;
    private final NativeMapAdapter nativeMaps;
    private final NativeMapStorage nativeStorage;
    private final MapPublisher publisher;
    private final MapReceiver receiver;
    private final MapPipeline pipeline;

    public MapSyncService(@NotNull SparrowSync plugin, @NotNull String ownerId) {
        this.plugin = plugin;
        this.ownerId = ownerId;
        MinecraftServer server = MinecraftServer.getServer();
        this.nativeMaps = new NativeMapAdapter(server.registryAccess(), VersionHelper.WORLD_VERSION);
        this.nativeStorage = new NativeMapStorage(server, VersionHelper.WORLD_VERSION);
        MapStorage storage = plugin.storageProvider().maps();
        MapCache shared = new RedisMapCache(plugin.redisConnector().connection().async(), plugin.messageBrokerManager().broker(), plugin.scheduler().async());
        this.publisher = new MapPublisher(storage, shared, ownerId, plugin.scheduler().async());
        this.receiver = new MapReceiver(storage, shared, this.nativeMaps, server, ownerId, plugin.logger());
        this.pipeline = new MapPipeline(plugin.dataRegistry(), List.of(new HideMapHandler(), new SyncMapHandler(this.receiver)), plugin.logger());
    }

    /** 从已编码物品中发现来源地图, 按需采集发布后生成传输快照. */
    @NotNull
    public CompletableFuture<Snapshot> compileAsync(@NotNull Snapshot snapshot, @NotNull MapType mode) {
        return this.pipeline.encodeAsync(snapshot, mode, this.ownerId, this::captureAndPublish);
    }

    /** 更新本服地图数据并选择物品 ID, 返回供玩家数据解码使用的快照. */
    @NotNull
    public CompletableFuture<Snapshot> decodeAsync(@NotNull Snapshot snapshot) {
        return this.pipeline.decodeAsync(snapshot, this.ownerId);
    }

    // 在玩家串行线程中采集编码后发现的来源地图, 单次保存的 ID 去重由物品管线负责.
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

    // 停止地图副本数据的拉取与更新, 来源发布保留到关服最终保存结束.
    public void stopReceiving() {
        this.receiver.close();
    }

    // 限时等待已采集的来源地图数据完成发布, 随后关闭本服务.
    public void finishPublishing(long timeout, @NotNull TimeUnit unit) {
        if (!this.publisher.sealAndAwait(timeout, unit)) {
            this.plugin.logger().warn(LogCategory.DATA, LogConstants.DATA_MAP_PUBLISH_UNFINISHED, this.ownerId);
        }
        this.close();
    }

    // 释放待编码的物品快照并停止服务后续工作, 数据库映射和本服地图副本继续留存
    public void close() {
        this.pipeline.close();
        this.stopReceiving();
        this.publisher.close();
    }
}
