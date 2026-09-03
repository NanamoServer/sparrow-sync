package net.momirealms.sparrow.sync.snapshot.data;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 可以在登录 Gate 的异步阶段直接写入原版玩家数据的类型.
 * 实现不得读取玩家或派发 Bukkit 事件; 所需全局元数据必须通过线程安全的快照读取.
 *
 * @param <T> 解码后的值类型
 */
@ApiStatus.Internal
public interface NativePlayerDataType<T> extends PlayerDataType<T> {

    /**
     * 在 Gate worker 上把本类型写入原版玩家数据.
     * 实现应先完成子树转换再安装到 {@code playerData}; 返回 {@code false} 时不得改变传入 tag.
     *
     * @return 本次值是否已经完整写入原版玩家数据
     */
    boolean applyNative(@NotNull CompoundTag playerData, @NotNull T value);
}
