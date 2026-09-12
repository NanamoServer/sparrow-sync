package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 验证摘要提取只依赖帧结构, 索引校验与块头, 损坏输入按整帧或单块分别降级. */
class SnapshotSummaryTest {
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0); // 保持测试块压缩存储

    /**
     * 正常摘要按物理块次序返回, 体量对应包含完整类型名的单键 NBT 文档.
     *
     * @throws IOException 当测试快照编码失败时
     */
    @Test
    void sizesMatchSerializedNbtAndKeepPhysicalOrder() throws IOException {
        Snapshot source = SnapshotFixtures.snapshot();
        Map<DataKey, Integer> expected = new LinkedHashMap<>();
        for (DataKey key : source.keys()) {
            var single = NBT.createCompound();
            single.put(key.asString(), source.data(key));
            expected.put(key, NBT.toBytes(single, false).length);
        }
        var summary = this.codec.summarize(this.codec.encode(source));
        assertEquals(expected, summary);
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(summary.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> summary.clear());
        assertEquals(Map.of(), this.codec.summarize(this.codec.encode(new Snapshot(source.meta(), Map.of()))));
    }

    /**
     * JSON, 独立数据帧与随机字节都不能成为完整快照摘要, 提取过程保持不抛出.
     *
     * @throws IOException 当测试数据帧编码失败时
     */
    @Test
    void nonSnapshotInputsHaveNoSummary() throws IOException {
        assertNull(assertDoesNotThrow(() -> this.codec.summarize("{\"data\":{}}".getBytes(StandardCharsets.UTF_8))));
        byte[] standalone = new SnapshotDataCodec(CompressorRegistry.DEFLATE).encode(SnapshotFixtures.snapshot().content());
        assertNull(assertDoesNotThrow(() -> this.codec.summarize(standalone)));
        Random random = new Random(7);
        for (int length : new int[]{0, 1, 6, 7, 9, 32, 300}) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            assertNull(assertDoesNotThrow(() -> this.codec.summarize(bytes)));
        }
        // 随机字节也可能碰巧通过版本与段边界, 此时仍须通过索引校验.
        byte[] bytes = new byte[300];
        random.nextBytes(bytes);
        ByteBuffer.wrap(bytes).put((byte) 1).putShort((short) 1).putInt(0).put((byte) 0)
                .put((byte) 1).putInt(20).putInt(0);
        assertNull(assertDoesNotThrow(() -> this.codec.summarize(bytes)));
    }

    /**
     * 外层或内嵌数据帧版本超出支持范围时, 摘要不可得.
     *
     * @param version 不受支持的版本字节
     * @throws IOException 当测试快照编码失败时
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 2, 255})
    void unsupportedFrameVersionsHaveNoSummary(int version) throws IOException {
        byte[] source = this.codec.encode(SnapshotFixtures.snapshot());
        for (int offset : new int[]{0, SnapshotFixtures.dataOffset(source)}) {
            byte[] bytes = source.clone();
            bytes[offset] = (byte) version;
            assertNull(assertDoesNotThrow(() -> this.codec.summarize(bytes)));
        }
    }

    /**
     * Meta 长度为零或超出正文, 索引长度非法以及截断都不能产出可信清单.
     *
     * @throws IOException 当测试快照编码失败时
     */
    @Test
    void invalidSegmentLengthsAndTruncationHaveNoSummary() throws IOException {
        byte[] source = this.codec.encode(SnapshotFixtures.snapshot());
        for (int length : new int[]{0, 65535, source.length - 7}) {
            byte[] bytes = source.clone();
            ByteBuffer.wrap(bytes).putShort(1, (short) length);
            assertNull(assertDoesNotThrow(() -> this.codec.summarize(bytes)));
        }
        int dataOffset = SnapshotFixtures.dataOffset(source);
        for (int length : new int[]{0, 6, dataOffset - 1, dataOffset + 8, SnapshotFixtures.blockBase(source) - 1}) {
            assertNull(assertDoesNotThrow(() -> this.codec.summarize(Arrays.copyOf(source, length))));
        }
        ByteBuffer.wrap(source).putInt(dataOffset + 1, -1);
        assertNull(this.codec.summarize(source));
    }

    /**
     * Meta 内容及其 CRC 都与类型摘要无关, 只损坏 Meta 时仍取得全部体量.
     *
     * @param position Meta 内容或 CRC 的字节位置
     * @throws IOException 当测试快照编码失败时
     */
    @ParameterizedTest
    @ValueSource(ints = {3, 7})
    void damagedMetaDoesNotDiscardIntactDataSummary(int position) throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        var expected = this.codec.summarize(bytes);
        bytes[position] ^= 0x7F;
        assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(bytes));
        assertEquals(expected, assertDoesNotThrow(() -> this.codec.summarize(bytes)));
    }

    /**
     * 索引必须通过 CRC 与内容校验, 任一失败都使整份清单不可得.
     *
     * @throws IOException 当测试索引和正文编码失败时
     */
    @Test
    void corruptChecksumAndInvalidIndexHaveNoSummary() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        int offset = SnapshotFixtures.dataOffset(bytes);
        bytes[offset + 5] ^= 1;
        assertNull(this.codec.summarize(bytes));
        byte[] invalid = SnapshotFixtures.nonCompoundIndexFrame();
        byte[] malformed = Arrays.copyOf(bytes, offset + invalid.length);
        System.arraycopy(invalid, 0, malformed, offset, invalid.length);
        assertNull(assertDoesNotThrow(() -> this.codec.summarize(malformed)));
    }

    /**
     * 单个块的长度字段或块头损坏时, 只把这一类型的体量标记为未知.
     *
     * @param damage 本次破坏的块头部分
     * @throws IOException 当测试快照编码失败时
     */
    @ParameterizedTest
    @ValueSource(strings = {"payload", "raw", "truncated"})
    void badBlockHeaderOnlyLosesItsOwnSize(String damage) throws IOException {
        byte[] source = this.codec.encode(SnapshotFixtures.snapshot());
        Snapshot located = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(source)).snapshot();
        DataKey last = new ArrayList<>(located.keys()).getLast();
        Map<DataKey, Integer> expected = new LinkedHashMap<>(this.codec.summarize(source));
        int offset = (int) located.content().raw(last).offset();
        byte[] bytes = damage.equals("truncated") ? Arrays.copyOf(source, offset + 12) : source.clone();
        if (!damage.equals("truncated")) {
            ByteBuffer.wrap(bytes).putInt(offset + (damage.equals("payload") ? 1 : 5), -1);
        }
        expected.put(last, -1);
        assertEquals(expected, assertDoesNotThrow(() -> this.codec.summarize(bytes)));
    }

    /**
     * 算法标识和 payload CRC 由内容预览处理, 摘要仍读取块头中的零字节体量.
     *
     * @throws IOException 当测试快照编码失败时
     */
    @Test
    void unknownCompressionAndPayloadDamageStillKeepDeclaredSizes() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        Map<DataKey, Integer> expected = new LinkedHashMap<>(this.codec.summarize(bytes));
        DataKey first = expected.keySet().iterator().next();
        int offset = SnapshotFixtures.blockBase(bytes);
        bytes[offset] = 99;
        bytes[offset + 13] ^= 1;
        ByteBuffer.wrap(bytes).putInt(offset + 5, 0);
        expected.put(first, 0);
        assertEquals(expected, this.codec.summarize(bytes));
    }
}
