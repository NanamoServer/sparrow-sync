package net.momirealms.sparrow.sync.snapshot.codec;

import com.github.luben.zstd.Zstd;
import net.momirealms.sparrow.sync.plugin.dependency.Dependencies;
import net.momirealms.sparrow.sync.plugin.dependency.classloader.IsolatedClassLoader;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.Compressor;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.ZstdCompressor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class CompressorRegistryTest {
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    @BeforeAll
    static void initializeZstd() throws Exception {
        ZstdTestSupport.initialize();
    }

    @Test
    void zstdUsesAnIsolatedDependencyAndReadsExistingFrames() throws Exception {
        ClassLoader loader = ZstdTestSupport.initialize();
        assertFalse(Dependencies.ZSTD_JNI.autoLoad());
        assertInstanceOf(IsolatedClassLoader.class, loader);
        assertSame(loader, loader.loadClass(Zstd.class.getName()).getClassLoader());
        assertNotSame(Zstd.class, loader.loadClass(Zstd.class.getName()));

        byte[] data = "existing-frame".repeat(100).getBytes();
        byte[] compressed = Zstd.compress(data, 3);
        byte[] padded = new byte[compressed.length + 17];
        System.arraycopy(compressed, 0, padded, 7, compressed.length);
        assertArrayEquals(data, CompressorRegistry.ZSTD.decompress(padded, 7, compressed.length, NO_LIMIT));
        assertArrayEquals(data, Zstd.decompress(CompressorRegistry.ZSTD.compress(data), data.length));
    }

    @Test
    void deflateRejectsOversizedDecompressedPayload() throws IOException {
        byte[] bomb = CompressorRegistry.DEFLATE.compress(new byte[1024 * 1024]);

        assertThrows(IOException.class, () -> CompressorRegistry.DEFLATE.decompress(bomb, 0, bomb.length, 64 * 1024));
    }

    @Test
    void noneCompressorRejectsPayloadOverLimit() {
        byte[] data = new byte[128];
        assertThrows(IOException.class, () -> CompressorRegistry.NONE.decompress(data, 0, data.length, 64));
    }

    @Test
    void zstdRejectsOversizedDecompressedPayload() throws IOException {
        byte[] bomb = CompressorRegistry.ZSTD.compress(new byte[1024 * 1024]);

        assertThrows(IOException.class, () -> CompressorRegistry.ZSTD.decompress(bomb, 0, bomb.length, 64 * 1024));
    }

    @Test
    void zstdCorruptedMagicFailsAsIOException() throws IOException {
        byte[] compressed = CompressorRegistry.ZSTD.compress("corrupt-me".repeat(100).getBytes());
        for (int i = 0; i < 4; i++) {
            compressed[i] = 0;
        }

        assertThrows(IOException.class, () -> CompressorRegistry.ZSTD.decompress(compressed, 0, compressed.length, NO_LIMIT));
    }

    @Test
    void zstdTruncatedFrameFailsAsIOException() throws IOException {
        byte[] compressed = CompressorRegistry.ZSTD.compress("truncate-me".repeat(100).getBytes());

        assertThrows(IOException.class, () -> CompressorRegistry.ZSTD.decompress(compressed, 0, compressed.length - 8, NO_LIMIT));
    }

    @Test
    void zstdEmptyPayloadFailsAsIOException() {
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
        byte[] data = "leveled-frame".repeat(100).getBytes();
        byte[] compressed = new ZstdCompressor(19).compress(data);

        assertArrayEquals(data, CompressorRegistry.ZSTD.decompress(compressed, 0, compressed.length, NO_LIMIT));
    }

    @Test
    void everyValueWritesFramesTheRegistryCanReadBack() {
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
