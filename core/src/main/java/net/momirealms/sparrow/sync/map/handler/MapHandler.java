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

    /** 等待模式所需的数据准备后编译本服原图. <strong>输入只读, 来源标记由管线写入</strong>. */
    @NotNull
    CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull Map<Integer, CompletableFuture<StoredMap>> publications);

    /** 等待接收数据就绪后生成本服组件. <strong>输入只读, 实际恢复原始 ID 后由管线清理来源标记</strong>. */
    @NotNull
    CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId);

    /** 处理已经带有模式的中转地图, 默认保留组件. SYNC 可在此续期共享缓存, 来源身份继续沿用物品标记. */
    @NotNull
    default CompletableFuture<CompoundTag> forwardAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin) {
        return CompletableFuture.completedFuture(components);
    }
}
