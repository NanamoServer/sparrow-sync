package net.momirealms.sparrow.sync.codec;

import net.momirealms.sparrow.sync.snapshot.codec.compressor.Compressor;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.ZstdCompressor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressorRegistryTest {
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    @Test
    void deflateRoundTripRestoresOriginalBytes() throws IOException {
        // 准备: 高重复度数据, 压缩应当明显缩小
        byte[] data = "sparrow-sync".repeat(200).getBytes();
        // 执行
        byte[] compressed = CompressorRegistry.DEFLATE.compress(data);
        byte[] restored = CompressorRegistry.DEFLATE.decompress(compressed, 0, compressed.length, NO_LIMIT);
        // 断言
        assertArrayEquals(data, restored);
        assertTrue(compressed.length < data.length);
    }

    @Test
    void deflateRoundTripRestoresRandomBytes() throws IOException {
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);
        byte[] compressed = CompressorRegistry.DEFLATE.compress(data);
        assertArrayEquals(data, CompressorRegistry.DEFLATE.decompress(compressed, 0, compressed.length, NO_LIMIT));
    }

    @Test
    void deflateDecompressHonorsOffsetAndLength() throws IOException {
        // 载荷前后各垫 3 字节, 模拟帧头场景
        byte[] data = "offset-test".repeat(50).getBytes();
        byte[] compressed = CompressorRegistry.DEFLATE.compress(data);
        byte[] padded = new byte[compressed.length + 6];
        System.arraycopy(compressed, 0, padded, 3, compressed.length);

        assertArrayEquals(data, CompressorRegistry.DEFLATE.decompress(padded, 3, compressed.length, NO_LIMIT));
    }

    @Test
    void deflateRejectsOversizedDecompressedPayload() throws IOException {
        // 1MB 零字节压缩后极小, 解压时超过 64KB 上限必须失败而不是继续分配
        byte[] bomb = CompressorRegistry.DEFLATE.compress(new byte[1024 * 1024]);

        assertThrows(IOException.class, () -> CompressorRegistry.DEFLATE.decompress(bomb, 0, bomb.length, 64 * 1024));
    }

    @Test
    void noneCompressorPassesBytesThrough() throws IOException {
        byte[] data = {1, 2, 3};
        assertArrayEquals(data, CompressorRegistry.NONE.compress(data));
        assertArrayEquals(data, CompressorRegistry.NONE.decompress(data, 0, data.length, NO_LIMIT));
    }

    @Test
    void noneCompressorRejectsPayloadOverLimit() {
        byte[] data = new byte[128];
        assertThrows(IOException.class, () -> CompressorRegistry.NONE.decompress(data, 0, data.length, 64));
    }

    @Test
    void zstdRoundTripRestoresOriginalBytes() throws IOException {
        // 准备: 高重复度数据, 压缩应当明显缩小
        byte[] data = "sparrow-sync".repeat(200).getBytes();
        // 执行
        byte[] compressed = CompressorRegistry.ZSTD.compress(data);
        byte[] restored = CompressorRegistry.ZSTD.decompress(compressed, 0, compressed.length, NO_LIMIT);
        // 断言
        assertArrayEquals(data, restored);
        assertTrue(compressed.length < data.length);
    }

    @Test
    void zstdRoundTripRestoresRandomBytes() throws IOException {
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);
        byte[] compressed = CompressorRegistry.ZSTD.compress(data);
        assertArrayEquals(data, CompressorRegistry.ZSTD.decompress(compressed, 0, compressed.length, NO_LIMIT));
    }

    @Test
    void zstdDecompressHonorsOffsetAndLength() throws IOException {
        // 载荷前后各垫 3 字节, 模拟帧头场景
        byte[] data = "offset-test".repeat(50).getBytes();
        byte[] compressed = CompressorRegistry.ZSTD.compress(data);
        byte[] padded = new byte[compressed.length + 6];
        System.arraycopy(compressed, 0, padded, 3, compressed.length);

        assertArrayEquals(data, CompressorRegistry.ZSTD.decompress(padded, 3, compressed.length, NO_LIMIT));
    }

    @Test
    void zstdRejectsOversizedDecompressedPayload() throws IOException {
        // 1MB 零字节压缩后极小, 帧头声明的原始大小超过 64KB 上限必须在分配前失败
        byte[] bomb = CompressorRegistry.ZSTD.compress(new byte[1024 * 1024]);

        assertThrows(IOException.class, () -> CompressorRegistry.ZSTD.decompress(bomb, 0, bomb.length, 64 * 1024));
    }

    @Test
    void zstdCorruptedMagicFailsAsIOException() throws IOException {
        // 帧头 magic 被清零后 content size 不可读, 必须以接口承诺的 IOException 失败
        byte[] compressed = CompressorRegistry.ZSTD.compress("corrupt-me".repeat(100).getBytes());
        for (int i = 0; i < 4; i++) {
            compressed[i] = 0;
        }

        assertThrows(IOException.class, () -> CompressorRegistry.ZSTD.decompress(compressed, 0, compressed.length, NO_LIMIT));
    }

    @Test
    void zstdTruncatedFrameFailsAsIOException() throws IOException {
        // 尾部截断在 native 侧以 unchecked 异常浮出, 实现必须转为 IOException 而不是任其逃逸
        byte[] compressed = CompressorRegistry.ZSTD.compress("truncate-me".repeat(100).getBytes());

        assertThrows(IOException.class, () -> CompressorRegistry.ZSTD.decompress(compressed, 0, compressed.length - 8, NO_LIMIT));
    }

    @Test
    void zstdEmptyPayloadFailsAsIOException() {
        // 只有帧头没有载荷的字节 (offset 越过数组末尾, length 为 0), 对应 4 字节裸帧头的解帧场景
        byte[] headerOnly = new byte[4];

        assertThrows(IOException.class, () -> CompressorRegistry.ZSTD.decompress(headerOnly, 4, 0, NO_LIMIT));
    }

    @Test
    void deflateTruncatedStreamFailsAsIOException() throws IOException {
        byte[] compressed = CompressorRegistry.DEFLATE.compress("truncate-me".repeat(100).getBytes());

        assertThrows(IOException.class, () -> CompressorRegistry.DEFLATE.decompress(compressed, 0, compressed.length - 8, NO_LIMIT));
    }

    @Test
    void zstdLeveledFramesStayMutuallyReadable() throws IOException {
        // 级别只影响编码, 高级别实例压出的帧必须能被注册表里的默认实例解开
        byte[] data = "leveled-frame".repeat(100).getBytes();
        byte[] compressed = new ZstdCompressor(19).compress(data);

        assertArrayEquals(data, CompressorRegistry.ZSTD.decompress(compressed, 0, compressed.length, NO_LIMIT));
    }

    @Test
    void everyValueWritesFramesTheRegistryCanReadBack() {
        // 每个取值写出的帧都要能按 id 找回它自己, 否则用户一改配置就读不了自己刚写的数据
        CompressorRegistry[] values = CompressorRegistry.values();
        for (int i = 0; i < values.length; i++) {
            Compressor decoder = CompressorRegistry.byId(values[i].id());
            assertNotNull(decoder, values[i] + " writes frames no registered compressor can read");
            assertSame(values[i], decoder, values[i] + " resolves to another compressor");
        }
    }

    @Test
    void byIdResolvesKnownIdsAndRejectsUnknown() {
        assertSame(CompressorRegistry.NONE, CompressorRegistry.byId((byte) 0));
        assertSame(CompressorRegistry.DEFLATE, CompressorRegistry.byId((byte) 1));
        assertSame(CompressorRegistry.ZSTD, CompressorRegistry.byId((byte) 2));
        assertNull(CompressorRegistry.byId((byte) 9));
    }

    @Test
    void registerRejectsDuplicateId() {
        Compressor duplicate = new Compressor() {
            @Override
            public byte @NotNull [] compress(byte @NotNull [] data) {
                return data;
            }

            @Override
            public byte @NotNull [] decompress(byte @NotNull [] data, int offset, int length, int sizeLimit) {
                return data;
            }
        };
        assertThrows(IllegalStateException.class, () -> CompressorRegistry.register(CompressorRegistry.DEFLATE.id(), duplicate));
    }
}
