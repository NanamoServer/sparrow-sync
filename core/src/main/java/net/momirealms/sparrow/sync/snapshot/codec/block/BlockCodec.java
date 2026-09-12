package net.momirealms.sparrow.sync.snapshot.codec.block;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.Compressor;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.model.BlockIndex;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * 一个数据类型的二进制编码, 每块都能独立校验和解压.
 * 块头依次保存 1 字节算法, 4 字节原始长度和 4 字节 payload CRC32, 后接单键 compound 的载荷.
 * 多字节整数使用大端字节序, CRC32 覆盖压缩后的字节.
 */
public final class BlockCodec {
    public static final int BLOCK_HEADER_LENGTH = 9; // 算法 1 字节, 原始长度与载荷 CRC 各 4 字节

    private BlockCodec() {
    }

    /**
     * 把一个类型封装为带数据库块头数据的独立 NBT 文档, 小于阈值时明文存储.
     *
     * @param key 完整 DataKey 文本, 同时写入块内 compound
     * @param value 该类型的原始 Tag
     * @param compressor 达到阈值时使用的压缩算法
     * @param threshold 原始单键 compound 的压缩阈值, 单位为字节
     * @return 9 字节块头与 payload 连续组成的字节数组
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
                .putInt(raw.length)
                .putInt((int) crc.getValue())
                .put(payload)
                .array();
    }

    /**
     * 校验并还原指定类型, 任何损坏都在异常中携带类型名.
     *
     * @param frame 包含块区的完整帧, <strong>读取期间调用方不得修改</strong>
     * @param blockBase 帧中块区的绝对起点, 索引偏移以此为基准
     * @param key 索引条目的完整类型名, 块内必须恰好含有此键
     * @param entry 已读取的块索引, 不要求该块实际字节完整
     * @return 块中该类型的 Tag, 引用缓存由调用方负责
     * @throws FormatException 当块越界, 校验失败, 算法未知或 NBT 损坏时
     */
    @NotNull
    public static Tag decode(byte @NotNull [] frame, int blockBase, @NotNull String key, @NotNull BlockIndex entry) throws IOException {
        // 使用 long 计算末端, 索引中的大偏移仍按越界报告.
        long startLong = (long) blockBase + entry.offset();
        long end = startLong + BLOCK_HEADER_LENGTH + entry.length();
        if (blockBase < 0 || entry.offset() < 0 || entry.length() < 0 || startLong < 0 || end > frame.length) {
            throw new FormatException(InvalidReason.CORRUPTED, "block out of bounds for " + key);
        }
        int start = (int) startLong;
        ByteBuffer header = ByteBuffer.wrap(frame, start, BLOCK_HEADER_LENGTH);
        byte compressorId = header.get();
        int rawLength = header.getInt();
        int checksum = header.getInt();
        if (rawLength < 0 || rawLength != entry.rawLength()) {
            throw new FormatException(InvalidReason.CORRUPTED, "block raw length differs from index for " + key);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, start + BLOCK_HEADER_LENGTH, entry.length());
        if ((int) crc.getValue() != checksum) {
            throw new FormatException(InvalidReason.CORRUPTED, "block checksum mismatch for " + key);
        }
        // 块头自述压缩算法
        Compressor compressor = CompressorRegistry.byId(compressorId);
        if (compressor == null) {
            throw new FormatException(InvalidReason.UNSUPPORTED_COMPRESSION, "unknown block compression id " + compressorId + " for " + key);
        }
        // rawLength 同时约束解压分配与实际结果, CRC 校验在解压之前完成.
        try {
            byte[] raw = compressor.decompress(frame, start + BLOCK_HEADER_LENGTH, entry.length(), rawLength);
            if (raw.length != rawLength) {
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
