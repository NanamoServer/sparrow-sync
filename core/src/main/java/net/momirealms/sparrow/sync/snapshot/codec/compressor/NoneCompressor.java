package net.momirealms.sparrow.sync.snapshot.codec.compressor;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class NoneCompressor implements Compressor {

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
