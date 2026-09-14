package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.Tag;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Set;

/**
 * 定义一类玩家数据的采集、编解码和应用.
 * encode 和 decode 可在任意线程执行, apply 在玩家线程执行; 登录前应用另实现 {@link NativePlayerDataType}.
 * @param <T> 采集和解码使用的值类型
 */
public interface PlayerDataType<T> {

    @NotNull
    DataKey key();

    /**
     * 是否视为关键数据, 关键数据在采集、解码或应用阶段失败都会中止当前同步流程.
     */
    default boolean critical() {
        return false;
    }

    /**
     * 应用顺序上先于本类型的其他数据, 未注册的依赖在排序时被忽略.
     */
    @NotNull
    default Set<DataKey> dependencies() {
        return Set.of();
    }

    /** 在线异步采集须允许与玩家更新和同步采集同时发生. */
    default boolean supportsAsyncCapture() {
        return false;
    }

    /**
     * 按采集模式读取玩家数据.
     * <strong>SYNC 和 ASYNC 必须返回脱离玩家可变状态的值; OFFLINE 可引用退出后不再变化的数据</strong>.
     */
    @NotNull
    T capture(@NotNull Player player, @NotNull CaptureMode mode);

    /**
     * 将采集值编码为 NBT, 可在任意线程调用.
     * <strong>输出须脱离玩家可变数据, 不得修改输入中的游戏状态</strong>.
     */
    @NotNull
    Tag encode(@NotNull T value);

    /**
     * 解码并校验快照中的数据, 可在任意线程调用.
     * 涉及 Minecraft 格式升级的类型在自身 Tag 中保存并读取数据版本.
     *
     * @param data 本类型编码的完整 Tag, 包含解码所需的版本信息
     * @throws IOException 当数据损坏或不符合本类型的结构时
     */
    @NotNull
    T decode(@NotNull Tag data) throws IOException;

    /**
     * 把解码后的值应用到玩家.
     * <strong>必须在玩家线程上调用</strong>.
     */
    void apply(@NotNull Player player, @NotNull T value);
}
