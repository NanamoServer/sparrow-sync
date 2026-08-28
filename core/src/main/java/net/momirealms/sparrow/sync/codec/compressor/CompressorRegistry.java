package net.momirealms.sparrow.sync.codec.compressor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public enum CompressorRegistry implements Compressor {
    NONE((byte) 0, new NoneCompressor()),        // 明文存储. 快照越大数据库传输越慢.
    DEFLATE((byte) 1, new DeflateCompressor()),  // 内置 Deflate, 不依赖 native 库, 性能和速度都不好.
    ZSTD((byte) 2, new ZstdCompressor(ZstdCompressor.DEFAULT_LEVEL));   // 压缩比 DEFLATE 快约 7 倍, 解压快 3 倍以上.

    private static final Map<Byte, Compressor> BY_ID = new ConcurrentHashMap<>(); // 帧头 id -> 解码器

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
     * 登记内置之外的压缩算法, 使本服能读到用它写出的帧. 写出用哪个由配置在本枚举里选, 因此登记只服务解码侧.
     *
     * @throws IllegalStateException 当该 id 已被登记时
     */
    public static void register(byte id, @NotNull Compressor compressor) {
        Compressor existing = BY_ID.putIfAbsent(id, compressor);
        if (existing != null) {
            throw new IllegalStateException("compressor id already registered: " + id);
        }
    }

    /**
     * 按字节头中的算法标识查找压缩器, 未登记的标识返回 null.
     */
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
