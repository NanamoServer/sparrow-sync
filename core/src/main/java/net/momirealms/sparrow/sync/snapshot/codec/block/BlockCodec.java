package net.momirealms.sparrow.sync.snapshot.codec.block;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.Compressor;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.model.RawBlock;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

public final class BlockCodec {
    public static final int BLOCK_HEADER_LENGTH = 13; // 压缩算法1B + 两个长度和载荷 CRC 各 4B

    private BlockCodec() {
    }

    /**
     * 将一个类型编码为带块头的独立 NBT 文档, 小于阈值时明文存储.
     *
     * @param key 完整 DataKey 文本, 同时写入块内 compound
     * @param value 该类型的原始 Tag
     * @param compressor 达到阈值时使用的压缩算法
     * @param threshold 原始单键 compound 的压缩阈值, 单位为字节
     * @return 13 字节块头与 payload 连续组成的字节数组
     * @throws IOException 当序列化失败或压缩器及 DEFLATE 回退均失败时
     */
    @NotNull
    public static byte[] encode(@NotNull String key, @NotNull Tag value, @NotNull CompressorRegistry compressor, int threshold) throws IOException {
        // 块中保留完整类型名, 单独提取出来的载荷仍是可识别的 NBT 文档.
        CompoundTag compound = NBT.createCompound();
        compound.put(key, value);
        byte[] raw = NBT.toBytes(compound, false);
        CompressorRegistry used = raw.length < threshold ? CompressorRegistry.NONE : compressor;
        byte[] payload;
        try {
            payload = used.compress(raw);
        } catch (IOException exception) {
            // 配置的压缩器失败时使用 JVM 内置的 DEFLATE.
            if (used == CompressorRegistry.DEFLATE) throw exception;
            used = CompressorRegistry.DEFLATE;
            payload = used.compress(raw);
        }
        // 校验压缩后的载荷, 读方可以在调用解压器之前发现存储损坏.
        CRC32 crc = new CRC32();
        crc.update(payload);
        return ByteBuffer.allocate(BLOCK_HEADER_LENGTH + payload.length)
                .put(used.id())
                .putInt(payload.length)
                .putInt(raw.length)
                .putInt((int) crc.getValue())
                .put(payload)
                .array();
    }

    /**
     * 读取指定块的编码字段, 检查块头和 payload 声明的长度是否恰好占满索引确定的区间.
     * 此处不检查压缩算法是否受支持, 不计算 payload CRC, 也不解压或解析 NBT.
     *
     * @param block 来源数组和该块所在区间, 尚未校验其内容
     * @param key 完整类型名, 用于在错误中指出损坏的类型
     * @return 长度已通过结构校验的块头
     * @throws FormatException 当区间越界, 块头截断, 长度为负或 payload 与区间边界不一致时
     */
    @NotNull
    public static BlockHeader readHeader(@NotNull RawBlock block, @NotNull String key) throws FormatException {
        // 区间保留 long 偏移, 通过来源数组边界检查后才转为下标, 避免大偏移溢出后指向其他位置.
        if (block.offset() < 0 || block.end() < block.offset() || block.end() > block.bytes().length) {
            throw new FormatException(InvalidReason.CORRUPTED, "block out of bounds for " + key);
        }
        if (block.end() - block.offset() < BLOCK_HEADER_LENGTH) {
            throw new FormatException(InvalidReason.CORRUPTED, "truncated block header for " + key);
        }
        ByteBuffer header = ByteBuffer.wrap(block.bytes(), (int) block.offset(), BLOCK_HEADER_LENGTH);
        byte compressorId = header.get();
        int payloadLength = header.getInt();
        int rawLength = header.getInt();
        int checksum = header.getInt();
        if (payloadLength < 0 || rawLength < 0) {
            throw new FormatException(InvalidReason.CORRUPTED, "negative block length for " + key);
        }
        if (block.offset() + BLOCK_HEADER_LENGTH + (long) payloadLength != block.end()) {
            throw new FormatException(InvalidReason.CORRUPTED, "block payload length differs from block boundary for " + key);
        }
        return new BlockHeader(compressorId, payloadLength, rawLength, checksum);
    }

    /**
     * 校验并还原指定类型, 任何损坏都在异常中携带类型名.
     *
     * @param block 来源数组和该块所在区间, <strong>读取期间调用方不得修改字节</strong>
     * @param key 索引条目的完整类型名, 块内必须恰好含有此键
     * @return 块中该类型的 Tag, 引用缓存由调用方负责
     * @throws FormatException 当块越界, 校验失败, 算法未知或 NBT 损坏时
     */
    @NotNull
    public static Tag decode(@NotNull RawBlock block, @NotNull String key) throws IOException {
        BlockHeader header = readHeader(block, key);
        int payloadOffset = (int) block.offset() + BLOCK_HEADER_LENGTH;
        CRC32 crc = new CRC32();
        crc.update(block.bytes(), payloadOffset, header.payloadLength());
        if ((int) crc.getValue() != header.checksum()) {
            throw new FormatException(InvalidReason.CORRUPTED, "block checksum mismatch for " + key);
        }
        // 块头自述压缩算法
        Compressor compressor = CompressorRegistry.byId(header.compressorId());
        if (compressor == null) {
            throw new FormatException(InvalidReason.UNSUPPORTED_COMPRESSION, "unknown block compression id " + header.compressorId() + " for " + key);
        }
        // rawLength 同时约束解压分配与实际结果, CRC 校验在解压之前完成.
        try {
            byte[] raw = compressor.decompress(block.bytes(), payloadOffset, header.payloadLength(), header.rawLength());
            if (raw.length != header.rawLength()) {
                throw new IOException("decompressed length differs from block header");
            }
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw));
            Tag root = NBT.readUnnamedTag(input, false);
            if (!(root instanceof CompoundTag compound) || compound.size() != 1 || !compound.containsKey(key) || input.available() != 0) {
                throw new IOException("block root must contain exactly its indexed key");
            }
            return compound.get(key);
        } catch (IOException | RuntimeException exception) {
            FormatException failure = new FormatException(InvalidReason.CORRUPTED, "cannot decode block " + key + ": " + exception.getMessage());
            failure.initCause(exception);
            throw failure;
        }
    }
}
