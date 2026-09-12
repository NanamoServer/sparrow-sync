package net.momirealms.sparrow.sync.test;

import net.momirealms.sparrow.sync.cluster.cache.SnapshotCache;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 保留实际缓存条目并记录删除请求, 测试可以暂停删除完成来检查业务回执的先后顺序. */
public final class MemorySnapshotCache implements SnapshotCache {
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>(); // 供保存回调与测试线程共同访问
    public final List<UUID> invalidations = new CopyOnWriteArrayList<>(); // 按调用顺序记录已经发出的删除请求
    public CompletableFuture<Void> invalidation = CompletableFuture.completedFuture(null); // 当前删除请求何时生效, 默认立即完成

    /**
     * 保存玩家的缓存正文, 后续消费会取走这份值.
     *
     * @param snapshot 模拟已发布的快照
     * @param ttlSeconds 生产调用传入的有效期, 测试通过消费和删除控制条目寿命
     * @return 已完成的发布结果
     */
    @Override
    @NotNull
    public CompletableFuture<Void> publish(@NotNull Snapshot snapshot, int ttlSeconds) {
        this.snapshots.put(snapshot.meta().player(), snapshot);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 取走玩家条目, 供测试区分命中旧正文与需要读库的情况.
     *
     * @param player 要读取的玩家
     * @return 原缓存正文, 未命中时为空
     */
    @Override
    @NotNull
    public CompletableFuture<Optional<Snapshot>> consume(@NotNull UUID player) {
        return CompletableFuture.completedFuture(Optional.ofNullable(this.snapshots.remove(player)));
    }

    /**
     * 立即记录删除请求, 到测试给定的完成时刻再移除正文.
     *
     * @param player 要失效的玩家
     * @return 正文移除后完成的结果
     */
    @Override
    @NotNull
    public CompletableFuture<Void> invalidate(@NotNull UUID player) {
        this.invalidations.add(player);
        return this.invalidation.thenRun(() -> this.snapshots.remove(player));
    }
}
