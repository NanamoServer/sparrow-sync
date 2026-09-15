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

public final class MemorySnapshotCache implements SnapshotCache {
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    public final List<UUID> invalidations = new CopyOnWriteArrayList<>();
    public CompletableFuture<Void> invalidation = CompletableFuture.completedFuture(null);

    @Override
    @NotNull
    public CompletableFuture<Void> publish(@NotNull Snapshot snapshot, int ttlSeconds) {
        this.snapshots.put(snapshot.meta().player(), snapshot);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<Snapshot>> consume(@NotNull UUID player) {
        return CompletableFuture.completedFuture(Optional.ofNullable(this.snapshots.remove(player)));
    }

    @Override
    @NotNull
    public CompletableFuture<Void> invalidate(@NotNull UUID player) {
        this.invalidations.add(player);
        return this.invalidation.thenRun(() -> this.snapshots.remove(player));
    }
}
