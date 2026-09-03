package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Set;

/**
 * 一类玩家数据的声明、采集、编解码与应用, 也是 {@link net.momirealms.sparrow.sync.snapshot.DataRegistry} 唯一接受的注册类型.
 * capture 可在玩家线程或玩家串行线程执行, encode 与 decode 可在任意线程执行, apply 在玩家拥有线程执行.
 * 登录前可异步写入原版玩家文件的类型另行实现 {@link NativePlayerDataType}.
 * todo 也许采集, 编码, 解码可以带当前目标玩家的会话状态, 例如玩家离开服务器之后的采集可以直接安全返回目标容器而不走 copy
 *
 * @param <T> 采集与解码共享的值类型
 */
public interface PlayerDataType<T> {

    @NotNull
    DataKey key();

    /**
     * 文档存储中的排布形态.
     */
    @NotNull
    StorageFormat storage();

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

    /**
     * 从玩家身上采集脱离值.
     * <strong>实现必须同时支持玩家拥有线程与玩家串行线程调用, 返回值不得继续引用玩家的可变数据</strong>.
     */
    @NotNull
    T capture(@NotNull Player player);

    /**
     * 把采集值编码为快照 NBT, 可在任意线程调用.
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
