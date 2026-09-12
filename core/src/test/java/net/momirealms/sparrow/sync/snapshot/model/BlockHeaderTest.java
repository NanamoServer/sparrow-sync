package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/** 验证索引读取, 块头结构检查与 payload 解码各自的失败边界. */
class BlockHeaderTest {
    private static final DataKey FIRST = DataKey.of("test", "first"); // 前块用于验证后块损坏时仍可读
    private static final DataKey LAST = DataKey.of("test", "last");   // 后块用于注入截断和长度错误

    private final SnapshotDataCodec codec = new SnapshotDataCodec(CompressorRegistry.DEFLATE, 0);

    /**
     * 长度字段损坏不阻止读取索引, 访问, 大小预览和逐块复制才报告对应类型.
     *
     * @param field 长度字段在块头中的偏移
     * @param value 注入的非法长度
     * @throws IOException 当测试数据帧编码失败时
     */
    @ParameterizedTest
    @CsvSource({"1,-1", "1,0", "1,2147483647", "5,-1"})
    void malformedLengthsFailOnlyWhenTheBlockIsAccessed(int field, int value) throws IOException {
        byte[] bytes = this.frame();
        RawBlock last = this.codec.decode(bytes).raw(LAST);
        ByteBuffer.wrap(bytes).putInt((int) last.offset() + field, value);
        LazySnapshotData data = (LazySnapshotData) this.codec.decode(bytes);
        assertEquals(List.of(FIRST, LAST), List.copyOf(data.keys()));
        assertEquals(0, data.decodedBlockCount());
        assertTrue(data.rawLength(FIRST) > 0);
        assertEquals(0, data.decodedBlockCount());
        assertTrue(assertThrows(UncheckedIOException.class, () -> data.rawLength(LAST)).getMessage().contains(LAST.asString()));
        assertTrue(assertThrows(UncheckedIOException.class, () -> data.get(LAST)).getMessage().contains(LAST.asString()));
        assertTrue(assertThrows(FormatException.class, () -> this.codec.encode(data.select(LAST::equals))).getMessage().contains(LAST.asString()));
        assertEquals(NBT.createString("first"), data.get(FIRST));
        // 整帧复制沿用已保存的数据帧区间, 不逐块读取结构或内容.
        assertArrayEquals(bytes, this.codec.encode(data));
    }

    /**
     * 在块头每个位置截断后, 索引仍可读, 前面的完整块不受影响.
     *
     * @throws IOException 当测试数据帧编解码失败时
     */
    @Test
    void everyTruncatedHeaderIsReportedLocally() throws IOException {
        byte[] original = this.frame();
        int lastOffset = (int) this.codec.decode(original).raw(LAST).offset();
        for (int kept = 0; kept < 13; kept++) {
            SnapshotData data = this.codec.decode(Arrays.copyOf(original, lastOffset + kept));
            assertEquals(NBT.createString("first"), data.get(FIRST));
            assertTrue(assertThrows(UncheckedIOException.class, () -> data.rawLength(LAST)).getMessage().contains(LAST.asString()));
            assertThrows(UncheckedIOException.class, () -> data.get(LAST));
            assertThrows(FormatException.class, () -> this.codec.encode(data.select(LAST::equals)));
        }
    }

    /**
     * 原始块透传允许未知算法及坏 CRC, 大小读取也不触碰 payload 解码.
     *
     * @param badCrc 是否同时破坏 payload, 使其与保存的 CRC 不符
     * @throws IOException 当测试帧编解码失败时
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copyingUnknownCompressionDoesNotValidatePayload(boolean badCrc) throws IOException {
        byte[] bytes = this.frame();
        RawBlock last = this.codec.decode(bytes).raw(LAST);
        bytes[(int) last.offset()] = 99;
        if (badCrc) {
            bytes[(int) last.offset() + 13] ^= 1;
        }
        LazySnapshotData data = (LazySnapshotData) this.codec.decode(bytes);
        assertTrue(data.rawLength(LAST) > 0);
        SnapshotData changed = data.with(FIRST, NBT.createString("replacement"));
        SnapshotData restored = this.codec.decode(this.codec.encode(changed));
        RawBlock copied = restored.raw(LAST);
        assertArrayEquals(Arrays.copyOfRange(bytes, (int) last.offset(), (int) last.end()),
                Arrays.copyOfRange(copied.bytes(), (int) copied.offset(), (int) copied.end()));
        assertEquals(0, data.decodedBlockCount());
        assertEquals(NBT.createString("replacement"), restored.get(FIRST));
        assertThrows(UncheckedIOException.class, () -> restored.get(LAST));
    }

    /**
     * 只凭偏移就能拒绝重复和负位置, 打乱索引写入顺序仍按物理顺序读取.
     *
     * @throws IOException 当测试索引序列化失败时
     */
    @Test
    void offsetsAreSortedAndDuplicateOrNegativeOffsetsAreRejected() throws IOException {
        byte[] frame = this.frame();
        SnapshotData source = this.codec.decode(frame);
        int base = SnapshotFixtures.blockBase(frame);
        int secondOffset = (int) source.raw(LAST).offset() - base;
        CompoundTag index = NBT.createCompound(new LinkedHashMap<>());
        index.putInt(LAST.asString(), secondOffset);
        index.putInt(FIRST.asString(), 0);
        SnapshotData restored = this.codec.decode(reindex(frame, index));
        assertEquals(List.of(FIRST, LAST), List.copyOf(restored.keys()));
        assertEquals(source.all(), restored.all());
        index.putInt(LAST.asString(), 0);
        assertThrows(FormatException.class, () -> this.codec.decode(reindex(frame, index)));
        index.putInt(LAST.asString(), -1);
        assertThrows(FormatException.class, () -> this.codec.decode(reindex(frame, index)));
    }

    /**
     * 索引边界留出的额外字节不能被逐块复制静默忽略.
     *
     * @throws IOException 当测试帧编解码失败时
     */
    @Test
    void trailingBytesDisagreeWithTheLastBlockHeader() throws IOException {
        byte[] frame = this.frame();
        SnapshotData data = this.codec.decode(Arrays.copyOf(frame, frame.length + 1));
        assertEquals(NBT.createString("first"), data.get(FIRST));
        assertThrows(UncheckedIOException.class, () -> data.get(LAST));
        assertThrows(FormatException.class, () -> this.codec.encode(data.select(LAST::equals)));
    }

    /**
     * 创建两个连续的压缩块, 输入顺序固定以便注入后块损坏.
     *
     * @return 独立 SD 数据帧
     * @throws IOException 当 NBT 编码或压缩失败时
     */
    private byte[] frame() throws IOException {
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        values.put(FIRST, NBT.createString("first"));
        values.put(LAST, NBT.createString("last"));
        return this.codec.encode(new EagerSnapshotData(values));
    }

    /**
     * 用指定索引重建测试数据帧, 保留所有块字节并重新计算索引 CRC.
     *
     * @param frame 独立 SD 数据帧
     * @param index 要注入的索引树
     * @return 索引 CRC 正确的新数据帧
     * @throws IOException 当测试索引编码失败时
     */
    private static byte[] reindex(byte[] frame, CompoundTag index) throws IOException {
        byte[] encoded = NBT.toBytes(index, false);
        int base = SnapshotFixtures.blockBase(frame);
        CRC32 crc = new CRC32();
        crc.update(encoded);
        return ByteBuffer.allocate(11 + encoded.length + frame.length - base)
                .put((byte) 'S').put((byte) 'D').put((byte) 1)
                .putInt(encoded.length).putInt((int) crc.getValue()).put(encoded)
                .put(frame, base, frame.length - base).array();
    }
}
