package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Set;

/**
 * 一类玩家数据的声明、采集、编解码与应用, 也是 {@link net.momirealms.sparrow.sync.snapshot.DataRegistry} 唯一接受的注册类型.
 * capture 按采集模式选择读取路径, encode 与 decode 可在任意线程执行, apply 在玩家线程执行.
 * 登录前可异步写入原版数据的类型另行实现 {@link NativePlayerDataType}.
 *
 * @param <T> 采集与解码共享的值类型
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

    /** 在线异步读取必须允许与玩家更新及同步采集重叠. */
    default boolean supportsAsyncCapture() {
        return false;
    }

    /**
     * 按实际执行条件采集玩家数据.
     * <strong>SYNC 与 ASYNC 返回不再引用玩家可变数据的独立采集数据; OFFLINE 可借用 Quit 后已静止的数据</strong>.
     */
    @NotNull
    T capture(@NotNull Player player, @NotNull CaptureMode mode);

    /**
     * 把采集值编码为快照 NBT, 可在任意线程调用.
     * <strong>输出必须脱离玩家的可变数据, 编码不得修改输入的游戏状态</strong>.
     */
    @NotNull
    Tag encode(@NotNull T value);

    /**
     * 解码并校验快照中的数据, 可在任意线程调用.
     *
     * @param mcDataVersion 快照记录的 Minecraft data version, 物品类数据据此做跨版本升级
     * @throws IOException 当数据损坏或不符合本类型的结构时
     */
    @NotNull
    T decode(@NotNull Tag data, int mcDataVersion) throws IOException;

    /**
     * 把解码后的值应用到玩家.
     * <strong>必须在玩家线程上调用</strong>.
     */
    void apply(@NotNull Player player, @NotNull T value);
}
