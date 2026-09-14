package net.momirealms.sparrow.sync.snapshot.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.function.Supplier;

/** 通过 DFU Codec 转换值对象和 NBT, 子类负责玩家数据的采集与应用. */
public abstract class CodecDataType<T> implements PlayerDataType<T> {
    private final DataKey key;
    private final Codec<T> codec;
    private final Supplier<DynamicOps<Tag>> ops;

    /** 使用普通 NBTOps, 不需要服务器注册表. */
    protected CodecDataType(@NotNull DataKey key, @NotNull Codec<T> codec) {
        this(key, codec, () -> NBTOps.INSTANCE);
    }

    /**
     * 为物品等带注册表引用的类型提供 ops.
     * <strong>ops 延迟到使用时获取, 此时服务器须已就绪</strong>.
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

    /** 遵循 {@link PlayerDataType#capture(Player, CaptureMode)} 的线程和数据引用约定采集. */
    @NotNull
    protected abstract T captureValue(@NotNull Player player, @NotNull CaptureMode mode);

    /** 将值应用到玩家. */
    protected abstract void applyValue(@NotNull Player player, @NotNull T value);
}
