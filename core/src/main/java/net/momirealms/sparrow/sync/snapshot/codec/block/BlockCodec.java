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
     * 编码一个类型, 返回 13 字节块头和 NBT 数据, 低于阈值时不压缩.
     * @param threshold 包含类型名的 NBT 字节数阈值
     * @throws IOException NBT 编码失败或压缩器及 DEFLATE 回退均失败时
     */
    @NotNull
    public static byte[] encode(@NotNull String key, @NotNull Tag value, @NotNull CompressorRegistry compressor, int threshold) throws IOException {
        // 块内保存完整类型名, 单独取出也可作为 NBT 文档读取
        CompoundTag compound = NBT.createCompound();
        compound.put(key, value);
        byte[] raw = NBT.toBytes(compound, false);
        CompressorRegistry used = raw.length < threshold ? CompressorRegistry.NONE : compressor;
        byte[] payload;
        try {
            payload = used.compress(raw);
        } catch (IOException exception) {
            // 配置的压缩器失败时尝试 DEFLATE
            if (used == CompressorRegistry.DEFLATE) throw exception;
            used = CompressorRegistry.DEFLATE;
            payload = used.compress(raw);
        }
        // 对存储字节计算 CRC, 读取时先校验再解压
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
     * 读取块头并检查声明长度是否恰好占满索引区间, 不检查算法和块内容.
     * @throws FormatException 区间越界、块头截断或长度不符时
     */
    @NotNull
    public static BlockHeader readHeader(@NotNull RawBlock block, @NotNull String key) throws FormatException {
        // 先检查 long 偏移是否在数组内, 再转换为 int 下标
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
     * 校验、解压并解析指定数据块, 错误中包含类型名.
     * @param block <strong>读取期间不得修改来源字节</strong>
     * @param key 块内必须恰好包含此类型名
     * @throws FormatException 区间越界、校验失败、算法未知或 NBT 损坏时
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
        // 先校验 CRC 再解压, 分配容量和实际结果都受 rawLength 限制
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
