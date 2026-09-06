package net.momirealms.sparrow.sync.map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 为传输物品和运行时刷新准备本服可用的地图 ID.
 *
 * <p>相同全局 ID 的读取共用一个任务, 顺序查询本地缓存、Redis 和数据库.
 * 数据准备完成后切到原生线程更新副本或恢复原图 ID, 返回 Future 在更新完成后结束.
 * 关闭、超时或途中收到失效通知时, 已读取的结果须重新检查后才能应用.
 */
// 为传输物品和运行时刷新准备本服可用的地图 ID.
public final class MapReceiver {
    private final MapStorage storage;
    private final MapCache shared;
    private final NativeMapAdapter nativeMaps;
    private final MinecraftServer server;
    private final String ownerId;
    private final MapRuntime runtime;
    private final Executor worker;
    private final Executor nativeExecutor;
    private final SyncLogger logger;
    private final Map<Integer, Flight> flights = new HashMap<>(); // 同图共享任务; 此监视器也保护失效状态与最终更新
    private final Set<Integer> databaseReads = new HashSet<>(); // 下次读取必须跳过两层缓存的全局 ID, 由 flights 监视器保护
    private final Cache<Integer, StoredMap> cache = Caffeine.newBuilder().maximumSize(1024).expireAfterWrite(Duration.ofMinutes(5)).build(); // 至多 1024 张, 写入后 5 分钟过期, 读取命中不会推迟到期
    private volatile boolean closed;

    public MapReceiver(@NotNull MapStorage storage, @NotNull MapCache shared, @NotNull NativeMapAdapter nativeMaps, @NotNull MinecraftServer server, @NotNull String ownerId, @NotNull Executor worker, @NotNull Executor nativeExecutor, @NotNull SyncLogger logger) {
        this.storage = storage;
        this.shared = shared;
        this.nativeMaps = nativeMaps;
        this.server = server;
        this.ownerId = ownerId;
        this.worker = worker;
        this.nativeExecutor = nativeExecutor;
        this.logger = logger;
        this.runtime = new MapRuntime(this, nativeMaps, server, nativeExecutor, logger);
    }

    /**
     * 等待指定地图在本服就绪, 同图并发请求共享结果.
     * <p>任务最多等待 5 秒. <strong>同一全局 ID 必须对应同一完整来源身份</strong>.
     *
     * @param identity 物品或已保存副本携带的完整身份
     * @return 更新后的负数 ID, 或回源找到的非负原始 ID; 失败时异常完成
     */
    @NotNull
    public CompletableFuture<Integer> receive(@NotNull MapIdentity identity) {
        synchronized (this.flights) {
            if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map receiver is closed"));
            Flight flight = this.flights.get(identity.globalId());
            if (flight == null) {
                flight = new Flight(identity);
                this.flights.put(identity.globalId(), flight);
                Flight admitted = flight;
                // 任务超时后从登记表移除, 后续原生回调通过任务身份核对放弃迟到结果
                flight.result.orTimeout(5, TimeUnit.SECONDS).whenComplete((result, failure) -> {
                    synchronized (this.flights) {
                        this.flights.remove(identity.globalId(), admitted);
                    }
                });
                this.read(flight);
            }
            if (!flight.identity.equals(identity)) return CompletableFuture.failedFuture(new IllegalArgumentException("conflicting map origin for " + identity.globalId()));
            return flight.result;
        }
    }

    // 使指定地图的本地缓存过期, 正在读取的任务在更新前补拉.
    public void invalidate(int globalId) {
        this.invalidate(globalId, false);
    }

    // 使指定地图重新读取, 并按需要强制核对数据库.
    public void invalidate(int globalId, boolean database) {
        synchronized (this.flights) {
            if (this.closed) return;
            this.cache.invalidate(globalId);
            if (database) {
                this.databaseReads.add(globalId);
            }
            Flight flight = this.flights.get(globalId);
            if (flight != null) {
                flight.invalidated = true;
            }
        }
    }

    // 先摘除活动任务, 再在锁外通知等待者失败; 已更新的原生副本继续保留
    public void close() {
        this.runtime.close();
        ArrayList<Flight> abandoned;
        synchronized (this.flights) {
            this.closed = true;
            abandoned = new ArrayList<>(this.flights.values());
            this.flights.clear();
            this.databaseReads.clear();
            this.cache.invalidateAll();
        }
        for (Flight flight : abandoned) {
            flight.result.completeExceptionally(new CancellationException("map receiver is closed"));
        }
    }

    // 为跨服中转续期共享缓存, 无需重新采集或发布地图内容.
    @NotNull
    public CompletableFuture<Boolean> touch(int globalId) {
        if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map receiver is closed"));
        return this.shared.touch(globalId);
    }

    /**
     * 读取当前任务的一轮内容并完成准备与更新.
     * <p><strong>发起调用时必须持有 flights 监视器</strong>, 后续回调在各自执行器运行.
     *
     * @param flight 仍登记在 flights 中的共享任务
     */
    private void read(Flight flight) {
        int id = flight.identity.globalId();
        boolean database = this.databaseReads.remove(id);
        StoredMap cached = this.cache.getIfPresent(id);
        CompletableFuture<Optional<StoredMap>> read = CompletableFuture.completedFuture(cached).thenComposeAsync(value -> {
            if (this.closed || flight.result.isDone()) return CompletableFuture.failedFuture(new CancellationException("map read ended"));
            if (database) return this.storage.find(id);
            if (value != null) return CompletableFuture.completedFuture(Optional.of(value));
            return CompletableFuture.completedFuture(null).thenCompose(ignored -> this.shared.find(id)).exceptionally(failure -> {
                this.logger.warnWithFileCause(LogCategory.DATA, null, null, failure, LogConstants.DATA_MAP_CACHE_FAILED, String.valueOf(id), String.valueOf(failure.getMessage()));
                return Optional.empty();
            }).thenCompose(found -> found.isPresent() ? CompletableFuture.completedFuture(found) : this.storage.find(id));
        }, this.worker);
        // 取得持久记录后先核对完整身份, 再在工作线程构造独立原生副本
        read.thenApplyAsync(value -> {
            if (this.closed || flight.result.isDone()) {
                throw new CancellationException("map read ended");
            }
            StoredMap map = value.orElseThrow(() -> new IllegalStateException("global map does not exist: " + id));
            if (!map.identity().equals(flight.identity)) {
                throw new IllegalArgumentException("global map origin mismatch: " + id);
            }
            try {
                return new Prepared(map, this.nativeMaps.prepareReplica(map.identity(), map.data()));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, this.worker).thenAcceptAsync(prepared -> {
            int localId;
            synchronized (this.flights) {
                if (this.closed || this.flights.get(id) != flight || flight.result.isDone()) return;
                // 读取期间收到通知时, 当前画面作废, 同一等待任务继续读取更新结果
                if (flight.invalidated) {
                    flight.invalidated = false;
                    this.read(flight);
                    return;
                }
                // 检查后到更新结束期间不接受失效穿插, 迟到旧结果不能回填缓存或原生对象.
                localId = this.updateLocalMap(prepared.map, prepared.nativeData);
                // 缓存命中的重复更新沿用原到期时间, 强制数据库校验会更新缓存内容
                if (cached == null || database) {
                    this.cache.put(id, prepared.map);
                }
                this.flights.remove(id, flight);
                // 续期不写内容, 失败也不撤销已经更新的原生数据.
                CompletableFuture.completedFuture(null).thenComposeAsync(ignored -> this.closed ? CompletableFuture.completedFuture(false) : this.shared.touch(id), this.worker).exceptionally(failure -> {
                    this.logger.warnWithFileCause(LogCategory.DATA, null, null, failure, LogConstants.DATA_MAP_CACHE_TOUCH_FAILED, String.valueOf(id), String.valueOf(failure));
                    return false;
                });
            }
            // 锁外完成 Future, 等待者可以继续快照流程而无需占用更新锁
            flight.result.complete(localId);
        }, this.nativeExecutor).whenComplete((ignored, failure) -> {
            if (failure == null) return;
            boolean retry;
            synchronized (this.flights) {
                if (this.closed || this.flights.get(id) != flight || flight.result.isDone()) return;
                retry = flight.invalidated;
                if (retry) {
                    flight.invalidated = false;
                    this.read(flight);
                } else {
                    this.flights.remove(id, flight);
                }
            }
            if (!retry) {
                flight.result.completeExceptionally(failure);
            }
        });
    }

    // 在原生线程选择回源 ID 或更新负数副本, 原图内容由来源世界继续维护.
    private int updateLocalMap(StoredMap map, MapItemSavedData prepared) {
        ServerLevel level = this.server.overworld();
        MapIdentity identity = map.identity();
        MapSource source = identity.source();
        if (this.ownerId.equals(source.ownerId())) {
            if (level.getMapData(new MapId(source.id())) != null) {
                // 已有负数副本仍可能被展示框引用, 更新后登记运行时追踪.
                if (identity.equals(this.nativeMaps.replicaIdentity(level, identity.globalId()))) {
                    this.nativeMaps.updateReplica(level, identity, prepared);
                    this.runtime.updated(identity);
                }
                return source.id();
            }
            this.logger.warn(LogCategory.DATA, LogConstants.DATA_MAP_SOURCE_MISSING, this.ownerId, String.valueOf(source.id()), String.valueOf(identity.globalId()));
        }
        // 外服或回源缺图时保留负数副本, 交给原版保存和显示.
        this.nativeMaps.updateReplica(level, identity, prepared);
        this.runtime.updated(identity);
        return identity.globalId();
    }

    public void observe(int globalId) {
        this.runtime.observe(globalId);
    }

    public void refresh(int globalId) {
        this.runtime.invalidate(globalId);
    }

    private record Prepared(StoredMap map, MapItemSavedData nativeData) {
    }

    /**
     * 合并同一全局 ID 的等待者, 保存当前读取是否需要补拉的状态.
     */
    private static final class Flight {
        private final MapIdentity identity; // 本次任务固定的完整身份, 后加入请求须一致
        private final CompletableFuture<Integer> result = new CompletableFuture<>(); // 等待者共用的最终本服 ID, 包含 5 秒超时
        private boolean invalidated; // 本轮读取开始后收到过通知, 由 flights 监视器保护

        /**
         * 为一张地图建立可共享的接收任务.
         *
         * @param identity 本次请求核对的完整身份
         */
        private Flight(MapIdentity identity) {
            this.identity = identity;
        }
    }
}
