package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * 完整快照的二进制读写, 用于本地文件和跨服缓存.
 * 9 字节快照头之后依次保存必需的 Meta 段和一个完整数据帧, 所有整数使用大端序.
 * Meta 和数据帧分别交给各自的编码器, 类型内容在首次取值时校验和解压.
 */
public final class BinarySnapshotCodec implements SnapshotCodec<byte[]> {
    private static final byte MAGIC_0 = 'S';    // 完整快照标识的首字节
    private static final byte MAGIC_1 = 'S';    // 完整快照标识的次字节
    private static final int HEADER_LENGTH = 9; // SS, u8 版本, u16 Meta 长度, u32 Meta CRC

    private final SnapshotDataCodec dataCodec; // 与数据库共用的数据帧编码器及压缩配置

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
                .put(MAGIC_0).put(MAGIC_1).put((byte) CURRENT_VERSION)
                .putShort((short) meta.length).putInt((int) crc.getValue()).array();
        ByteArrayOutputStream output = new ByteArrayOutputStream(HEADER_LENGTH + meta.length);
        output.write(header);
        output.write(meta);
        // 数据帧直接追加到完整快照的缓冲区, 其中的原始块可从来源数组复制.
        this.dataCodec.write(snapshot.content(), output);
        return output.toByteArray();
    }

    /**
     * 读取快照身份和数据索引, 返回引用原数组的惰性快照.
     *
     * @param encoded 完整快照, <strong>成功返回后调用方不得修改数组内容</strong>
     * @return 有效快照或带具体原因的无效结果, 块内错误在首次取值时报告
     */
    @Override
    @NotNull
    public DecodedSnapshot decode(byte @NotNull [] encoded) {
        try {
            if (encoded.length < HEADER_LENGTH) {
                throw new FormatException(InvalidReason.CORRUPTED, "snapshot too short: " + encoded.length + " bytes");
            }
            if (encoded[0] != MAGIC_0 || encoded[1] != MAGIC_1) {
                throw new FormatException(InvalidReason.BAD_MAGIC, "expected SS snapshot");
            }
            int version = encoded[2] & 0xFF;
            if (version < MINIMUM_SUPPORTED_VERSION || version > CURRENT_VERSION) {
                throw new FormatException(InvalidReason.UNSUPPORTED_FORMAT, "snapshot format " + version + ", supported range " + MINIMUM_SUPPORTED_VERSION + ".." + CURRENT_VERSION);
            }
            ByteBuffer header = ByteBuffer.wrap(encoded);
            int metaLength = Short.toUnsignedInt(header.getShort(3));
            int dataOffset = HEADER_LENGTH + metaLength;
            if (metaLength == 0 || dataOffset > encoded.length) {
                throw new FormatException(InvalidReason.CORRUPTED, "invalid meta length");
            }
            CRC32 crc = new CRC32();
            crc.update(encoded, HEADER_LENGTH, metaLength);
            if ((int) crc.getValue() != header.getInt(5)) {
                throw new FormatException(InvalidReason.CORRUPTED, "meta checksum mismatch");
            }
            SnapshotMeta meta = SnapshotMetaCodec.decode(encoded, HEADER_LENGTH, metaLength);
            // 数据编码器接收已经确定的区间, 后续原始块引用仍指向同一个输入数组.
            SnapshotData data = this.dataCodec.decode(encoded, dataOffset, encoded.length - dataOffset);
            return new DecodedSnapshot.Valid(new Snapshot(meta, data));
        } catch (FormatException exception) {
            return new DecodedSnapshot.Invalid(exception.reason(), String.valueOf(exception.getMessage()));
        } catch (Exception exception) {
            return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, String.valueOf(exception.getMessage()));
        }
    }
}
