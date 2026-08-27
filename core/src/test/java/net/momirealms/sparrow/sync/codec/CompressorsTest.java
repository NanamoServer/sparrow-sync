package net.momirealms.sparrow.sync.codec;

import net.momirealms.sparrow.sync.codec.compressor.Compressor;
import net.momirealms.sparrow.sync.codec.compressor.Compressors;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressorsTest {
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    @Test
    void deflateRoundTripRestoresOriginalBytes() throws IOException {
        // 准备: 高重复度数据, 压缩应当明显缩小
        byte[] data = "sparrow-sync".repeat(200).getBytes();
        // 执行
        byte[] compressed = Compressors.DEFLATE.compress(data);
        byte[] restored = Compressors.DEFLATE.decompress(compressed, 0, compressed.length, NO_LIMIT);
        // 断言
        assertArrayEquals(data, restored);
        assertTrue(compressed.length < data.length);
    }

    @Test
    void deflateRoundTripRestoresRandomBytes() throws IOException {
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);
        byte[] compressed = Compressors.DEFLATE.compress(data);
        assertArrayEquals(data, Compressors.DEFLATE.decompress(compressed, 0, compressed.length, NO_LIMIT));
    }

    @Test
    void deflateDecompressHonorsOffsetAndLength() throws IOException {
        // 载荷前后各垫 3 字节, 模拟帧头场景
        byte[] data = "offset-test".repeat(50).getBytes();
        byte[] compressed = Compressors.DEFLATE.compress(data);
        byte[] padded = new byte[compressed.length + 6];
        System.arraycopy(compressed, 0, padded, 3, compressed.length);

        assertArrayEquals(data, Compressors.DEFLATE.decompress(padded, 3, compressed.length, NO_LIMIT));
    }

    @Test
    void deflateRejectsOversizedDecompressedPayload() throws IOException {
        // 1MB 零字节压缩后极小, 解压时超过 64KB 上限必须失败而不是继续分配
        byte[] bomb = Compressors.DEFLATE.compress(new byte[1024 * 1024]);

        assertThrows(IOException.class, () -> Compressors.DEFLATE.decompress(bomb, 0, bomb.length, 64 * 1024));
    }

    @Test
    void noneCompressorPassesBytesThrough() throws IOException {
        byte[] data = {1, 2, 3};
        assertArrayEquals(data, Compressors.NONE.compress(data));
        assertArrayEquals(data, Compressors.NONE.decompress(data, 0, data.length, NO_LIMIT));
    }

    @Test
    void noneCompressorRejectsPayloadOverLimit() {
        byte[] data = new byte[128];
        assertThrows(IOException.class, () -> Compressors.NONE.decompress(data, 0, data.length, 64));
    }

    @Test
    void byIdResolvesKnownIdsAndRejectsUnknown() {
        assertSame(Compressors.NONE, Compressors.byId((byte) 0));
        assertSame(Compressors.DEFLATE, Compressors.byId((byte) 1));
        assertNull(Compressors.byId((byte) 9));
    }

    @Test
    void registerRejectsDuplicateId() {
        Compressor duplicate = new Compressor() {
            @Override
            public byte id() {
                return 1;
            }

            @Override
            public byte @NotNull [] compress(byte @NotNull [] data) {
                return data;
            }

            @Override
            public byte @NotNull [] decompress(byte @NotNull [] data, int offset, int length, int sizeLimit) {
                return data;
            }
        };
        assertThrows(IllegalStateException.class, () -> Compressors.register(duplicate));
    }
}
