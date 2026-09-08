package net.momirealms.sparrow.sync.storage.postgresql;

import net.momirealms.sparrow.sync.storage.SnapshotRow;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证行快照的数据保真、元信息独立性和无效内容的错误分类.
 */
class PostgresRowSnapshotCodecTest {
    private final PostgresRowSnapshotCodec codec = new PostgresRowSnapshotCodec(new BinarySnapshotCodec(CompressorRegistry.DEFLATE)); // 统一使用的读取入口, 用于验证不同写入压缩方式的兼容性

    /**
     * 验证读取压缩方式取自帧头, 可以读取由不同压缩器写出的快照.
     *
     * @param compressor 本轮写入使用的压缩器
     * @throws IOException 当测试快照编码失败时
     */
    @ParameterizedTest
    @EnumSource(CompressorRegistry.class)
    void everyCompressorCanBeReadByTheSameDecoder(CompressorRegistry compressor) throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();
        SnapshotRow row = new PostgresRowSnapshotCodec(new BinarySnapshotCodec(compressor)).encode(snapshot);
        assertSame(snapshot.meta(), row.meta());
        assertEquals(SnapshotCodec.CURRENT_VERSION, row.format());
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(row)).snapshot());
    }

    /**
     * 验证所有 NBT 标签类型以及未注册的数据键能够完整往返.
     *
     * @throws IOException 当测试快照编码失败时
     */
    @Test
    void dataFrameKeepsEveryTagTypeAndUnknownKeys() throws IOException {
        // 覆盖数值、数组、容器和 Unicode 文本, 使用未注册键模拟第三方数据.
        CompoundTag data = NBT.createCompound();
        data.putByte("byte", (byte) 1);
        data.putShort("short", (short) 2);
        data.putInt("int", 3);
        data.putLong("long", 4);
        data.putFloat("float", 5.5F);
        data.putDouble("double", 6.5);
        data.putString("string", "大小写 É😀");
        data.putByteArray("bytes", new byte[]{-1, 2});
        data.putIntArray("ints", new int[]{Integer.MIN_VALUE, 3});
        data.putLongArray("longs", new long[]{Long.MIN_VALUE, 4});
        ListTag list = NBT.createList();
        list.add(NBT.createString("nested"));
        data.put("list", list);
        data.put("compound", NBT.createCompound());
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of(SnapshotFixtures.UNKNOWN_DOC, data));
        SnapshotRow row = this.codec.encode(snapshot);
        // 检查帧内数据结构, 再验证完整快照往返结果.
        CompoundTag frame = assertInstanceOf(CompoundTag.class, new BinarySnapshotCodec(CompressorRegistry.NONE).deframe(row.data()));
        assertEquals(1, frame.size());
        assertEquals(data, frame.get(SnapshotFixtures.UNKNOWN_DOC.asString()));
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(row)).snapshot());
    }

    /**
     * 验证空数据快照可读, 且固定标记变更使用已有数据帧.
     *
     * @throws IOException 当测试快照编码失败时
     */
    @Test
    void emptyDataAndChangedMetadataNeedNoPayloadRewrite() throws IOException {
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of());
        SnapshotRow row = this.codec.encode(snapshot);
        // 复用原数组并只替换元信息, 验证固定状态与帧内容独立.
        SnapshotRow pinned = new SnapshotRow(row.meta().withPinned(true), row.format(), row.data());
        Snapshot decoded = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(pinned)).snapshot();
        assertTrue(decoded.meta().pinned());
        assertEquals(Map.of(), decoded.data());
        assertSame(row.data(), pinned.data());
    }

    /**
     * 验证未知行版本优先返回格式错误, 即使二进制内容为空.
     *
     * @param format 当前行编解码器不支持的版本
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 3, 255})
    void unsupportedRowFormatsFailBeforeReadingData(int format) {
        DecodedSnapshot decoded = this.codec.decode(new SnapshotRow(SnapshotFixtures.meta(), format, new byte[0]));
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    /**
     * 验证帧头、压缩标记、根标签和版本不一致各自返回约定的错误原因.
     *
     * @throws IOException 当有效对照帧编码失败时
     */
    @Test
    void malformedFramesPreserveTheFailureReason() throws IOException {
        SnapshotRow row = this.codec.encode(SnapshotFixtures.snapshot());
        assertEquals(InvalidReason.CORRUPTED, this.reason(new byte[]{'S', 'S'}));
        // 每种损坏都从有效帧重新复制, 让失败原因只对应当前注入的错误.
        byte[] bytes = row.data().clone();
        bytes[0] = 0;
        assertEquals(InvalidReason.BAD_MAGIC, this.reason(bytes));
        bytes = row.data().clone();
        bytes[2] = 99;
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, this.reason(bytes));
        bytes = row.data().clone();
        bytes[3] = 99;
        assertEquals(InvalidReason.UNSUPPORTED_COMPRESSION, this.reason(bytes));
        bytes = row.data().clone();
        bytes[2] = 1;
        assertEquals(InvalidReason.CORRUPTED, this.reason(bytes));
        assertEquals(InvalidReason.CORRUPTED, this.reason(new BinarySnapshotCodec(CompressorRegistry.NONE).frame(NBT.createInt(3))));
    }

    /**
     * 验证 UUID 字节顺序与文本表示对应, 并拒绝长度错误的数据库内容.
     */
    @Test
    void uuidEncodingHasStableByteOrder() {
        UUID uuid = UUID.fromString("fedcba98-7654-3210-0123-456789abcdef");
        byte[] bytes = UUIDUtils.toBytes(uuid);
        assertEquals("fedcba98765432100123456789abcdef", HexFormat.of().formatHex(bytes));
        assertEquals(uuid, UUIDUtils.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> UUIDUtils.fromBytes(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> UUIDUtils.fromBytes(new byte[17]));
    }

    /**
     * 读取坏帧的解码原因, 将错误分类断言集中在测试调用处.
     *
     * @param bytes 待验证的二进制帧
     * @return 解码返回的无效原因
     */
    private InvalidReason reason(byte[] bytes) {
        return assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(new SnapshotRow(SnapshotFixtures.meta(), 2, bytes))).reason();
    }
}
