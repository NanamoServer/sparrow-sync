package net.momirealms.sparrow.sync.map.handler;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.map.data.MapOrigin;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

@ApiStatus.Internal
public final class HideMapHandler implements MapHandler {
    @Override
    @NotNull
    public MapType type() {
        return MapType.HIDE;
    }

    @Override
    @NotNull
    public CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull IntFunction<CompletableFuture<StoredMap>> publish) {
        CompoundTag hidden = new CompoundTag(new HashMap<>(components.tags));
        hidden.remove("minecraft:map_id");
        return CompletableFuture.completedFuture(hidden);
    }

    @Override
    @NotNull
    public CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
        if (!ownerId.equals(origin.ownerId())) return CompletableFuture.completedFuture(components);
        CompoundTag restored = new CompoundTag(new HashMap<>(components.tags));
        restored.putInt("minecraft:map_id", origin.id());
        return CompletableFuture.completedFuture(restored);
    }
}
