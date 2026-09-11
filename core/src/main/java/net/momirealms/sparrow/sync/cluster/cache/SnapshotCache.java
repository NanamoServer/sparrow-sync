package net.momirealms.sparrow.sync.cluster.cache;

import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
