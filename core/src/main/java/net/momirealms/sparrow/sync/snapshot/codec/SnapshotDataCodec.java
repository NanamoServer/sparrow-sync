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
    public static final int DEFAULT_COMPRESS_THRESHOLD = 256; // 单块原始 NBT 低于此字节数时明文保存

    private static final byte MAGIC_0 = 'S';    // 数据帧标识的首字节
    private static final byte MAGIC_1 = 'D';    // 数据帧标识的次字节
    private static final int HEADER_LENGTH = 11; // SD, u8 版本, u32 索引长度, u32 索引 CRC

    private CompressorRegistry compressor;
    private final int compressThreshold;   // 每块选择明文保存的字节阈值

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
     * 写出包含索引和全部类型块的数据帧, 用于数据库保存.
     * 已有原始块逐字节保留, 新增或替换的内容按当前压缩配置编码.
     *
     * @param data 本次要保存的完整类型数据, 可同时包含原始块和新增 Tag
     * @return 依次包含 11 字节数据帧头, 索引和数据块的新数组
     * @throws IOException 当 NBT 编码失败或原始块超出来源数组范围时
     */
    @NotNull
    public byte[] encode(@NotNull SnapshotData data) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        this.write(data, output);
        return output.toByteArray();
    }

    /**
     * 向目标缓冲区追加一个完整数据帧, 完整快照把它写在 Meta 段之后.
     *
     * @param data 本次要保存的类型数据
     * @param output 接收数据帧的缓冲区, 已有内容保留
     * @throws IOException 当块编码失败或原始块超出来源数组范围时
     */
    void write(@NotNull SnapshotData data, @NotNull ByteArrayOutputStream output) throws IOException {
        // 从 Lazy 数据的来源数组复制整个数据帧, 起点和长度由读取时保存的区间给出.
        if (data instanceof LazySnapshotData lazy) {
            output.write(lazy.frameBytes(), lazy.frameOffset(), lazy.frameLength());
            return;
        }
        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        LinkedHashMap<String, BlockIndex> entries = new LinkedHashMap<>();
        // 按 keys 的顺序写出数据块, 自动记录每块的位置和长度并生成新索引
        // 索引中的位置从第一个数据块起计算; 每块占用 9 字节块头加实际数据长度, 索引的 length 只记录后者
        for (DataKey key : data.keys()) {
            String name = key.asString();
            RawBlock raw = data.raw(key);
            if (raw != null) {
                BlockIndex entry = raw.index();
                long length = BlockCodec.BLOCK_HEADER_LENGTH + (long) entry.length();
                if (raw.offset() < 0 || raw.offset() + length > raw.bytes().length) {
                    throw new FormatException(InvalidReason.CORRUPTED, "raw block out of bounds for " + name);
                }
                // 前面的块修改后长度可能变化, 因此重算此块的位置; 复制的内容没变, 其余索引信息沿用原值.
                entries.put(name, new BlockIndex(blocks.size(), entry.length(), entry.rawLength()));
                blocks.write(raw.bytes(), (int) raw.offset(), (int) length);
                continue;
            }
            byte[] block = BlockCodec.encode(name, data.get(key), this.compressor, this.compressThreshold);
            ByteBuffer header = ByteBuffer.wrap(block);
            int rawLength = header.getInt(1);
            entries.put(name, new BlockIndex(blocks.size(), block.length - BlockCodec.BLOCK_HEADER_LENGTH, rawLength));
            blocks.write(block);
        }
        byte[] index = NBT.toBytes(BlockIndexCodec.write(entries), false);
        CRC32 crc = new CRC32();
        crc.update(index);
        // 数据帧的 CRC 覆盖索引; 各块的 CRC 随块头一起保存, 在读取该类型时检查.
        byte[] header = ByteBuffer.allocate(HEADER_LENGTH)
                .put(MAGIC_0).put(MAGIC_1).put((byte) SnapshotCodec.CURRENT_VERSION)
                .putInt(index.length).putInt((int) crc.getValue()).array();
        output.write(header);
        output.write(index);
        blocks.writeTo(output);
    }

    /**
     * 读取独立数据帧的头部和索引, 各类型保留原始字节供首次取值时读取.
     *
     * @param bytes 数据帧, <strong>成功返回后调用方不得修改数组内容</strong>
     * @return 引用输入数组的惰性类型数据
     * @throws IOException 当数据帧标识, 版本, 索引长度或索引内容无效时
     */
    @NotNull
    public SnapshotData decode(byte @NotNull [] bytes) throws IOException {
        return this.decode(bytes, 0, bytes.length);
    }

    /**
     * 从完整快照的指定区间读取数据帧, 保留原数组供后续取块和复制.
     *
     * @param bytes 来源数组, <strong>成功返回后调用方不得修改数组内容</strong>
     * @param offset 数据帧的起点, <strong>须位于来源数组内或末尾</strong>
     * @param length 数据帧占用的字节数, <strong>须处于来源数组范围内</strong>
     * @return 引用指定区间的惰性类型数据
     * @throws IOException 当数据帧头或索引无效时
     */
    @NotNull
    SnapshotData decode(byte @NotNull [] bytes, int offset, int length) throws IOException {
        if (length < HEADER_LENGTH) {
            throw new FormatException(InvalidReason.CORRUPTED, "data frame too short: " + length + " bytes");
        }
        if (bytes[offset] != MAGIC_0 || bytes[offset + 1] != MAGIC_1) {
            throw new FormatException(InvalidReason.BAD_MAGIC, "expected SD data frame");
        }
        int version = bytes[offset + 2] & 0xFF;
        if (version < SnapshotCodec.MINIMUM_SUPPORTED_VERSION || version > SnapshotCodec.CURRENT_VERSION) {
            throw new FormatException(InvalidReason.UNSUPPORTED_FORMAT, "data frame format " + version + ", supported range "
                    + SnapshotCodec.MINIMUM_SUPPORTED_VERSION + ".." + SnapshotCodec.CURRENT_VERSION);
        }
        ByteBuffer header = ByteBuffer.wrap(bytes);
        long indexLength = Integer.toUnsignedLong(header.getInt(offset + 3));
        if (indexLength > length - HEADER_LENGTH) {
            throw new FormatException(InvalidReason.CORRUPTED, "invalid index length");
        }
        int indexOffset = offset + HEADER_LENGTH;
        int blockBase = indexOffset + (int) indexLength;
        // 索引先通过 CRC 校验, 才能用其中的偏移定位各类型的数据块.
        CRC32 crc = new CRC32();
        crc.update(bytes, indexOffset, (int) indexLength);
        if ((int) crc.getValue() != header.getInt(offset + 7)) {
            throw new FormatException(InvalidReason.CORRUPTED, "index checksum mismatch");
        }
        CompoundTag index = readIndex(bytes, indexOffset, (int) indexLength);
        LinkedHashMap<String, BlockIndex> entries = BlockIndexCodec.read(index);
        // NBT 库读取 compound 使用 HashMap; o 保存了物理次序, 据此恢复 keys 的稳定顺序.
        var ordered = new ArrayList<>(entries.entrySet());
        ordered.sort(Comparator.comparingInt(entry -> entry.getValue().offset()));
        entries.clear();
        long nextOffset = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Map.Entry<String, BlockIndex> entry = ordered.get(i);
            if (entry.getValue().offset() != nextOffset) {
                throw new FormatException(InvalidReason.CORRUPTED, "non-contiguous index offset for " + entry.getKey());
            }
            entries.put(entry.getKey(), entry.getValue());
            nextOffset += BlockCodec.BLOCK_HEADER_LENGTH + (long) entry.getValue().length();
        }
        return new LazySnapshotData(bytes, offset, length, blockBase, entries);
    }

    // 在指定段的边界内读取一个 compound.
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
