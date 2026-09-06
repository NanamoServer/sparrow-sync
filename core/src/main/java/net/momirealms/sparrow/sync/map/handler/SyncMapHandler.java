package net.momirealms.sparrow.sync.map.handler;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.sync.map.MapIdentity;
import net.momirealms.sparrow.sync.map.MapOrigin;
import net.momirealms.sparrow.sync.map.MapReceiver;
import net.momirealms.sparrow.sync.map.MapSource;
import net.momirealms.sparrow.sync.map.StoredMap;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class SyncMapHandler implements MapHandler {
    private final String clusterId;
    private final MapReceiver receiver;

    public SyncMapHandler(@NotNull String clusterId, @NotNull MapReceiver receiver) {
        this.clusterId = clusterId;
        this.receiver = receiver;
    }

    @Override
    @NotNull
    public MapType type() {
        return MapType.SYNC;
    }

    @Override
    @NotNull
    public CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull Map<Integer, CompletableFuture<StoredMap>> captured) {
        CompletableFuture<StoredMap> candidate = captured.get(origin.id());
        if (candidate == null) return CompletableFuture.failedFuture(new IllegalStateException("no source map was captured for " + origin));
        return candidate.thenApply(map -> {
            if (!map.identity().clusterId().equals(this.clusterId) || !map.identity().source().equals(new MapSource(origin.ownerId(), origin.id()))) {
                throw new IllegalArgumentException("captured map origin mismatch");
            }
            return this.withId(components, map.identity().globalId());
        });
    }

    @Override
    @NotNull
    public CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
        if (!(components.get("minecraft:map_id") instanceof IntTag id)) return CompletableFuture.failedFuture(new IllegalArgumentException("SYNC map has no integer global id"));
        MapIdentity identity = new MapIdentity(this.clusterId, new MapSource(origin.ownerId(), origin.id()), id.getAsInt());
        return this.receiver.receive(identity).thenApply(localId -> localId == identity.globalId() ? components : this.withId(components, localId));
    }

    private CompoundTag withId(CompoundTag components, int id) {
        CompoundTag result = new CompoundTag(new HashMap<>(components.tags));
        result.putInt("minecraft:map_id", id);
        return result;
    }

    @Override
    @NotNull
    public CompletableFuture<CompoundTag> forwardAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin) {
        if (!(components.get("minecraft:map_id") instanceof IntTag id)) return CompletableFuture.failedFuture(new IllegalArgumentException("SYNC map has no integer global id"));
        MapIdentity identity = new MapIdentity(this.clusterId, new MapSource(origin.ownerId(), origin.id()), id.getAsInt());
        return this.receiver.touch(identity.globalId()).thenApply(ignored -> components);
    }
}
