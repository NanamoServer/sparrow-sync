package net.momirealms.sparrow.sync.map.handler;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.map.MapOrigin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public interface MapHandler {
    @NotNull
    MapType type();

    /** 编译本服原图的组件. <strong>输入只读, 来源标记由管线写入</strong>. */
    @NotNull
    CompoundTag compile(@NotNull CompoundTag components, @NotNull MapOrigin origin);

    /** 应用已准备好的接收结果. <strong>输入只读; 实际恢复原始 ID 由管线清理来源标记</strong>. */
    @NotNull
    CompoundTag decode(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId);
}
