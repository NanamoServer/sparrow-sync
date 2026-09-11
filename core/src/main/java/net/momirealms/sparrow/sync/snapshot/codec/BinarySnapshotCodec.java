package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.LazySnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.RawBlock;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockCodec;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockIndex;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

/**
 * 快照的分块二进制容器, 用于本地文件, 跨服传输及数据库的数据字段.
 * 14 字节固定头之后依次是未压缩的元数据, 索引和独立数据块, 所有整数使用大端序.
 * 完整快照在读取元数据和索引后即可返回, 每个类型在首次取值时单独校验和解压.
 */
public final class BinarySnapshotCodec implements SnapshotCodec<byte[]> {
    public static final int DEFAULT_COMPRESS_THRESHOLD = 256; // 单块原始 NBT 低于此字节数时明文保存

    private static final byte MAGIC_0 = 'S';      // 快照魔数的首字节
    private static final byte MAGIC_1 = 'S';      // 快照魔数的次字节
    private static final int HEADER_LENGTH = 14; // 固定头长度, 后续段从此处开始
    private static final int FLAG_HAS_META = 0x01;      // 元数据位于帧内
    private static final int FLAG_RESERVED_MASK = 0xFE; // 保留位, 任一置位表示当前实现不支持

    private CompressorRegistry compressor; // onLoad 或显式构造器选定的写入算法
    private final int compressThreshold;   // 每块选择明文存储的字节阈值

    public BinarySnapshotCodec(@NotNull SparrowSync plugin) {
        this.compressThreshold = DEFAULT_COMPRESS_THRESHOLD;
    }

    public BinarySnapshotCodec(@NotNull CompressorRegistry compressor) {
        this(compressor, DEFAULT_COMPRESS_THRESHOLD);
    }

    public BinarySnapshotCodec(@NotNull CompressorRegistry compressor, int compressThreshold) {
        this.compressor = compressor;
        this.compressThreshold = compressThreshold;
    }

    public void onLoad() throws IOException {
        CompressorRegistry compressor = PluginConfig.synchronization$compression();
        byte[] probe = compressor.compress(new byte[64]);
        compressor.decompress(probe, 0, probe.length, 256);
        this.compressor = compressor;
    }

    @NotNull
    @Override
    public byte[] encode(@NotNull Snapshot snapshot) throws IOException {
        byte[] meta = NBT.toBytes(SnapshotNBT.toMetaTree(snapshot.meta()), false);
        if (meta.length > 0xFFFF) {
            throw new IOException("snapshot meta exceeds unsigned short length: " + meta.length);
        }
        return this.encodeFrame(meta, snapshot.content());
    }

    /**
     * 将 Map 中的每个 Tag 编码为独立的数据块, 自动生成索引, 返回供数据库保存的完整二进制帧, 帧内不包含快照元数据;
     *
     * @param data 按 Map 迭代顺序写出的类型标识及对应 NBT 值
     * @return 依次包含帧头, 索引和所有数据块的字节数组, 帧头的 HAS_META 标志为 0
     * @throws IOException 当 NBT 序列化或数据块压缩失败时
     */
    @NotNull
    public byte[] frameData(@NotNull Map<DataKey, Tag> data) throws IOException {
        return this.frameData(new EagerSnapshotData(data));
    }

    /**
     * 将快照中各类型的数据写成供数据库保存的二进制帧, 元数据由数据库单独保存.
     * 已有原始字节的类型直接复制数据块, 保留原来的压缩方式, CRC 和类型版本;
     * 新增或替换的 Tag 按当前压缩配置编码.
     *
     * @param data 要保存的各类型数据, 可以同时包含原始数据块和修改后的 Tag
     * @return 依次包含帧头, 索引和所有数据块的完整字节数组, 帧头的 HAS_META 标志为 0
     * @throws IOException 当 NBT 序列化或压缩失败, 或逐块复制时发现数据块超出原数组范围
     */
    @NotNull
    public byte[] frameData(@NotNull SnapshotData data) throws IOException {
        return this.encodeFrame(new byte[0], data);
    }

    /**
     * 构建完整的二进制帧, 传入元数据时生成可用于文件保存和跨服传输的完整快照; 传入空数组时生成数据库数据字段的内容.
     *
     * @param meta 已序列化的元数据 NBT, 长度须不超过 65535 字节; 空数组表示帧内不保存元数据
     * @param data 本次要写出的全部类型数据, 可包含原始数据块及新增或替换的 Tag
     * @return 按帧头, 可选元数据, 索引, 所有数据块的顺序排列的完整字节数组
     * @throws IOException 当 NBT 序列化或压缩失败, 或逐块复制时发现数据块超出原数组范围
     */
    @NotNull
    private byte[] encodeFrame(byte @NotNull [] meta, @NotNull SnapshotData data) throws IOException {
        //  LazySnapshotData 已有完整帧, 直接复制其中的索引和所有数据块;
        if (data instanceof LazySnapshotData lazy) return copyFrame(meta, lazy.encodedFrame());
        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        LinkedHashMap<String, BlockIndex.Entry> entries = new LinkedHashMap<>();
        // 按 keys 的顺序写出数据块, 自动记录每块的位置, 长度, 压缩方式和类型版本并生成新索引
        // 索引中的位置从第一个数据块起计算; 每块占用 9 字节块头加实际数据长度, 索引的 length 只记录后者
        for (DataKey key : data.keys()) {
            String name = key.asString();
            RawBlock raw = data.raw(key);
            if (raw != null) {
                BlockIndex.Entry entry = raw.entry();
                long length = BlockCodec.BLOCK_HEADER_LENGTH + (long) entry.length();
                if (raw.offset() < 0 || raw.offset() + length > raw.bytes().length) {
                    throw new FormatException(InvalidReason.CORRUPTED, "raw block out of bounds for " + name);
                }
                // 前面的块修改后长度可能变化, 因此重算此块的位置; 复制的内容没变, 其余索引信息沿用原值.
                entries.put(name, new BlockIndex.Entry(blocks.size(), entry.length(), entry.rawLength(), entry.compressorId(), entry.version()));
                blocks.write(raw.bytes(), (int) raw.offset(), (int) length);
                continue;
            }
            byte[] block = BlockCodec.encode(name, data.get(key), this.compressor, this.compressThreshold);
            ByteBuffer header = ByteBuffer.wrap(block);
            byte compressorId = header.get();
            int rawLength = header.getInt();
            entries.put(name, new BlockIndex.Entry(blocks.size(), block.length - BlockCodec.BLOCK_HEADER_LENGTH, rawLength, compressorId, 1));
            blocks.write(block);
        }
        byte[] index = NBT.toBytes(BlockIndex.write(entries), false);
        CRC32 crc = new CRC32();
        crc.update(meta);
        crc.update(index);
        // 帧头的 CRC 校验元数据和索引; 每个数据块另有 CRC, 校验该块实际保存的数据字节.
        ByteArrayOutputStream output = new ByteArrayOutputStream(HEADER_LENGTH + meta.length + index.length + blocks.size());
        byte[] header = ByteBuffer.allocate(HEADER_LENGTH)
                .put(MAGIC_0).put(MAGIC_1).put((byte) CURRENT_VERSION).put((byte) (meta.length == 0 ? 0 : FLAG_HAS_META))
                .putShort((short) meta.length).putInt(index.length).putInt((int) crc.getValue()).array();
        output.write(header);
        output.write(meta);
        output.write(index);
        blocks.writeTo(output);
        return output.toByteArray();
    }

    /**
     * 替换已有帧的元数据, 返回重新组装的完整帧.
     * 重新写入帧头并计算元数据与索引的 CRC, 原索引和所有数据块连续复制到新数组中.
     *
     * @param meta 新的元数据 NBT 字节, 空数组表示移除帧内元数据
     * @param original 已读取并校验过帧头和索引的原帧, 包括帧头, 可选元数据, 索引和数据块
     * @return 包含新帧头, 新元数据以及原索引和原数据块的新字节数组
     */
    @NotNull
    private static byte[] copyFrame(byte @NotNull [] meta, byte @NotNull [] original) {
        ByteBuffer source = ByteBuffer.wrap(original);
        int indexOffset = HEADER_LENGTH + Short.toUnsignedInt(source.getShort(4));
        int indexLength = source.getInt(6);
        CRC32 crc = new CRC32();
        crc.update(meta);
        crc.update(original, indexOffset, indexLength);
        // 元数据变化后需更新帧头中的 CRC; 各块的内容没变, 块内原有的 CRC 随数据一起复制.
        return ByteBuffer.allocate(HEADER_LENGTH + meta.length + original.length - indexOffset)
                .put(MAGIC_0).put(MAGIC_1).put((byte) CURRENT_VERSION).put((byte) (meta.length == 0 ? 0 : FLAG_HAS_META))
                .putShort((short) meta.length).putInt(indexLength).putInt((int) crc.getValue())
                .put(meta).put(original, indexOffset, original.length - indexOffset).array();
    }

    // 读取并校验帧头, 元数据和索引后返回快照; 各类型的数据块在首次取值时才校验, 解压并解析 NBT.
    @Override
    @NotNull
    public DecodedSnapshot decode(byte @NotNull [] encoded) {
        try {
            Frame frame = readFrame(encoded);
            if (frame.meta() == null) {
                throw new FormatException(InvalidReason.CORRUPTED, "missing snapshot meta");
            }
            return new DecodedSnapshot.Valid(new Snapshot(SnapshotNBT.fromMetaTree(frame.meta()), new LazySnapshotData(encoded, frame.blockBase(), frame.index())));
        } catch (FormatException exception) {
            return new DecodedSnapshot.Invalid(exception.reason(), String.valueOf(exception.getMessage()));
        } catch (Exception exception) {
            return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, String.valueOf(exception.getMessage()));
        }
    }

    /**
     * 读取数据库数据帧的头部与索引, 各类型的块留到首次取值时校验和解压.
     *
     * @param bytes HAS_META=0 的数据帧, <strong>成功返回后调用方不得修改其字节</strong>
     * @return 惰性数据体, 通过 get 读取单个类型或通过 all 读取全部类型
     * @throws IOException 当容器头或索引不可读, 或输入含有帧内元数据时
     */
    @NotNull
    public SnapshotData deframeData(byte @NotNull [] bytes) throws IOException {
        Frame frame = readFrame(bytes);
        if (frame.meta() != null) {
            throw new FormatException(InvalidReason.CORRUPTED, "expected a data-only frame");
        }
        return new LazySnapshotData(bytes, frame.blockBase(), frame.index());
    }

    /**
     * 校验容器头与索引, 块区中的字节在取块时校验.
     *
     * @param bytes 完整帧字节
     * @return 元数据, 按物理块顺序排列的索引及块区起点
     * @throws IOException 当版本不受支持, 段长越界或元数据与索引损坏时
     */
    @NotNull
    private static Frame readFrame(byte @NotNull [] bytes) throws IOException {
        if (bytes.length < HEADER_LENGTH) {
            throw new FormatException(InvalidReason.CORRUPTED, "framed payload too short: " + bytes.length + " bytes");
        }
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) {
            throw new FormatException(InvalidReason.BAD_MAGIC, "unexpected magic bytes");
        }
        int version = bytes[2] & 0xFF;
        if (version < MINIMUM_SUPPORTED_VERSION || version > CURRENT_VERSION) {
            throw new FormatException(InvalidReason.UNSUPPORTED_FORMAT, "snapshot format " + version + ", supported range " + MINIMUM_SUPPORTED_VERSION + ".." + CURRENT_VERSION);
        }
        int flags = bytes[3] & 0xFF;
        if ((flags & FLAG_RESERVED_MASK) != 0) {
            throw new FormatException(InvalidReason.UNSUPPORTED_FORMAT, "unsupported snapshot flags " + flags);
        }
        ByteBuffer header = ByteBuffer.wrap(bytes);
        int metaLength = Short.toUnsignedInt(header.getShort(4));
        long indexLength = Integer.toUnsignedLong(header.getInt(6));
        long blockBase = HEADER_LENGTH + metaLength + indexLength;
        if (blockBase > bytes.length || ((flags & FLAG_HAS_META) == 0 && metaLength != 0)) {
            throw new FormatException(InvalidReason.CORRUPTED, "invalid meta or index length");
        }
        // 在解析 NBT 前校验寻址表, 受损的索引不能用于判断各块位置.
        CRC32 crc = new CRC32();
        crc.update(bytes, HEADER_LENGTH, metaLength + (int) indexLength);
        if ((int) crc.getValue() != header.getInt(10)) {
            throw new FormatException(InvalidReason.CORRUPTED, "meta/index checksum mismatch");
        }
        CompoundTag meta = (flags & FLAG_HAS_META) == 0 ? null : readCompound(bytes, HEADER_LENGTH, metaLength, "meta");
        CompoundTag index = readCompound(bytes, HEADER_LENGTH + metaLength, (int) indexLength, "index");
        LinkedHashMap<String, BlockIndex.Entry> entries = BlockIndex.read(index);
        // NBT 库读取 compound 使用 HashMap; o 保存了物理次序, 据此恢复 keys 的稳定顺序.
        var ordered = new ArrayList<>(entries.entrySet());
        ordered.sort(Comparator.comparingInt(entry -> entry.getValue().offset()));
        entries.clear();
        long nextOffset = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Map.Entry<String, BlockIndex.Entry> entry = ordered.get(i);
            if (entry.getValue().offset() != nextOffset) {
                throw new FormatException(InvalidReason.CORRUPTED, "non-contiguous index offset for " + entry.getKey());
            }
            entries.put(entry.getKey(), entry.getValue());
            nextOffset += BlockCodec.BLOCK_HEADER_LENGTH + (long) entry.getValue().length();
        }
        return new Frame(meta, entries, (int) blockBase);
    }

    /**
     * 在指定段的边界内读取一个 compound, 并要求 NBT 恰好占满该段.
     *
     * @param bytes 帧字节
     * @param offset 段起点
     * @param length 段字节数
     * @param segment 用于错误定位的段名
     * @return 解析出的树
     * @throws IOException 当 NBT 损坏, 根类型不符或段内有尾随字节时
     */
    @NotNull
    private static CompoundTag readCompound(byte @NotNull [] bytes, int offset, int length, @NotNull String segment) throws IOException {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, offset, length));
            Tag root = NBT.readUnnamedTag(input, false);
            if (!(root instanceof CompoundTag compound) || input.available() != 0) {
                throw new IOException("expected exactly one compound");
            }
            return compound;
        } catch (IOException | RuntimeException exception) {
            FormatException failure = new FormatException(InvalidReason.CORRUPTED, "invalid " + segment + " segment: " + exception.getMessage());
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 容器解析结果, 块区仍保留在调用方提供的原始帧中.
     *
     * @param meta 帧内元数据, 数据帧为 null
     * @param index 各类型的块位置与编码信息
     * @param blockBase 第一块在帧中的绝对偏移
     */
    private record Frame(@Nullable CompoundTag meta, @NotNull LinkedHashMap<String, BlockIndex.Entry> index, int blockBase) {
    }
}
