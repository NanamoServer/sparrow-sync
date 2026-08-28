package net.momirealms.sparrow.sync.codec.compressor;

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
        // 帧头声明的原始大小只决定分配, 实际解压受目标容量约束, 伪造的声明骗不出更大的缓冲
        try {
            long contentSize = Zstd.getFrameContentSize(data, offset, length);
            if (contentSize < 0) {
                throw new IOException("zstd frame header unreadable or carries no content size (code " + contentSize + ")");
            }
            if (contentSize > sizeLimit) {
                throw new IOException("decompressed size " + contentSize + " exceeds limit " + sizeLimit);
            }
            byte[] out = new byte[(int) contentSize];
            // 出错以 unchecked ZstdException 浮出而不是返回错误码, 正常返回时长度必与声明一致
            Zstd.decompressByteArray(out, 0, out.length, data, offset, length);
            return out;
        } catch (RuntimeException exception) {
            // 损坏数据与越界访问都转为接口承诺的 IOException
            throw new IOException("zstd decompression failed", exception);
        }
    }
}
