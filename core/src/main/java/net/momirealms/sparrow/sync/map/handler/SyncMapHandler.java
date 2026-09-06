package net.momirealms.sparrow.sync.map.handler;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapOrigin;
import net.momirealms.sparrow.sync.map.MapReceiver;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class SyncMapHandler implements MapHandler {
    private final MapReceiver receiver;

    public SyncMapHandler(@NotNull MapReceiver receiver) {
        this.receiver = receiver;
    }

    @Override
    @NotNull
    public MapType type() {
        return MapType.SYNC;
    }

    @Override
    @NotNull
    public CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull Map<Integer, CompletableFuture<StoredMap>> publications) {
        CompletableFuture<StoredMap> publication = publications.get(origin.id());
        if (publication == null) return CompletableFuture.failedFuture(new IllegalStateException("no source map publication for " + origin));
        // 发布 Future 完成后才写负数引用, 接收服此时已经能查到对应记录
        return publication.thenApply(map -> {
            if (!map.identity().source().equals(new MapSource(origin.ownerId(), origin.id()))) {
                throw new IllegalArgumentException("published map origin mismatch");
            }
            return this.withId(components, map.identity().globalId());
        });
    }

    @Override
    @NotNull
    public CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
        if (!(components.get("minecraft:map_id") instanceof IntTag id)) return CompletableFuture.failedFuture(new IllegalArgumentException("SYNC map has no integer global id"));
        MapIdentity identity = new MapIdentity(new MapSource(origin.ownerId(), origin.id()), id.getAsInt());
        // 接收结果可能是外服负数 ID, 也可能是回源后找到的原始 ID
        return this.receiver.receive(identity).thenApply(localId -> localId == identity.globalId() ? components : this.withId(components, localId));
    }

    @Override
    @NotNull
    public CompletableFuture<CompoundTag> forwardAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin) {
        if (!(components.get("minecraft:map_id") instanceof IntTag id)) return CompletableFuture.failedFuture(new IllegalArgumentException("SYNC map has no integer global id"));
        MapIdentity identity = new MapIdentity(new MapSource(origin.ownerId(), origin.id()), id.getAsInt());
        return this.receiver.touch(identity.globalId()).thenApply(ignored -> components);
    }

    // 替换地图 ID 并保留其他组件的共享引用.
    private CompoundTag withId(CompoundTag components, int id) {
        CompoundTag result = new CompoundTag(new HashMap<>(components.tags));
        result.putInt("minecraft:map_id", id);
        return result;
    }
}
