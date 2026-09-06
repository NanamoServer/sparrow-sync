package net.momirealms.sparrow.sync.map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** 合并同图读取, 并把失效检查和原生安装放在同一完成边界. */
public final class MapReceiver {
    private final Supplier<CompletableFuture<MapStorage>> storage;
    private final MapCache shared;
    private final ReplicaAccess replicas;
    private final Executor worker;
    private final Executor nativeExecutor;
    private final SyncLogger logger;
    private final Map<Integer, Flight> flights = new HashMap<>();
    private final Set<Integer> databaseReads = new HashSet<>();
    private final Cache<Integer, StoredMap> cache = Caffeine.newBuilder().maximumSize(1024).expireAfterWrite(Duration.ofMinutes(5)).build();
    private volatile boolean closed;

    public MapReceiver(@NotNull Supplier<CompletableFuture<MapStorage>> storage, @NotNull MapCache shared, @NotNull ReplicaAccess replicas, @NotNull Executor worker, @NotNull Executor nativeExecutor, @NotNull SyncLogger logger) {
        this.storage = storage;
        this.shared = shared;
        this.replicas = replicas;
        this.worker = worker;
        this.nativeExecutor = nativeExecutor;
        this.logger = logger;
    }

    @NotNull
    public CompletableFuture<Integer> receive(@NotNull MapIdentity identity) {
        synchronized (this.flights) {
            if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map receiver is closed"));
            Flight flight = this.flights.get(identity.globalId());
            if (flight == null) {
                flight = new Flight(identity);
                this.flights.put(identity.globalId(), flight);
                Flight admitted = flight;
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

    public void invalidate(int globalId) {
        this.invalidate(globalId, false);
    }

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

    public void invalidateAll() {
        synchronized (this.flights) {
            this.cache.invalidateAll();
            this.databaseReads.addAll(this.flights.keySet());
            for (Flight flight : this.flights.values()) {
                flight.invalidated = true;
            }
        }
    }

    public void close() {
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

    @NotNull
    public CompletableFuture<Boolean> touch(int globalId) {
        if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map receiver is closed"));
        return this.shared.touch(globalId);
    }

    private void read(Flight flight) {
        int id = flight.identity.globalId();
        boolean database = this.databaseReads.remove(id);
        StoredMap cached = this.cache.getIfPresent(id);
        CompletableFuture<Optional<StoredMap>> read = CompletableFuture.completedFuture(cached).thenComposeAsync(value -> {
            if (this.closed || flight.result.isDone()) return CompletableFuture.failedFuture(new CancellationException("map read ended"));
            if (database) return this.storage.get().thenCompose(storage -> storage.find(id));
            if (value != null) return CompletableFuture.completedFuture(Optional.of(value));
            return CompletableFuture.completedFuture(null).thenCompose(ignored -> this.shared.find(id)).exceptionally(failure -> {
                this.logger.warnWithFileCause(LogCategory.DATA, null, null, failure, LogConstants.DATA_MAP_CACHE_FAILED, String.valueOf(id), String.valueOf(failure.getMessage()));
                return Optional.empty();
            }).thenCompose(found -> found.isPresent() ? CompletableFuture.completedFuture(found) : this.storage.get().thenCompose(storage -> storage.find(id)));
        }, this.worker);
        read.thenApplyAsync(value -> {
            if (this.closed || flight.result.isDone()) {
                throw new CancellationException("map read ended");
            }
            StoredMap map = value.orElseThrow(() -> new IllegalStateException("global map does not exist: " + id));
            if (!map.identity().equals(flight.identity)) {
                throw new IllegalArgumentException("global map origin mismatch: " + id);
            }
            try {
                return new Prepared(map, this.replicas.prepare(map));
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, this.worker).thenAcceptAsync(prepared -> {
            int localId;
            synchronized (this.flights) {
                if (this.closed || this.flights.get(id) != flight || flight.result.isDone()) return;
                if (flight.invalidated) {
                    flight.invalidated = false;
                    this.read(flight);
                    return;
                }
                // 检查后到安装结束期间不接受失效穿插, 迟到旧结果不能回填缓存或原生对象.
                localId = prepared.install.getAsInt();
                if (cached == null || database) {
                    this.cache.put(id, prepared.map);
                }
                this.flights.remove(id, flight);
                // 续期不写内容, 失败也不撤销已经安装的原生数据.
                CompletableFuture.completedFuture(null).thenComposeAsync(ignored -> this.closed ? CompletableFuture.completedFuture(false) : this.shared.touch(id), this.worker).exceptionally(failure -> {
                    this.logger.warnWithFileCause(LogCategory.DATA, null, null, failure, LogConstants.DATA_MAP_CACHE_TOUCH_FAILED, String.valueOf(id), String.valueOf(failure));
                    return false;
                });
            }
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

    /** 异步构造独立原生数据, 返回只在原生线程执行的安装/回源操作. */
    @FunctionalInterface
    public interface ReplicaAccess {
        @NotNull
        IntSupplier prepare(@NotNull StoredMap map) throws IOException;
    }

    private record Prepared(StoredMap map, IntSupplier install) {
    }

    private static final class Flight {
        private final MapIdentity identity;
        private final CompletableFuture<Integer> result = new CompletableFuture<>();
        private boolean invalidated;

        private Flight(MapIdentity identity) {
            this.identity = identity;
        }
    }
}
