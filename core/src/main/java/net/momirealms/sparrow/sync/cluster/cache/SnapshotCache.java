package net.momirealms.sparrow.sync.cluster.cache;

import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 玩家最新快照的跨服短时缓存, <strong>只作为读取快路径, 不承担正确性</strong>.
 * <p>条目顺序由会话锁的互斥与同一条 Redis 连接的按序入队提供, 所以取到的必然是上一台服收尾保存写下的那一份.
 * <p>任何读写失败都静默降级为直接读数据库.
 */
public interface SnapshotCache {

    // 投递一份已确认落库的快照.
    @NotNull
    CompletableFuture<Void> publish(@NotNull Snapshot snapshot, int ttlSeconds);

    // 取走并删除该玩家的缓存条目.
    @NotNull
    CompletableFuture<Optional<Snapshot>> consume(@NotNull UUID player);

    // 删除该玩家的缓存条目, 供绕开保存流程的写库路径在落库后清理.
    @NotNull
    CompletableFuture<Void> invalidate(@NotNull UUID player);
}
