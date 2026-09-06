package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface MapCache {

    @NotNull
    CompletableFuture<Optional<StoredMap>> find(int globalId);

    @NotNull
    CompletableFuture<Void> publish(@NotNull StoredMap map);

    @NotNull
    CompletableFuture<Boolean> touch(int globalId);
}
