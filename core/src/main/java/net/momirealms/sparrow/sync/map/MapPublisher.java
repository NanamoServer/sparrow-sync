package net.momirealms.sparrow.sync.map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** 来源键从首次登记到取得全局 ID 始终使用同一条发布链. */
public final class MapPublisher {
    private final Supplier<CompletableFuture<MapStorage>> storage;
    private final MapCache shared;
    private final Executor executor;
    private final ConcurrentHashMap<MapSource, Candidate> pending = new ConcurrentHashMap<>();
    private final Cache<MapSource, StoredMap> published = Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(Duration.ofMinutes(10)).build();
    private volatile boolean sealed;
    private volatile boolean closed;

    public MapPublisher(@NotNull Supplier<CompletableFuture<MapStorage>> storage, @NotNull MapCache shared, @NotNull Executor executor) {
        this.storage = storage;
        this.shared = shared;
        this.executor = executor;
    }

    /** 在允许原生访问的线程采集; 并发玩家不争夺采集顺序, 发布链只负责完整提交步骤. */
    @NotNull
    public CompletableFuture<StoredMap> capture(@NotNull MapSource source, @NotNull Supplier<MapData> capture) {
        if (this.sealed) return CompletableFuture.failedFuture(new CancellationException("map publisher is sealed"));
        MapData data = capture.get();
        if (data == null) {
            throw new IllegalStateException("source map does not exist: " + source);
        }
        Candidate candidate;
        synchronized (this.pending) {
            if (this.sealed) return CompletableFuture.failedFuture(new CancellationException("map publisher is sealed"));
            candidate = this.pending.compute(source, (key, previous) -> {
                Candidate next = new Candidate(data);
                CompletableFuture<?> tail = previous == null ? CompletableFuture.completedFuture(null) : previous.result;
                tail.handle((value, failure) -> null).thenComposeAsync(ignored -> this.publish(source, next, 0), this.executor)
                        .whenComplete((value, failure) -> {
                            if (failure == null) {
                                next.result.complete(value);
                            } else {
                                next.result.completeExceptionally(failure);
                            }
                        });
                return next;
            });
        }
        candidate.result.whenComplete((value, failure) -> this.pending.remove(source, candidate));
        return candidate.result;
    }

    private CompletableFuture<StoredMap> publish(MapSource source, Candidate candidate, int retry) {
        if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
        return this.storage.get().thenCompose(storage -> {
            if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
            StoredMap known = this.published.getIfPresent(source);
            if (known != null && known.data().equals(candidate.data)) {
                return this.shared.touch(known.identity().globalId()).thenCompose(exists -> {
                    if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
                    return exists ? CompletableFuture.completedFuture(known) : this.shared.publish(known).thenApply(ignored -> known);
                });
            }
            CompletableFuture<StoredMap> registered = known == null ? storage.register(source, candidate.data) : CompletableFuture.completedFuture(known);
            return registered.thenCompose(current -> {
                if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
                CompletableFuture<Void> write = current.data().equals(candidate.data)
                        ? CompletableFuture.completedFuture(null) : storage.update(current.identity(), candidate.data);
                StoredMap latest = new StoredMap(current.identity(), candidate.data);
                // 数据库确认后才允许写共享内容; Redis 和广播也必须完成后才能放行下一候选.
                return write.thenCompose(ignored -> this.closed ? CompletableFuture.<Void>failedFuture(new CancellationException("map publisher is closed")) : this.shared.publish(latest)).thenApply(ignored -> {
                    if (!this.closed) {
                        this.published.put(source, latest);
                    }
                    return latest;
                });
            });
        }).exceptionallyCompose(failure -> {
            this.published.invalidate(source);
            // 有更新的已采集候选时, 旧候选不再重试; 未完成的旧 I/O 仍占据当前链头.
            if (this.closed || retry >= 2 || this.pending.get(source) != candidate) return CompletableFuture.failedFuture(failure);
            return CompletableFuture.completedFuture(null).thenComposeAsync(ignored -> this.publish(source, candidate, retry + 1), this.executor);
        });
    }

    // 调用方先完成所有玩家的采集交接, 关服线程只等待已经入链的异步发布.
    public boolean sealAndAwait(long timeout, @NotNull TimeUnit unit) {
        CompletableFuture<?>[] tails;
        synchronized (this.pending) {
            this.sealed = true;
            tails = this.pending.values().stream().map(candidate -> candidate.result.handle((value, failure) -> null)).toArray(CompletableFuture[]::new);
        }
        try {
            CompletableFuture.allOf(tails).get(timeout, unit);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            return false;
        }
    }

    public void close() {
        synchronized (this.pending) {
            this.sealed = true;
            this.closed = true;
            this.published.invalidateAll();
        }
    }

    public void invalidateCache() {
        this.published.invalidateAll();
    }

    private static final class Candidate {
        private final MapData data;
        private final CompletableFuture<StoredMap> result = new CompletableFuture<>();

        private Candidate(MapData data) {
            this.data = data;
        }
    }
}
