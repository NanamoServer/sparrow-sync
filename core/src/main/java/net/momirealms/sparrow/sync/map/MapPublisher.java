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
    private final ConcurrentHashMap<MapSource, CompletableFuture<StoredMap>> pending = new ConcurrentHashMap<>(); // 每张来源地图最后提交的发布任务, 监视器协调任务入队和停止接受新发布请求
    private final Cache<MapSource, StoredMap> published = Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(Duration.ofMinutes(10)).build(); // 最近成功发布的内容, 供相等比较.
    private volatile boolean sealed; // 拒绝新发布, 已加入发布队列的任务仍可继续
    private volatile boolean closed; // 禁止发布链发起后续步骤, 已提交的外部 I/O 自行结束

    public MapPublisher(@NotNull MapStorage storage, @NotNull MapCache shared, @NotNull String ownerId, @NotNull Executor executor) {
        this.storage = storage;
        this.shared = shared;
        this.ownerId = ownerId;
        this.executor = executor;
    }

    /**
     * 发布已采集的地图同步数据, 同一来源按提交顺序完成数据库写入、Redis 缓存更新和失效通知广播.
     *
     * @return 完整发布结果, 失败由物品管线告警并原样同步
     */
    @NotNull
    public CompletableFuture<StoredMap> publish(@NotNull MapSource source, @NotNull MapData data) {
        // 来源上传权限在发布入口核对, 数据库和 Redis 按已确认的地图身份读写.
        if (!this.ownerId.equals(source.ownerId())) return CompletableFuture.failedFuture(new IllegalArgumentException("only the origin owner may upload map content"));
        CompletableFuture<StoredMap> result;
        synchronized (this.pending) {
            if (this.sealed) return CompletableFuture.failedFuture(new CancellationException("map publisher is sealed"));
            result = this.pending.compute(source, (key, previous) -> {
                CompletableFuture<?> tail = previous == null ? CompletableFuture.completedFuture(null) : previous;
                // 本次待发布地图数据等待上一项发布任务结束, 前一份失败也允许继续处理
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

    /** 清除来源地图的发布比较缓存, 下一次发布重新读取数据库中的地图记录. */
    public void invalidate(@NotNull MapSource source) {
        this.published.invalidate(source);
    }

    // 提交一份已经采集的地图同步数据, 失败交给物品管线处理.
    private CompletableFuture<StoredMap> publishContent(MapSource source, MapData data) {
        if (this.closed) return CompletableFuture.failedFuture(new CancellationException("map publisher is closed"));
        StoredMap known = this.published.getIfPresent(source);
        // 内容相等时续期 Redis 地图缓存; Redis 地图缓存已过期则由来源服重新写入这份已提交内容
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
            // 命中的记录已确认内容变化, 首次登记才需核对数据库中的已有内容.
            CompletableFuture<Void> write = known == null && current.data().equals(data)
                    ? CompletableFuture.completedFuture(null)
                    : this.storage.update(current.identity(), data);
            StoredMap latest = new StoredMap(current.identity(), data);
            // 数据库确认后才更新 Redis 地图缓存并广播失效通知, 这些步骤完成后才开始下一项发布任务.
            return write.thenCompose(ignored -> this.closed ? CompletableFuture.failedFuture(new CancellationException("map publisher is closed")) : this.shared.publish(latest)).thenApply(ignored -> {
                if (!this.closed) {
                    this.published.put(source, latest);
                }
                return latest;
            });
        });
    }

    // 停止接受新地图发布请求, 并限时等待已加入发布队列的任务完成.
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

    // 终止后续发布步骤并释放比较缓存, 世界中的 NMS 地图数据和数据库记录继续保留
    public void close() {
        synchronized (this.pending) {
            this.sealed = true;
            this.closed = true;
            this.published.invalidateAll();
        }
    }
}
