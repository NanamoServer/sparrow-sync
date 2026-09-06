package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.nbt.CompoundTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public interface MapHandler {
    @NotNull
    MapType type();

    /** 编译本服原图的组件. <strong>输入只读, 来源标记由管线写入</strong>. */
    @NotNull
    CompoundTag compile(@NotNull CompoundTag components, @NotNull MapOrigin origin);

    /** 接收地图并按来源决定应用行为. <strong>输入只读, 回源后的来源标记由管线清理</strong>. */
    @NotNull
    CompoundTag decode(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId);
}
