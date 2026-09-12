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
    private final ConcurrentHashMap<Integer, Tracked> tracked = new ConcurrentHashMap<>(); // 已登记接收更新的全局地图 ID, 登记后持续保留条目
    private final Map<Integer, Flight> receiveTasks = new HashMap<>(); // 同图共享任务; 此监视器也保护失效状态与最终更新
    private final Cache<Integer, StoredMap> localCache = Caffeine.newBuilder().maximumSize(1024).expireAfterWrite(Duration.ofMinutes(5)).build(); // 至多 1024 张, 写入后 5 分钟过期, 读取命中不会推迟到期
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
     * 等待指定地图在本服就绪, 同图并发请求共享结果.
     * <p>任务最多等待 5 秒. <strong>同一全局 ID 必须对应同一地图同步标识</strong>.
     *
     * @param identity 物品明确携带的地图同步标识, 须与地图存储记录一致
     * @return 更新后的负数 ID, 或返回来源服后找到的非负来源地图 ID; 失败时异常完成
     */
    @NotNull
    public CompletableFuture<Integer> receive(@NotNull MapIdentity identity) {
        return this.receive(identity.globalId(), identity);
    }

    // 运行时按全局 ID 获取地图同步标识, 本服地图副本内容由读取结果更新.
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
                // 任务超时后从登记表移除, 后续主线程回调通过接收任务引用核对放弃迟到结果
                flight.result.orTimeout(5, TimeUnit.SECONDS).whenComplete((result, failure) -> {
                    synchronized (this.receiveTasks) {
                        this.receiveTasks.remove(globalId, admitted);
                    }
                });
                this.read(flight);
            } else if (expectedIdentity != null) {
                if (flight.expectedIdentity != null && !flight.expectedIdentity.equals(expectedIdentity)) return CompletableFuture.failedFuture(new IllegalArgumentException("conflicting map origin for " + globalId));
                // 玩家可以加入运行时先发起的读取, 物品来源在更新本服 NMS 地图数据前一并核对.
                flight.expectedIdentity = expectedIdentity;
            }
            return flight.result;
        }
    }

    // 使指定地图的本地缓存过期, 正在读取的任务在更新前补拉.
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

    // 先摘除活动任务, 再在锁外通知等待者失败; 已更新的本服地图副本继续保留
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

    // 为跨服中转续期 Redis 地图缓存, 无需重新采集或发布地图内容.
    @NotNull
    public CompletableFuture<Boolean> touch(int globalId) {
        return this.redisCache.touch(globalId);
    }

    /**
     * 读取当前任务的一轮内容并完成准备与更新.
     * <p><strong>发起调用时必须持有 receiveTasks 监视器</strong>, 后续回调在各自执行器运行.
     *
     * @param flight 仍登记在 receiveTasks 中的共享任务
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
        // 地图存储记录须对应请求的全局 ID, NMS 地图对象在异步线程独立构造.
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
                        // 读取期间收到通知时, 当前画面作废, 同一等待任务继续读取更新结果
                        if (flight.invalidated) {
                            flight.invalidated = false;
                            this.read(flight);
                            return;
                        }
                        // 物品带来的来源约束在这里核对, 包含准备期间新加入的玩家请求.
                        if (flight.expectedIdentity != null && !prepared.map.identity().equals(flight.expectedIdentity)) {
                            throw new IllegalArgumentException("global map origin mismatch: " + id);
                        }
                        // 检查后到更新结束期间不接受失效穿插, 迟到旧结果不能写入本服 Caffeine 地图缓存或更新本服 NMS 地图数据.
                        localId = this.updateLocalMap(prepared.map, prepared.nativeData);
                        // 缓存命中的重复更新沿用原到期时间, 失效后读取的内容重新入缓存
                        if (cached == null) {
                            this.localCache.put(id, prepared.map);
                        }
                        this.receiveTasks.remove(id, flight);
                        // 续期不写内容, 失败也不撤销已经更新的 NMS 地图数据.
                        CompletableFuture.completedFuture(null)
                                .thenComposeAsync(ignored -> this.closed ? CompletableFuture.completedFuture(false) : this.redisCache.touch(id), SparrowSync.instance().scheduler().async())
                                .exceptionally(failure -> {
                                    this.logger.warnWithFileCause(LogCategory.DATA, null, null, failure, LogConstants.DATA_MAP_CACHE_TOUCH_FAILED, String.valueOf(id), String.valueOf(failure));
                                    return false;
                                });
                    }
                    // 锁外完成 Future, 等待者可以继续快照流程而无需占用更新锁
                    flight.result.complete(localId);
                }, SparrowSync.instance().scheduler().sync())
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

    // 在主线程选择返回来源服后使用的地图 ID 或更新本服地图副本, 来源地图内容由来源世界继续维护.
    private int updateLocalMap(StoredMap map, MapItemSavedData prepared) {
        ServerLevel level = this.server.overworld();
        MapIdentity identity = map.identity();
        MapSource source = identity.source();
        if (this.ownerId.equals(source.ownerId())) {
            if (level.getMapData(new MapId(source.id())) != null) {
                // 已有本服地图副本仍可能被展示框引用, 更新后登记该地图, 供后续通知触发更新.
                if (level.getMapData(new MapId(identity.globalId())) != null) {
                    this.nativeMaps.updateReplica(level, identity, prepared);
                    this.tracked.computeIfAbsent(identity.globalId(), ignored -> new Tracked());
                }
                return source.id();
            }
            this.logger.warn(LogCategory.DATA, LogConstants.DATA_MAP_SOURCE_MISSING, this.ownerId, String.valueOf(source.id()), String.valueOf(identity.globalId()));
        }
        // 在外服或返回来源服后找不到来源地图时, 保留本服地图副本, 交给原版保存和显示.
        this.nativeMaps.updateReplica(level, identity, prepared);
        this.tracked.computeIfAbsent(identity.globalId(), ignored -> new Tracked());
        return identity.globalId();
    }

    // 首次发送负数 ID 地图数据时登记该地图, 按全局地图 ID 从缓存或数据库取得地图同步标识与内容.
    public void observe(int globalId) {
        if (this.closed || globalId >= 0 || this.tracked.containsKey(globalId)) return;
        Tracked entry = new Tracked();
        if (this.tracked.putIfAbsent(globalId, entry) != null) return;
        // 在发包回调中登记首见 ID, 接收流程交给异步线程发起.
        try {
            SparrowSync.instance().scheduler().async().execute(() -> this.refresh(globalId, entry));
        } catch (RejectedExecutionException exception) {
            if (!this.closed) {
                this.failed(globalId, entry, exception);
            }
        }
    }

    // 使本服 Caffeine 地图缓存失效, 并为已登记的地图副本安排数据更新.
    public void refresh(int globalId) {
        if (this.closed) return;
        this.invalidate(globalId);
        Tracked entry = this.tracked.get(globalId);
        if (entry != null) {
            this.refresh(globalId, entry);
        }
    }

    // 已登记副本的刷新沿用同图接收任务, 成功后结束本轮连续故障告警.
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

    // 记录一个连续故障周期的首次失败, 成功刷新后允许再次记录.
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
        private boolean failed; // 本轮连续失败是否已经打印过告警, 由条目监视器保护
    }

    /**
     * 合并同一全局 ID 的等待者, 保存当前读取是否需要补拉的状态.
     */
    private static final class Flight {
        private final int globalId;
        private @Nullable MapIdentity expectedIdentity; // 物品请求附带的来源, 运行时单独读取时为空, 由 receiveTasks 监视器保护
        private final CompletableFuture<Integer> result = new CompletableFuture<>(); // 等待者共用的最终本服 ID, 包含 5 秒超时
        private boolean invalidated; // 本轮读取开始后收到过通知, 由 receiveTasks 监视器保护

        /**
         * 为一张地图建立可共享的接收任务.
         *
         * @param globalId 共享读取的全局 ID
         * @param expectedIdentity 物品来源约束, 运行时读取为空
         */
        private Flight(int globalId, @Nullable MapIdentity expectedIdentity) {
            this.globalId = globalId;
            this.expectedIdentity = expectedIdentity;
        }
    }
}
