package net.momirealms.sparrow.sync.map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.cache.MapCache;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class MapReceiver {
    private final MapStorage storage;
    private final MapCache redisCache;
    private final NativeMapAdapter nativeMaps;
    private final MinecraftServer server;
    private final String ownerId;
    private final SyncLogger logger;
    private final ConcurrentHashMap<Integer, Tracked> tracked = new ConcurrentHashMap<>(); // 持续接收更新的全局地图 ID, 保留到服务关闭
    private final Map<Integer, Flight> receiveTasks = new HashMap<>(); // 同一地图共用接收任务; 锁同时保护失效状态和本服更新
    private final Cache<Integer, StoredMap> localCache = Caffeine.newBuilder().maximumSize(1024).expireAfterWrite(Duration.ofMinutes(5)).build(); // 最多缓存 1024 张, 写入 5 分钟后过期, 读取不续期
    private volatile boolean closed;

    public MapReceiver(@NotNull MapStorage storage, @NotNull MapCache redisCache, @NotNull NativeMapAdapter nativeMaps, @NotNull MinecraftServer server, @NotNull String ownerId, @NotNull SyncLogger logger) {
        this.storage = storage;
        this.redisCache = redisCache;
        this.nativeMaps = nativeMaps;
        this.server = server;
        this.ownerId = ownerId;
        this.logger = logger;
    }

    /**
     * 等待地图在本服就绪, 同一地图的并发请求共用结果, 最多等待 5 秒.
     * <strong>物品携带的来源必须与存储记录一致</strong>.
     *
     * @return 副本的负数 ID, 或回到来源服后找到的原地图 ID; 失败时异常完成
     */
    @NotNull
    public CompletableFuture<Integer> receive(@NotNull MapIdentity identity) {
        return this.receive(identity.globalId(), identity);
    }

    // 按全局 ID 读取来源和内容, 更新本服副本.
    @NotNull
    CompletableFuture<Integer> receive(int globalId) {
        return this.receive(globalId, null);
    }

    @NotNull
    private CompletableFuture<Integer> receive(int globalId, @Nullable MapIdentity expectedIdentity) {
        synchronized (this.receiveTasks) {
            if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map receiver is closed"));
            Flight flight = this.receiveTasks.get(globalId);
            if (flight == null) {
                flight = new Flight(globalId, expectedIdentity);
                this.receiveTasks.put(globalId, flight);
                Flight admitted = flight;
                // 超时后移除任务, 主线程回调会核对任务引用并丢弃迟到结果.
                flight.result.orTimeout(5, TimeUnit.SECONDS).whenComplete((result, failure) -> {
                    synchronized (this.receiveTasks) {
                        this.receiveTasks.remove(globalId, admitted);
                    }
                });
                this.read(flight);
            } else if (expectedIdentity != null) {
                if (flight.expectedIdentity != null && !flight.expectedIdentity.equals(expectedIdentity)) return CompletableFuture.failedFuture(new IllegalArgumentException("conflicting map origin for " + globalId));
                // 物品请求可加入已有读取任务, 更新地图前再校验其来源.
                flight.expectedIdentity = expectedIdentity;
            }
            return flight.result;
        }
    }

    // 清除本地缓存; 正在读取的任务会在更新地图前重新读取.
    public void invalidate(int globalId) {
        synchronized (this.receiveTasks) {
            if (this.closed) return;
            this.localCache.invalidate(globalId);
            Flight flight = this.receiveTasks.get(globalId);
            if (flight != null) {
                flight.invalidated = true;
            }
        }
    }

    // 移除任务后在锁外通知等待者失败, 保留已更新的地图副本.
    public void close() {
        ArrayList<Flight> abandoned;
        synchronized (this.receiveTasks) {
            this.closed = true;
            this.tracked.clear();
            abandoned = new ArrayList<>(this.receiveTasks.values());
            this.receiveTasks.clear();
            this.localCache.invalidateAll();
        }
        for (Flight flight : abandoned) {
            flight.result.completeExceptionally(new CancellationException("map receiver is closed"));
        }
    }

    // 延长中转地图的 Redis 缓存有效期, 无需重新采集或发布.
    @NotNull
    public CompletableFuture<Boolean> touch(int globalId) {
        return this.redisCache.touch(globalId);
    }

    /**
     * 读取地图并准备本服更新.
     * <strong>调用时必须持有 receiveTasks 锁</strong>, 回调在各自的执行器上运行.
     *
     * @param flight 仍在 receiveTasks 中的共享任务
     */
    private void read(Flight flight) {
        int id = flight.globalId;
        StoredMap cached = this.localCache.getIfPresent(id);
        CompletableFuture<Optional<StoredMap>> read = CompletableFuture.completedFuture(cached).thenComposeAsync(value -> {
            if (this.closed || flight.result.isDone()) return CompletableFuture.failedFuture(new CancellationException("map read ended"));
            if (value != null) return CompletableFuture.completedFuture(Optional.of(value));
            return CompletableFuture.completedFuture(null).thenCompose(ignored -> this.redisCache.find(id)).exceptionally(failure -> {
                this.logger.warnWithFileCause(LogCategory.DATA, null, null, failure, LogConstants.DATA_MAP_CACHE_FAILED, String.valueOf(id), String.valueOf(failure.getMessage()));
                return Optional.empty();
            }).thenCompose(found -> found.isPresent() ? CompletableFuture.completedFuture(found) : this.storage.find(id));
        }, SparrowSync.instance().scheduler().async());
        // 校验全局 ID, 在异步线程构造独立的原版地图对象.
        read.thenApplyAsync(value -> {
                    if (this.closed || flight.result.isDone()) {
                        throw new CancellationException("map read ended");
                    }
                    StoredMap map = value.orElseThrow(() -> new IllegalStateException("global map does not exist: " + id));
                    if (map.identity().globalId() != id) {
                        throw new IllegalArgumentException("global map id mismatch: " + id);
                    }
                    try {
                        return new Prepared(map, this.nativeMaps.prepareReplica(map.identity(), map.data()));
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    }
                }, SparrowSync.instance().scheduler().async())
                .thenAcceptAsync(prepared -> {
                    int localId;
                    synchronized (this.receiveTasks) {
                        if (this.closed || this.receiveTasks.get(id) != flight || flight.result.isDone()) return;
                        // 读取期间收到更新通知, 丢弃本次结果并重新读取, 等待者继续共用任务.
                        if (flight.invalidated) {
                            flight.invalidated = false;
                            this.read(flight);
                            return;
                        }
                        // 更新前校验物品来源, 包括读取期间加入的请求.
                        if (flight.expectedIdentity != null && !prepared.map.identity().equals(flight.expectedIdentity)) {
                            throw new IllegalArgumentException("global map origin mismatch: " + id);
                        }
                        // 持锁直到地图和缓存更新完成, 避免失效通知插入后仍写入旧结果.
                        localId = this.updateLocalMap(prepared.map, prepared.nativeData);
                        // 缓存命中时保留原到期时间, 重新读取的数据才写入缓存.
                        if (cached == null) {
                            this.localCache.put(id, prepared.map);
                        }
                        this.receiveTasks.remove(id, flight);
                        // 续期失败不撤销已完成的地图更新.
                        CompletableFuture.completedFuture(null)
                                .thenComposeAsync(ignored -> this.closed ? CompletableFuture.completedFuture(false) : this.redisCache.touch(id), SparrowSync.instance().scheduler().async())
                                .exceptionally(failure -> {
                                    this.logger.warnWithFileCause(LogCategory.DATA, null, null, failure, LogConstants.DATA_MAP_CACHE_TOUCH_FAILED, String.valueOf(id), String.valueOf(failure));
                                    return false;
                                });
                    }
                    // 在锁外完成 Future, 避免后续快照处理占用更新锁.
                    flight.result.complete(localId);
                }, SparrowSync.instance().scheduler().platform())
                .whenComplete((ignored, failure) -> {
                    if (failure == null) return;
                    boolean retry;
                    synchronized (this.receiveTasks) {
                        if (this.closed || this.receiveTasks.get(id) != flight || flight.result.isDone()) return;
                        retry = flight.invalidated;
                        if (retry) {
                            flight.invalidated = false;
                            this.read(flight);
                        } else {
                            this.receiveTasks.remove(id, flight);
                        }
                    }
                    if (!retry) {
                        flight.result.completeExceptionally(failure);
                    }
                });
    }

    // 在主线程选择物品 ID 并更新副本, 原地图仍由来源世界维护.
    private int updateLocalMap(StoredMap map, MapItemSavedData prepared) {
        ServerLevel level = this.server.overworld();
        MapIdentity identity = map.identity();
        MapSource source = identity.source();
        if (this.ownerId.equals(source.ownerId())) {
            if (level.getMapData(new MapId(source.id())) != null) {
                // 展示框可能仍引用负数 ID 的副本, 需要更新并继续跟踪它.
                if (level.getMapData(new MapId(identity.globalId())) != null) {
                    this.nativeMaps.updateReplica(level, identity, prepared);
                    this.tracked.computeIfAbsent(identity.globalId(), ignored -> new Tracked());
                }
                return source.id();
            }
            this.logger.warn(LogCategory.DATA, LogConstants.DATA_MAP_SOURCE_MISSING, this.ownerId, String.valueOf(source.id()), String.valueOf(identity.globalId()));
        }
        // 在外服或原地图丢失时使用副本, 由原版保存和显示.
        this.nativeMaps.updateReplica(level, identity, prepared);
        this.tracked.computeIfAbsent(identity.globalId(), ignored -> new Tracked());
        return identity.globalId();
    }

    // 首次发送负数 ID 的地图包时, 登记地图并读取最新数据.
    public void observe(int globalId) {
        if (this.closed || globalId >= 0 || this.tracked.containsKey(globalId)) return;
        Tracked entry = new Tracked();
        if (this.tracked.putIfAbsent(globalId, entry) != null) return;
        // 发包回调只登记 ID, 读取交给异步线程.
        try {
            SparrowSync.instance().scheduler().async().execute(() -> this.refresh(globalId, entry));
        } catch (RejectedExecutionException exception) {
            if (!this.closed) {
                this.failed(globalId, entry, exception);
            }
        }
    }

    // 清除本地缓存, 并刷新已登记的地图副本.
    public void refresh(int globalId) {
        if (this.closed) return;
        this.invalidate(globalId);
        Tracked entry = this.tracked.get(globalId);
        if (entry != null) {
            this.refresh(globalId, entry);
        }
    }

    // 复用同一地图的接收任务, 刷新成功后重置告警状态.
    private void refresh(int id, Tracked entry) {
        if (this.closed) return;
        this.receive(id).whenComplete((localId, failure) -> {
            if (this.closed) return;
            if (failure == null) {
                synchronized (entry) {
                    entry.failed = false;
                }
            } else {
                this.failed(id, entry, failure);
            }
        });
    }

    // 连续失败只记录一次, 刷新成功后重置.
    private void failed(int id, Tracked entry, Throwable failure) {
        synchronized (entry) {
            if (entry.failed) return;
            entry.failed = true;
        }
        this.logger.warnWithFileCause(LogCategory.DATA, null, null, failure, LogConstants.DATA_MAP_REFRESH_FAILED, String.valueOf(id), String.valueOf(failure));
    }

    private record Prepared(StoredMap map, MapItemSavedData nativeData) {
    }

    private static final class Tracked {
        private boolean failed; // 连续失败是否已告警, 由当前条目的锁保护
    }

    /** 同一地图共用的接收任务, 记录是否需要重新读取. */
    private static final class Flight {
        private final int globalId;
        private @Nullable MapIdentity expectedIdentity; // 物品携带的来源; 按 ID 读取时为 null, 由 receiveTasks 锁保护
        private final CompletableFuture<Integer> result = new CompletableFuture<>(); // 共用的本服地图 ID 结果, 5 秒超时
        private boolean invalidated; // 读取期间是否收到更新通知, 由 receiveTasks 锁保护

        private Flight(int globalId, @Nullable MapIdentity expectedIdentity) {
            this.globalId = globalId;
            this.expectedIdentity = expectedIdentity;
        }
    }
}
