package net.momirealms.sparrow.sync.data;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.DataDeclaration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 一类玩家数据的采集与应用, 声明 (key, 存储形态, 失败等级, 依赖) 与行为合一.
 * 生命周期分三段: capture 与 apply 只能在玩家的拥有线程上执行,
 * decode 可在任意线程完成解码与校验, 让应用前的预检不占用主线程.
 *
 * @param <T> 解码后的值类型
 */
public interface PlayerDataType<T> extends DataDeclaration {

    /**
     * 校验当前线程可以读写该玩家, Paper 上为主线程, Folia 上为玩家所在区域线程.
     * Folia 的任意 tick 线程都会让 isPrimaryThread 为真, 判定必须落在 isOwnedByCurrentRegion 上.
     *
     * @throws IllegalStateException 当前线程不拥有该玩家时
     */
    static void ensureOwningThread(@NotNull Player player) {
        if (Bukkit.isOwnedByCurrentRegion(player)) return;
        throw new IllegalStateException("player data of " + player.getName() + " must be accessed on its owning thread");
    }

    /**
     * 从玩家身上采集当前数据并编码为 NBT.
     * <strong>必须在玩家线程上调用</strong>.
     */
    @NotNull
    Tag capture(@NotNull Player player);

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
