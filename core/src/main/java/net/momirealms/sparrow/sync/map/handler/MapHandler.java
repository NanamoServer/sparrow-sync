package net.momirealms.sparrow.sync.map.handler;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.map.data.MapOrigin;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public interface MapHandler {
    @NotNull
    MapType type();

    /** 等待模式所需的数据准备后编码本服的来源地图物品. <strong>输入只读, 来源标记由管线写入</strong>. */
    @NotNull
    CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull Map<Integer, CompletableFuture<StoredMap>> publications);

    /** 等待接收数据就绪后生成本服组件. <strong>输入只读, 实际恢复来源地图 ID 后由管线清理来源标记</strong>. */
    @NotNull
    CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId);

    /** 处理已有模式的中转地图. renewals 由本次物品编码独占, 同一全局 ID 共享续期结果, 各物品组件仍独立处理. */
    @NotNull
    default CompletableFuture<CompoundTag> forwardAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull Map<Integer, CompletableFuture<Boolean>> renewals) {
        return CompletableFuture.completedFuture(components);
    }
}
