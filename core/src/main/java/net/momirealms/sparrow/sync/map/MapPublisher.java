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

public final class MapPublisher {
    private final MapStorage storage;
    private final MapCache shared;
    private final Executor executor;
    private final ConcurrentHashMap<MapSource, CompletableFuture<StoredMap>> pending = new ConcurrentHashMap<>(); // 每个来源的发布尾任务, 监视器协调入链与关服封口
    private final Cache<MapSource, StoredMap> published = Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(Duration.ofMinutes(10)).build(); // 最近成功发布的内容, 供相等比较.
    private volatile boolean sealed; // 拒绝新发布, 已入链任务仍可继续
    private volatile boolean closed; // 禁止发布链发起后续步骤, 已提交的外部 I/O 自行结束

    public MapPublisher(@NotNull MapStorage storage, @NotNull MapCache shared, @NotNull Executor executor) {
        this.storage = storage;
        this.shared = shared;
        this.executor = executor;
    }

    /**
     * 异步发布已经采集的独立地图内容, 同一来源按提交顺序完成数据库、Redis 和通知.
     *
     * @return 完整发布结果, 失败由物品管线告警并原样同步
     */
    @NotNull
    public CompletableFuture<StoredMap> publish(@NotNull MapSource source, @NotNull MapData data) {
        CompletableFuture<StoredMap> result;
        synchronized (this.pending) {
            if (this.sealed) return CompletableFuture.failedFuture(new CancellationException("map publisher is sealed"));
            result = this.pending.compute(source, (key, previous) -> {
                CompletableFuture<?> tail = previous == null ? CompletableFuture.completedFuture(null) : previous;
                // 当前候选等待前一份发布结束, 前一份失败也允许继续处理
                return tail.handle((value, failure) -> null)
                        .thenComposeAsync(ignored -> this.publishContent(source, data), this.executor)
                        .whenComplete((value, failure) -> {
                            if (failure != null) {
                                // 下一份发布开始前清除旧比较结果, 数据库可能已经提交了本次内容.
                                this.published.invalidate(source);
                            }
                        });
            });
        }
        result.whenComplete((value, failure) -> this.pending.remove(source, result));
        return result;
    }

    // 提交一份已经采集的候选, 失败交给物品管线处理.
    private CompletableFuture<StoredMap> publishContent(MapSource source, MapData data) {
        if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
        StoredMap known = this.published.getIfPresent(source);
        // 内容相等时续期缓存; 缓存已过期则由来源服重新写入这份已提交内容
        if (known != null && known.data().equals(data)) {
            return this.shared.touch(known.identity().globalId()).thenCompose(exists -> {
                if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
                return exists ? CompletableFuture.completedFuture(known) : this.shared.publish(known).thenApply(ignored -> known);
            });
        }
        // 首次登记可能返回已有记录, 后续内容比较覆盖重新启动和重复登记的情况
        CompletableFuture<StoredMap> registered = known == null ? this.storage.register(source, data) : CompletableFuture.completedFuture(known);
        return registered.thenCompose(current -> {
            if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
            CompletableFuture<Void> write = current.data().equals(data)
                    ? CompletableFuture.completedFuture(null)
                    : this.storage.update(current.identity(), data);
            StoredMap latest = new StoredMap(current.identity(), data);
            // 数据库确认后才允许写共享内容; Redis 和广播也必须完成后才能放行下一候选.
            return write.thenCompose(ignored -> this.closed ? CompletableFuture.failedFuture(new CancellationException("map publisher is closed")) : this.shared.publish(latest)).thenApply(ignored -> {
                if (!this.closed) {
                    this.published.put(source, latest);
                }
                return latest;
            });
        });
    }

    // 停止接收新候选并限时等待已入链的发布结束.
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

    // 终止后续发布步骤并释放比较缓存, 世界原生地图和数据库记录继续保留
    public void close() {
        synchronized (this.pending) {
            this.sealed = true;
            this.closed = true;
            this.published.invalidateAll();
        }
    }
}
