package net.momirealms.sparrow.sync.codec.compressor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 压缩算法注册表, 内置 NONE(id 0) 与 DEFLATE(id 1). id 2 预留给 Zstd, 待 Q4 裁决后注册.
 */
public final class Compressors {
    private static final Map<Byte, Compressor> REGISTRY = new ConcurrentHashMap<>();

    public static final Compressor NONE = new NoneCompressor();
    public static final Compressor DEFLATE = new DeflateCompressor();

    static {
        register(NONE);
        register(DEFLATE);
    }

    private Compressors() {
    }

    /**
     * 注册一个压缩算法, 使解码方能按字节头中的 id 找到它.
     *
     * @throws IllegalStateException 当该 id 已被注册时
     */
    public static void register(@NotNull Compressor compressor) {
        Compressor existing = REGISTRY.putIfAbsent(compressor.id(), compressor);
        if (existing != null) {
            throw new IllegalStateException("compressor id already registered: " + compressor.id());
        }
    }

    /**
     * 按字节头中的算法标识查找压缩器, 未注册的标识返回 null.
     */
    @Nullable
    public static Compressor byId(byte id) {
        return REGISTRY.get(id);
    }

    private static final class NoneCompressor implements Compressor {

        @Override
        public byte id() {
            return 0;
        }

        @Override
        public byte @NotNull [] compress(byte @NotNull [] data) {
            return data;
        }

        @Override
        public byte @NotNull [] decompress(byte @NotNull [] data, int offset, int length, int sizeLimit) throws IOException {
            if (length > sizeLimit) {
                throw new IOException("payload size " + length + " exceeds limit " + sizeLimit);
            }
            byte[] out = new byte[length];
            System.arraycopy(data, offset, out, 0, length);
            return out;
        }
    }

    private static final class DeflateCompressor implements Compressor {

        @Override
        public byte id() {
            return 1;
        }

        @Override
        public byte @NotNull [] compress(byte @NotNull [] data) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(32, data.length / 3));
            try (DeflaterOutputStream stream = new DeflaterOutputStream(bytes)) {
                stream.write(data);
            }
            return bytes.toByteArray();
        }

        @Override
        public byte @NotNull [] decompress(byte @NotNull [] data, int offset, int length, int sizeLimit) throws IOException {
            try (InflaterInputStream stream = new InflaterInputStream(new ByteArrayInputStream(data, offset, length))) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, Math.min(sizeLimit, length * 4)));
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    total += read;
                    // 边解压边计量, 超限立即停止, 解压炸弹不会耗尽内存
                    if (total > sizeLimit) {
                        throw new IOException("decompressed size exceeds limit " + sizeLimit);
                    }
                    bytes.write(buffer, 0, read);
                }
                return bytes.toByteArray();
            }
        }
    }
}
