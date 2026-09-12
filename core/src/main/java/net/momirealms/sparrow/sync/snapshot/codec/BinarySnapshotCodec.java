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
 * 完整快照的二进制读写, 用于本地文件和跨服缓存.
 * 7 字节快照头之后依次保存必需的 Meta 段和一个完整数据帧, 所有整数使用大端序.
 * Meta 和数据帧分别交给各自的编码器, 类型内容在首次取值时校验和解压.
 */
public final class BinarySnapshotCodec implements SnapshotCodec<byte[]> {
    private static final int HEADER_LENGTH = 7; // u8 版本, u16 Meta 长度, u32 Meta CRC

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
                .put((byte) CURRENT_VERSION)
                .putShort((short) meta.length).putInt((int) crc.getValue()).array();
        ByteArrayOutputStream output = new ByteArrayOutputStream(HEADER_LENGTH + meta.length);
        output.write(header);
        output.write(meta);
        // 数据帧直接追加到完整快照的缓冲区, 其中的原始块可从来源数组复制.
        this.dataCodec.write(snapshot.content(), output);
        return output.toByteArray();
    }

    /**
     * 从完整快照中取得类型清单与未压缩字节数, 供本地异常头保存诊断摘要.
     * 清单依赖数据帧索引及其 CRC; Meta 内容损坏仍可提取, payload 保持原样.
     *
     * @param encoded 待归档的完整快照字节, 可以包含损坏的数据
     * @return 按块顺序排列的只读摘要; 无法读取索引时为 null, 单个块头损坏时该类型体量为 -1
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
            // 区间解码复用输入数组, 并在访问各块之前校验数据帧版本和索引 CRC.
            SnapshotData data = this.dataCodec.decode(encoded, dataOffset, encoded.length - dataOffset);
            Map<DataKey, Integer> summary = new LinkedHashMap<>();
            for (DataKey key : data.keys()) {
                try {
                    summary.put(key, data.rawLength(key));
                } catch (UncheckedIOException failure) {
                    // 单块头损坏仍保留类型名, 其他块的体量继续读取.
                    summary.put(key, -1);
                }
            }
            return Collections.unmodifiableMap(summary);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
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
