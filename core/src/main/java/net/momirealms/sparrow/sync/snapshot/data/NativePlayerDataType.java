package net.momirealms.sparrow.sync.snapshot.data;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.UUID;

/**
 * 可以在登录 Gate worker 准备原版登录数据的类型.
 * 实现不得读取玩家或派发 Bukkit 事件; 所需全局元数据必须通过线程安全的快照读取.
 *
 * @param <T> 解码后的值类型
 */
@ApiStatus.Internal
public interface NativePlayerDataType<T> extends PlayerDataType<T> {

    /**
     * 把本类型安装到原版登录读取的数据源.
     * <strong>返回 {@link NativeApplyResult#NOT_APPLIED} 或抛出异常时不得留下部分可见的写入</strong>.
     *
     * @return 应用结果, 用于决定槽位状态以及 synthetic player data 是否需要发布
     * @throws IOException 当外部原生数据源写入失败时
     */
    @NotNull
    NativeApplyResult applyNative(@NotNull UUID player, @NotNull CompoundTag playerData, @NotNull T value) throws IOException;

    enum NativeApplyResult {
        NOT_APPLIED,
        APPLIED_PLAYER_DATA,
        APPLIED_EXTERNAL
    }
}
