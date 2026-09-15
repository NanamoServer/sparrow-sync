package net.momirealms.sparrow.sync.test;

import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.cluster.cache.SnapshotCache;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class NoopSnapshotCache implements SnapshotCache {

    @Override
    @NotNull
    public CompletableFuture<Void> publish(@NotNull Snapshot snapshot, int ttlSeconds) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<Snapshot>> consume(@NotNull UUID player) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    @NotNull
    public CompletableFuture<Void> invalidate(@NotNull UUID player) {
        return CompletableFuture.completedFuture(null);
    }
}
