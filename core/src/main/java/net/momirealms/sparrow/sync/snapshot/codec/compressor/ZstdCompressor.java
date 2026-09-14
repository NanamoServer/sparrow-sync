package net.momirealms.sparrow.sync.snapshot.codec.compressor;

import com.github.luben.zstd.Zstd;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class ZstdCompressor implements Compressor {
    public static final int DEFAULT_LEVEL = 3;

    private final int level;

    public ZstdCompressor(int level) {
        this.level = level;
    }

    @Override
    public byte @NotNull [] compress(byte @NotNull [] data) throws IOException {
        try {
            return Zstd.compress(data, this.level);
        } catch (RuntimeException exception) {
            throw new IOException("zstd compression failed", exception);
        }
    }

    @Override
    public byte @NotNull [] decompress(byte @NotNull [] data, int offset, int length, int sizeLimit) throws IOException {
        // 按帧头声明的大小分配结果数组, 解压时以数组容量为上限
        try {
            long contentSize = Zstd.getFrameContentSize(data, offset, length);
            if (contentSize < 0) {
                throw new IOException("zstd frame header unreadable or carries no content size (code " + contentSize + ")");
            }
            if (contentSize > sizeLimit) {
                throw new IOException("decompressed size " + contentSize + " exceeds limit " + sizeLimit);
            }
            byte[] out = new byte[(int) contentSize];
            // 解压错误抛出 ZstdException, 成功时检查长度与声明一致
            Zstd.decompressByteArray(out, 0, out.length, data, offset, length);
            return out;
        } catch (RuntimeException exception) {
            // 将解压和越界错误转换为 IOException
            throw new IOException("zstd decompression failed", exception);
        }
    }
}
