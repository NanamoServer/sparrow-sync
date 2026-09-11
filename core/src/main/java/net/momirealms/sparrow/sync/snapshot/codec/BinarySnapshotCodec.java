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
        return this.frame(meta, snapshot.content());
    }

    /**
     * 将数据树封为 HAS_META=0 的容器, 供元数据和内容数据分离的数据库格式调用.
     *
     * @param tag 以完整 DataKey 文本为键的 CompoundTag, 每个值独立成块
     * @return 元数据长度为零的完整数据帧
     * @throws IOException 当根不是 compound, 序列化或压缩失败时
     */
    @NotNull
    public byte[] frame(@NotNull Tag tag) throws IOException {
        if (!(tag instanceof CompoundTag compound)) {
            throw new IOException("data root must be a compound");
        }
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        for (Map.Entry<String, Tag> entry : compound.entrySet()) {
            values.put(DataKey.parse(entry.getKey()), entry.getValue());
        }
        return this.frame(new byte[0], new EagerSnapshotData(values));
    }

    /**
     * 写出元数据, 索引与块区, 两种载体共用相同的块布局.
     *
     * @param meta 已序列化的元数据, 空数组表示元数据由外层载体提供
     * @param data 按 keys 的迭代顺序编码的数据体
     * @return 各段紧密排列的容器
     * @throws IOException 当 NBT 序列化或块压缩失败时
     */
    @NotNull
    private byte[] frame(byte @NotNull [] meta, @NotNull SnapshotData data) throws IOException {
        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        LinkedHashMap<String, BlockIndex.Entry> entries = new LinkedHashMap<>();
        // 偏移以块区起点为零, l 仅计 payload, 块头的 9 字节单独参与寻址.
        for (DataKey key : data.keys()) {
            String name = key.asString();
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
        // meta 和 index 共用连续校验区, 块内 CRC 各自覆盖压缩后的 payload.
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

    // 解码完整快照的元数据和索引, 数据块留到首次取值时还原.
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
     * 还原数据库数据帧, 遍历全部块后返回以类型名为键的树.
     *
     * @param bytes HAS_META=0 的数据帧
     * @return 各类型已完整还原的 CompoundTag
     * @throws IOException 当容器或任一块不可读时, 原因保留在 FormatException 中
     */
    @NotNull
    public Tag deframe(byte @NotNull [] bytes) throws IOException {
        Frame frame = readFrame(bytes);
        if (frame.meta() != null) {
            throw new FormatException(InvalidReason.CORRUPTED, "expected a data-only frame");
        }
        CompoundTag values = NBT.createCompound(new LinkedHashMap<>());
        for (Map.Entry<String, BlockIndex.Entry> entry : frame.index().entrySet()) {
            values.put(entry.getKey(), BlockCodec.decode(bytes, frame.blockBase(), entry.getKey(), entry.getValue()));
        }
        return values;
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
