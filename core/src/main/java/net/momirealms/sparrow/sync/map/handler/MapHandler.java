package net.momirealms.sparrow.sync.map.handler;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.map.MapOrigin;
import net.momirealms.sparrow.sync.map.StoredMap;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public interface MapHandler {
    @NotNull
    MapType type();

    /** 编译本服原图的组件. <strong>输入只读, 来源标记由管线写入</strong>. */
    @NotNull
    default CompoundTag compile(@NotNull CompoundTag components, @NotNull MapOrigin origin) {
        throw new UnsupportedOperationException("map handler requires asynchronous compilation");
    }

    /** 应用已准备好的接收结果. <strong>输入只读; 实际恢复原始 ID 由管线清理来源标记</strong>. */
    @NotNull
    default CompoundTag decode(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
        throw new UnsupportedOperationException("map handler requires asynchronous decoding");
    }

    @NotNull
    default CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull Map<Integer, CompletableFuture<StoredMap>> captured) {
        return CompletableFuture.completedFuture(this.compile(components, origin));
    }

    @NotNull
    default CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
        return CompletableFuture.completedFuture(this.decode(components, origin, ownerId));
    }

    /** 中转不重新登记来源, 可续期已有共享缓存. */
    @NotNull
    default CompletableFuture<CompoundTag> forwardAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin) {
        return CompletableFuture.completedFuture(components);
    }
}
