package net.momirealms.sparrow.sync.snapshot.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * 以 DFU Codec 建模的数据类型基类, 值对象与快照 NBT 互转.
 * 子类实现玩家状态与值对象之间的采集与应用.
 */
public abstract class CodecDataType<T> implements PlayerDataType<T> {
    private final DataKey key;
    private final Codec<T> codec;
    private final Supplier<DynamicOps<Tag>> ops;

    /** 纯数据 codec 用本构造, 在裸 NBTOps 上运行, 不依赖服务器环境. */
    protected CodecDataType(@NotNull DataKey key, @NotNull Codec<T> codec) {
        this(key, codec, () -> NBTOps.INSTANCE);
    }

    /**
     * 含注册表引用的 codec (药水效果, 物品等) 用本构造传入注册表 ops.
     * <strong>ops 惰性求值, 装配发生在启动期而注册表 ops 要求服务器就绪</strong>.
     */
    protected CodecDataType(@NotNull DataKey key, @NotNull Codec<T> codec, @NotNull Supplier<DynamicOps<Tag>> ops) {
        this.key = key;
        this.codec = codec;
        this.ops = ops;
    }

    @Override
    @NotNull
    public final DataKey key() {
        return this.key;
    }

    @Override
    @NotNull
    public final T capture(@NotNull Player player, @NotNull CaptureMode mode) {
        return this.captureValue(player, mode);
    }

    @Override
    @NotNull
    public final Tag encode(@NotNull T value) {
        return this.codec.encodeStart(this.ops.get(), value)
                .getOrThrow(message -> new IllegalStateException("failed to encode " + this.key + ": " + message));
    }

    @Override
    @NotNull
    public final T decode(@NotNull Tag data) throws IOException {
        return this.codec.parse(this.ops.get(), data)
                .getOrThrow(message -> new IOException("failed to decode " + this.key + ": " + message));
    }

    @Override
    public final void apply(@NotNull Player player, @NotNull T value) {
        this.applyValue(player, value);
    }

    /**
     * 按 {@link PlayerDataType#capture(Player, CaptureMode)} 的线程与借用契约读取玩家数据.
     */
    @NotNull
    protected abstract T captureValue(@NotNull Player player, @NotNull CaptureMode mode);

    /**
     * 把值对象写回玩家.
     */
    protected abstract void applyValue(@NotNull Player player, @NotNull T value);
}
