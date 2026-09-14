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
import java.util.function.IntFunction;

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
    public CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull IntFunction<CompletableFuture<StoredMap>> publish) {
        CompletableFuture<StoredMap> publication = publish.apply(origin.id());
        // 发布完成后才写入负数 ID, 确保接收服能查到记录.
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
        // 回到来源服且原地图存在时恢复原 ID, 否则使用负数 ID 的副本.
        return this.receiver.receive(identity).thenApply(localId -> localId == identity.globalId() ? components : this.withId(components, localId));
    }

    @Override
    @NotNull
    public CompletableFuture<CompoundTag> forwardAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull Map<Integer, CompletableFuture<Boolean>> renewals) {
        if (!(components.get("minecraft:map_id") instanceof IntTag id)) return CompletableFuture.failedFuture(new IllegalArgumentException("SYNC map has no integer global id"));
        MapIdentity identity = new MapIdentity(new MapSource(origin.ownerId(), origin.id()), id.getAsInt());
        return renewals.computeIfAbsent(identity.globalId(), idToRenew -> {
            try {
                return this.receiver.touch(idToRenew);
            } catch (RuntimeException exception) {
                // 提交 Redis 请求时的异常也作为共享结果, 各物品分别告警并保留原内容.
                return CompletableFuture.failedFuture(exception);
            }
        }).thenApply(ignored -> components);
    }

    // 只替换地图 ID, 其他组件仍共用原引用.
    private CompoundTag withId(CompoundTag components, int id) {
        CompoundTag result = new CompoundTag(new HashMap<>(components.tags));
        result.putInt("minecraft:map_id", id);
        return result;
    }
}
