package net.momirealms.sparrow.sync.cluster.cache;

import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface SnapshotCache {

    /** 缓存已写入数据库的快照, 供下一次跨服登录读取. */
    @NotNull
    CompletableFuture<Void> publish(@NotNull Snapshot snapshot, int ttlSeconds);

    /** 读取并删除玩家缓存, 每份缓存只能读取一次. */
    @NotNull
    CompletableFuture<Optional<Snapshot>> consume(@NotNull UUID player);

    /** 清除玩家的旧缓存. */
    @NotNull
    CompletableFuture<Void> invalidate(@NotNull UUID player);
}
