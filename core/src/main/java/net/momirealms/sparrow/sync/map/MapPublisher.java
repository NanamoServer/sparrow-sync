package net.momirealms.sparrow.sync.map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.momirealms.sparrow.sync.map.cache.MapCache;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

public final class MapPublisher {
    private final MapStorage storage;
    private final MapCache shared;
    private final String ownerId;
    private final Executor executor;
    private final ConcurrentHashMap<MapSource, CompletableFuture<StoredMap>> pending = new ConcurrentHashMap<>(); // 各来源地图的最后一个发布任务; 锁用于协调入队与停止接收请求
    private final Cache<MapSource, StoredMap> published = Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(Duration.ofMinutes(10)).build(); // 最近发布成功的内容, 用于判断是否变化
    private volatile boolean sealed; // 不再接收新发布, 已排队的任务继续执行
    private volatile boolean closed; // 停止后续发布步骤, 已提交的 I/O 继续执行

    public MapPublisher(@NotNull MapStorage storage, @NotNull MapCache shared, @NotNull String ownerId, @NotNull Executor executor) {
        this.storage = storage;
        this.shared = shared;
        this.ownerId = ownerId;
        this.executor = executor;
    }

    /**
     * 发布采集到的地图. 同一来源按提交顺序写入数据库、更新 Redis 并广播通知.
     *
     * @return 发布完成的结果; 失败由物品管线记录告警并保留原物品
     */
    @NotNull
    public CompletableFuture<StoredMap> publish(@NotNull MapSource source, @NotNull MapData data) {
        // 只有来源服可以上传地图.
        if (!this.ownerId.equals(source.ownerId())) return CompletableFuture.failedFuture(new IllegalArgumentException("only the origin owner may upload map content"));
        CompletableFuture<StoredMap> result;
        synchronized (this.pending) {
            if (this.sealed) return CompletableFuture.failedFuture(new CancellationException("map publisher is sealed"));
            result = this.pending.compute(source, (key, previous) -> {
                CompletableFuture<?> tail = previous == null ? CompletableFuture.completedFuture(null) : previous;
                // 等待上一项发布结束, 即使上一项失败也继续.
                return tail.handle((value, failure) -> null)
                        .thenComposeAsync(ignored -> this.publishContent(source, data), this.executor)
                        .whenComplete((value, failure) -> {
                            if (failure != null) {
                                // 数据库可能已写入新内容, 失败后清除旧比较缓存.
                                this.published.invalidate(source);
                            }
                        });
            });
        }
        result.whenComplete((value, failure) -> this.pending.remove(source, result));
        return result;
    }

    /** 清除用于比较的发布记录, 下次发布重新读取数据库. */
    public void invalidate(@NotNull MapSource source) {
        this.published.invalidate(source);
    }

    // 提交一份已经采集的地图同步数据, 失败交给物品管线处理.
    private CompletableFuture<StoredMap> publishContent(MapSource source, MapData data) {
        if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
        StoredMap known = this.published.getIfPresent(source);
        // 内容未变时只续期; Redis 缓存已过期则重新写入.
        if (known != null && known.data().equals(data)) {
            return this.shared.touch(known.identity().globalId()).thenCompose(exists -> {
                if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
                return exists ? CompletableFuture.completedFuture(known) : this.shared.publish(known).thenApply(ignored -> known);
            });
        }
        // 登记可能返回重启前已有的记录, 仍需比较内容.
        CompletableFuture<StoredMap> registered = known == null ? this.storage.register(source, data) : CompletableFuture.completedFuture(known);
        return registered.thenCompose(current -> {
            if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
            // 缓存中的内容已确认有变化; 新登记时还需检查数据库返回的内容.
            CompletableFuture<Void> write = known == null && current.data().equals(data)
                    ? CompletableFuture.completedFuture(null)
                    : this.storage.update(current.identity(), data);
            StoredMap latest = new StoredMap(current.identity(), data);
            // 数据库写入成功后再更新 Redis 并广播, 全部完成后才处理下一项发布.
            return write.thenCompose(ignored -> this.closed ? CompletableFuture.failedFuture(new CancellationException("map publisher is closed")) : this.shared.publish(latest)).thenApply(ignored -> {
                if (!this.closed) {
                    this.published.put(source, latest);
                }
                return latest;
            });
        });
    }

    // 停止接收新发布, 限时等待已排队的任务完成.
    public boolean sealAndAwait(long timeout, @NotNull TimeUnit unit) {
        CompletableFuture<?>[] tails;
        synchronized (this.pending) {
            this.sealed = true;
            tails = this.pending.values().stream().map(result -> result.handle((value, failure) -> null)).toArray(CompletableFuture[]::new);
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

    // 停止后续发布步骤并清除比较缓存, 保留世界地图和数据库记录.
    public void close() {
        synchronized (this.pending) {
            this.sealed = true;
            this.closed = true;
            this.published.invalidateAll();
        }
    }
}
