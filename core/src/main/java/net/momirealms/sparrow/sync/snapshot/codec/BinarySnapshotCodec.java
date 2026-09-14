package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * 本地文件和跨服缓存使用的二进制快照格式.
 * 7 字节快照头后依次写入元数据和数据帧, 整数使用大端序.
 * 类型内容在首次读取时校验和解压.
 */
public final class BinarySnapshotCodec implements SnapshotCodec<byte[]> {
    private static final int HEADER_LENGTH = 7; // u8 版本, u16 Meta 长度, u32 Meta CRC

    private final SnapshotDataCodec dataCodec; // 与数据库共用数据帧格式和压缩配置

    public BinarySnapshotCodec(@NotNull SnapshotDataCodec dataCodec) {
        this.dataCodec = dataCodec;
    }

    public BinarySnapshotCodec(@NotNull CompressorRegistry compressor) {
        this(new SnapshotDataCodec(compressor));
    }

    public BinarySnapshotCodec(@NotNull CompressorRegistry compressor, int compressThreshold) {
        this(new SnapshotDataCodec(compressor, compressThreshold));
    }

    @NotNull
    @Override
    public byte[] encode(@NotNull Snapshot snapshot) throws IOException {
        byte[] meta = SnapshotMetaCodec.encode(snapshot.meta());
        if (meta.length > 0xFFFF) {
            throw new IOException("snapshot meta exceeds unsigned short length: " + meta.length);
        }
        CRC32 crc = new CRC32();
        crc.update(meta);
        byte[] header = ByteBuffer.allocate(HEADER_LENGTH)
                .put((byte) CURRENT_VERSION)
                .putShort((short) meta.length).putInt((int) crc.getValue()).array();
        ByteArrayOutputStream output = new ByteArrayOutputStream(HEADER_LENGTH + meta.length);
        output.write(header);
        output.write(meta);
        // 将数据帧追加到元数据之后, 已有原始块直接复制
        this.dataCodec.write(snapshot.content(), output);
        return output.toByteArray();
    }

    /**
     * 从数据索引和块头提取类型与大小, 供异常快照头文件使用, 元数据损坏时仍可尝试读取.
     * @return 按块顺序排列的只读摘要, 索引不可读时为 null, 单个块头损坏时大小为 -1
     */
    @Nullable
    public Map<DataKey, Integer> summarize(byte @NotNull [] encoded) {
        if (encoded.length < HEADER_LENGTH) return null;
        int version = encoded[0] & 0xFF;
        if (version < MINIMUM_SUPPORTED_VERSION || version > CURRENT_VERSION) return null;
        int metaLength = Short.toUnsignedInt(ByteBuffer.wrap(encoded).getShort(1));
        int dataOffset = HEADER_LENGTH + metaLength;
        if (metaLength == 0 || dataOffset > encoded.length) return null;
        try {
            // 复用来源数组, 读取块头前先校验数据帧版本和索引 CRC
            SnapshotData data = this.dataCodec.decode(encoded, dataOffset, encoded.length - dataOffset);
            Map<DataKey, Integer> summary = new LinkedHashMap<>();
            for (DataKey key : data.keys()) {
                try {
                    summary.put(key, data.rawLength(key));
                } catch (UncheckedIOException failure) {
                    // 块头损坏时仍保留类型名, 继续读取其他块的大小
                    summary.put(key, -1);
                }
            }
            return Collections.unmodifiableMap(summary);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * 读取快照元数据和索引, 类型内容延迟到首次取值时校验和解码.
     * @param encoded <strong>返回后不得修改来源数组</strong>
     * @return 引用原数组的快照, 或带原因的无效结果
     */
    @Override
    @NotNull
    public DecodedSnapshot decode(byte @NotNull [] encoded) {
        try {
            if (encoded.length < HEADER_LENGTH) {
                throw new FormatException(InvalidReason.CORRUPTED, "snapshot too short: " + encoded.length + " bytes");
            }
            int version = encoded[0] & 0xFF;
            if (version < MINIMUM_SUPPORTED_VERSION || version > CURRENT_VERSION) {
                throw new FormatException(InvalidReason.UNSUPPORTED_FORMAT, "snapshot format " + version + ", supported range " + MINIMUM_SUPPORTED_VERSION + ".." + CURRENT_VERSION);
            }
            ByteBuffer header = ByteBuffer.wrap(encoded);
            int metaLength = Short.toUnsignedInt(header.getShort(1));
            int dataOffset = HEADER_LENGTH + metaLength;
            if (metaLength == 0 || dataOffset > encoded.length) {
                throw new FormatException(InvalidReason.CORRUPTED, "invalid meta length");
            }
            CRC32 crc = new CRC32();
            crc.update(encoded, HEADER_LENGTH, metaLength);
            if ((int) crc.getValue() != header.getInt(3)) {
                throw new FormatException(InvalidReason.CORRUPTED, "meta checksum mismatch");
            }
            SnapshotMeta meta = SnapshotMetaCodec.decode(encoded, HEADER_LENGTH, metaLength);
            // 数据帧和后续原始块均引用同一个来源数组
            SnapshotData data = this.dataCodec.decode(encoded, dataOffset, encoded.length - dataOffset);
            return new DecodedSnapshot.Valid(new Snapshot(meta, data));
        } catch (FormatException exception) {
            return new DecodedSnapshot.Invalid(exception.reason(), String.valueOf(exception.getMessage()));
        } catch (Exception exception) {
            return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, String.valueOf(exception.getMessage()));
        }
    }
}
