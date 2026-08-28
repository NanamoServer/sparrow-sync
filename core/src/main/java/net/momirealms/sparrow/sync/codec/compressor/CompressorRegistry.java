package net.momirealms.sparrow.sync.codec.compressor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public enum CompressorRegistry implements Compressor {
    NONE(new NoneCompressor()),             // 明文存储. 快照越大数据库传输越慢.
    DEFLATE(new DeflateCompressor()),       // 内置 Deflate, 不依赖 native 库, 性能和速度都不好.
    SPEED(new ZstdCompressor(ZstdCompressor.DEFAULT_LEVEL)),    // Zstd 默认策略, 压缩比 DEFLATE 快约 7 倍, 解压快 3 倍以上.
    SIZE(new ZstdCompressor(ZstdCompressor.SIZE_LEVEL));        // Zstd Level 12, 比 DEFLATE 再小 8-13%, 但是速度较慢.

    private static final Map<Byte, Compressor> BY_ID = new ConcurrentHashMap<>(); // 帧头 id -> 解码器

    static {
        CompressorRegistry[] values = values();
        for (int i = 0; i < values.length; i++) {
            BY_ID.putIfAbsent(values[i].id(), values[i]);
        }
    }

    private final Compressor delegate;

    CompressorRegistry(Compressor delegate) {
        this.delegate = delegate;
    }

    /**
     * 注册内置之外的压缩算法, 使解码方能按字节头中的 id 找到它.
     *
     * @throws IllegalStateException 当该 id 已被注册时
     */
    public static void register(@NotNull Compressor compressor) {
        Compressor existing = BY_ID.putIfAbsent(compressor.id(), compressor);
        if (existing != null) {
            throw new IllegalStateException("compressor id already registered: " + compressor.id());
        }
    }

    /**
     * 按字节头中的算法标识查找压缩器, 未注册的标识返回 null.
     */
    @Nullable
    public static Compressor byId(byte id) {
        return BY_ID.get(id);
    }

    @Override
    public byte id() {
        return this.delegate.id();
    }

    @Override
    public byte @NotNull [] compress(byte @NotNull [] data) throws IOException {
        return this.delegate.compress(data);
    }

    @Override
    public byte @NotNull [] decompress(byte @NotNull [] data, int offset, int length, int sizeLimit) throws IOException {
        return this.delegate.decompress(data, offset, length, sizeLimit);
    }
}
