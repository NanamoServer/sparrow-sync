package net.momirealms.sparrow.sync.snapshot.codec.compressor;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.dependency.Dependencies;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;

public final class ZstdCompressor implements Compressor {
    public static final int DEFAULT_LEVEL = 3;

    private final int level;

    public ZstdCompressor(int level) {
        this.level = level;
    }

    @Override
    public byte @NotNull [] compress(byte @NotNull [] data) throws IOException {
        try {
            return (byte[]) ZstdAccess.COMPRESS.invokeExact(data, this.level);
        } catch (Throwable exception) {
            throw new IOException("zstd compression failed", exception);
        }
    }

    @Override
    public byte @NotNull [] decompress(byte @NotNull [] data, int offset, int length, int sizeLimit) throws IOException {
        // 按帧头声明的大小分配结果数组, 解压时以数组容量为上限
        try {
            long contentSize = (long) ZstdAccess.FRAME_CONTENT_SIZE.invokeExact(data, offset, length);
            if (contentSize < 0) {
                throw new IOException("zstd frame header unreadable or carries no content size (code " + contentSize + ")");
            }
            if (contentSize > sizeLimit) {
                throw new IOException("decompressed size " + contentSize + " exceeds limit " + sizeLimit);
            }
            byte[] out = new byte[(int) contentSize];
            ZstdAccess.DECOMPRESS.invokeExact(out, 0, out.length, data, offset, length);
            return out;
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IOException("zstd decompression failed", exception);
        }
    }

    // 配置会在依赖下载前构造压缩器, 首次压缩或解压时才加载 Zstd 并缓存句柄
    private static final class ZstdAccess {
        private static final MethodHandle COMPRESS;
        private static final MethodHandle FRAME_CONTENT_SIZE;
        private static final MethodHandle DECOMPRESS;

        static {
            ClassLoader classLoader = SparrowSync.instance().dependencyManager().obtainClassLoaderWith(Set.of(Dependencies.ZSTD_JNI));
            try {
                Class<?> zstdClass = classLoader.loadClass("com.github.luben.zstd.Zstd");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                COMPRESS = lookup.findStatic(zstdClass, "compress", MethodType.methodType(byte[].class, byte[].class, int.class));
                FRAME_CONTENT_SIZE = lookup.findStatic(zstdClass, "getFrameContentSize", MethodType.methodType(long.class, byte[].class, int.class, int.class));
                DECOMPRESS = lookup.findStatic(zstdClass, "decompressByteArray", MethodType.methodType(long.class, byte[].class, int.class, int.class, byte[].class, int.class, int.class))
                        .asType(MethodType.methodType(void.class, byte[].class, int.class, int.class, byte[].class, int.class, int.class));
            } catch (ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }
}
