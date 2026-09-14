package net.momirealms.sparrow.sync.snapshot.codec.compressor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public enum CompressorRegistry implements Compressor {
    NONE((byte) 0, new NoneCompressor()),        // 不压缩
    DEFLATE((byte) 1, new DeflateCompressor()),  // JDK 内置 Deflate
    ZSTD((byte) 2, new ZstdCompressor(ZstdCompressor.DEFAULT_LEVEL));   // Zstandard 压缩

    private static final Map<Byte, Compressor> BY_ID = new ConcurrentHashMap<>(); // 块头算法 ID 对应的解码器

    static {
        CompressorRegistry[] values = values();
        for (int i = 0; i < values.length; i++) {
            register(values[i].id, values[i]);
        }
    }

    private final byte id;
    private final Compressor delegate;

    CompressorRegistry(byte id, Compressor delegate) {
        this.id = id;
        this.delegate = delegate;
    }

    /**
     * 注册额外的解压算法, 写入时使用的算法仍由配置选择.
     * @throws IllegalStateException 算法 ID 已注册时
     */
    public static void register(byte id, @NotNull Compressor compressor) {
        Compressor existing = BY_ID.putIfAbsent(id, compressor);
        if (existing != null) {
            throw new IllegalStateException("compressor id already registered: " + id);
        }
    }

    /** 按算法 ID 查找压缩器, 未注册时返回 null. */
    @Nullable
    public static Compressor byId(byte id) {
        return BY_ID.get(id);
    }

    public byte id() {
        return this.id;
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
