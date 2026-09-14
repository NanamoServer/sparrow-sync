package net.momirealms.sparrow.sync.map.handler;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.map.data.MapOrigin;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

@ApiStatus.Internal
public interface MapHandler {
    @NotNull
    MapType type();

    /**
     * 编码本服来源地图, 同次编码按 ID 共用 publish 结果.
     * <strong>输入只读, 来源标记由管线写入</strong>.
     */
    @NotNull
    CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull IntFunction<CompletableFuture<StoredMap>> publish);

    /**
     * 等待所需地图就绪后生成本服物品组件.
     * <strong>输入只读, 恢复原地图 ID 后由管线清除来源标记</strong>.
     */
    @NotNull
    CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId);

    /** 按已有模式处理中转地图. renewals 仅供本次编码使用, 同一全局 ID 共用续期结果, 物品组件分别处理. */
    @NotNull
    default CompletableFuture<CompoundTag> forwardAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull Map<Integer, CompletableFuture<Boolean>> renewals) {
        return CompletableFuture.completedFuture(components);
    }
}
