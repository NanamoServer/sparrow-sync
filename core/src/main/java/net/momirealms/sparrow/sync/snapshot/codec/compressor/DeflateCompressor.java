package net.momirealms.sparrow.sync.snapshot.codec.compressor;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class DeflateCompressor implements Compressor {

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
                // 边解压边检查长度, 超过上限立即停止
                if (total > sizeLimit) {
                    throw new IOException("decompressed size exceeds limit " + sizeLimit);
                }
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        }
    }
}
