package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockCodec;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockIndexCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.model.BlockIndex;
import net.momirealms.sparrow.sync.snapshot.model.LazySnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.RawBlock;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

public final class SnapshotDataCodec {
    public static final int DEFAULT_COMPRESS_THRESHOLD = 256; // 小于此字节数的数据块不压缩
    private static final int HEADER_LENGTH = 9; // u8 版本, u32 索引长度, u32 索引 CRC

    private CompressorRegistry compressor;
    private final int compressThreshold;   // 数据块的压缩阈值, 单位字节

    public SnapshotDataCodec() {
        this.compressThreshold = DEFAULT_COMPRESS_THRESHOLD;
    }

    public SnapshotDataCodec(@NotNull CompressorRegistry compressor) {
        this(compressor, DEFAULT_COMPRESS_THRESHOLD);
    }

    public SnapshotDataCodec(@NotNull CompressorRegistry compressor, int compressThreshold) {
        this.compressor = compressor;
        this.compressThreshold = compressThreshold;
    }

    public void onLoad() throws IOException {
        CompressorRegistry compressor = PluginConfig.synchronization$compression();
        byte[] probe = compressor.compress(new byte[64]);
        compressor.decompress(probe, 0, probe.length, 256);
        this.compressor = compressor;
    }

    /**
     * 编码数据库使用的数据帧, 依次写入 9 字节帧头、索引和数据块.
     * 已有原始块直接复制, 新增或替换的 Tag 按当前配置编码.
     * @throws IOException NBT 编码失败或原始块头、区间无效时
     */
    @NotNull
    public byte[] encode(@NotNull SnapshotData data) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        this.write(data, output);
        return output.toByteArray();
    }

    /**
     * 向缓冲区追加完整数据帧, 保留已有内容.
     * @throws IOException 数据块编码失败或原始块头、区间无效时
     */
    void write(@NotNull SnapshotData data, @NotNull ByteArrayOutputStream output) throws IOException {
        // 直接复制 Lazy 数据记录的完整帧区间
        if (data instanceof LazySnapshotData lazy) {
            output.write(lazy.frameBytes(), lazy.frameOffset(), lazy.frameLength());
            return;
        }
        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        LinkedHashMap<String, BlockIndex> entries = new LinkedHashMap<>();
        // 按 keys 顺序写入各块, 索引记录块头相对块区起点的偏移
        for (DataKey key : data.keys()) {
            String name = key.asString();
            RawBlock raw = data.raw(key);
            if (raw != null) {
                int length = BlockCodec.BLOCK_HEADER_LENGTH + BlockCodec.readHeader(raw, name).payloadLength();
                // 按块头长度复制原始字节, 保留压缩算法和 CRC, 此处不校验内容
                entries.put(name, new BlockIndex(blocks.size()));
                blocks.write(raw.bytes(), (int) raw.offset(), length);
                continue;
            }
            byte[] block = BlockCodec.encode(name, data.get(key), this.compressor, this.compressThreshold);
            entries.put(name, new BlockIndex(blocks.size()));
            blocks.write(block);
        }
        byte[] index = NBT.toBytes(BlockIndexCodec.write(entries), false);
        CRC32 crc = new CRC32();
        crc.update(index);
        // 帧头 CRC 只覆盖索引, 各块 CRC 在读取对应类型时检查
        byte[] header = ByteBuffer.allocate(HEADER_LENGTH)
                .put((byte) SnapshotCodec.CURRENT_VERSION)
                .putInt(index.length).putInt((int) crc.getValue()).array();
        output.write(header);
        output.write(index);
        blocks.writeTo(output);
    }

    /**
     * 校验帧头和索引, 各类型首次读取时再解码.
     * @param bytes <strong>返回后不得修改来源数组</strong>
     * @throws IOException 帧头或索引无效时
     */
    @NotNull
    public SnapshotData decode(byte @NotNull [] bytes) throws IOException {
        return this.decode(bytes, 0, bytes.length);
    }

    /**
     * 读取指定区间的数据帧, 后续解码和复制继续引用原数组.
     * @param bytes <strong>返回后不得修改来源数组</strong>
     * @param offset 区间起点, 可等于数组末尾
     * @param length 区间长度, 不得超出数组范围
     * @throws IOException 帧头或索引无效时
     */
    @NotNull
    SnapshotData decode(byte @NotNull [] bytes, int offset, int length) throws IOException {
        if (length < HEADER_LENGTH) {
            throw new FormatException(InvalidReason.CORRUPTED, "data frame too short: " + length + " bytes");
        }
        int version = bytes[offset] & 0xFF;
        if (version < SnapshotCodec.MINIMUM_SUPPORTED_VERSION || version > SnapshotCodec.CURRENT_VERSION) {
            throw new FormatException(InvalidReason.UNSUPPORTED_FORMAT, "data frame format " + version + ", supported range "
                    + SnapshotCodec.MINIMUM_SUPPORTED_VERSION + ".." + SnapshotCodec.CURRENT_VERSION);
        }
        ByteBuffer header = ByteBuffer.wrap(bytes);
        long indexLength = Integer.toUnsignedLong(header.getInt(offset + 1));
        if (indexLength > length - HEADER_LENGTH) {
            throw new FormatException(InvalidReason.CORRUPTED, "invalid index length");
        }
        int indexOffset = offset + HEADER_LENGTH;
        int blockBase = indexOffset + (int) indexLength;
        // 先校验索引 CRC, 再用索引定位数据块
        CRC32 crc = new CRC32();
        crc.update(bytes, indexOffset, (int) indexLength);
        if ((int) crc.getValue() != header.getInt(offset + 5)) {
            throw new FormatException(InvalidReason.CORRUPTED, "index checksum mismatch");
        }
        CompoundTag index = readIndex(bytes, indexOffset, (int) indexLength);
        LinkedHashMap<String, BlockIndex> entries = BlockIndexCodec.read(index);
        // NBT 读取使用 HashMap, 按偏移排序恢复原有块顺序
        var ordered = new ArrayList<>(entries.entrySet());
        ordered.sort(Comparator.comparingInt(entry -> entry.getValue().offset()));
        entries.clear();
        int previousOffset = -1;
        for (int i = 0; i < ordered.size(); i++) {
            Map.Entry<String, BlockIndex> entry = ordered.get(i);
            int blockOffset = entry.getValue().offset();
            if (blockOffset == previousOffset) {
                throw new FormatException(InvalidReason.CORRUPTED, "duplicate index offset for " + entry.getKey());
            }
            if (i == 0 && blockOffset != 0) {
                throw new FormatException(InvalidReason.CORRUPTED, "first index offset must be zero for " + entry.getKey());
            }
            entries.put(entry.getKey(), entry.getValue());
            previousOffset = blockOffset;
        }
        return new LazySnapshotData(bytes, offset, length, blockBase, entries);
    }

    // 在指定区间内读取一个 CompoundTag
    @NotNull
    private static CompoundTag readIndex(byte @NotNull [] bytes, int offset, int length) throws IOException {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, offset, length));
            Tag root = NBT.readUnnamedTag(input, false);
            if (!(root instanceof CompoundTag compound) || input.available() != 0) {
                throw new IOException("expected exactly one compound");
            }
            return compound;
        } catch (IOException | RuntimeException exception) {
            FormatException failure = new FormatException(InvalidReason.CORRUPTED, "invalid index segment: " + exception.getMessage());
            failure.initCause(exception);
            throw failure;
        }
    }

}
