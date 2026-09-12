package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.LazySnapshotData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinarySnapshotCodecTest {
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE);

    @Test
    void roundTripPreservesSnapshot() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        byte[] bytes = this.codec.encode(snapshot);
        DecodedSnapshot decoded = this.codec.decode(bytes);

        // SS 完整快照携带 Meta 和独立数据帧, 元数据与全部类型字段往返一致
        assertEquals(SnapshotCodec.CURRENT_VERSION, bytes[2]);
        assertEquals('S', bytes[1]);
        assertEquals('D', bytes[SnapshotFixtures.dataOffset(bytes) + 1]);
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot();
        assertEquals(snapshot, restored);
    }

    @Test
    void encodingTheSameSnapshotTwiceYieldsIdenticalBytes() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        // 数据体隔了一层取用后, 迭代顺序仍跟随构造时的 Map, 编码结果逐字节可重复
        assertArrayEquals(this.codec.encode(snapshot), this.codec.encode(snapshot));
    }

    @Test
    void smallSnapshotStoredUncompressed() throws IOException {
        // 空数据仍然携带完整元数据, 索引为空
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of());

        byte[] bytes = this.codec.encode(snapshot);

        assertEquals('S', bytes[1]);
        assertEquals('D', bytes[SnapshotFixtures.dataOffset(bytes) + 1]);
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(bytes)).snapshot());
    }

    @Test
    void decodeSelectsCompressorByHeaderNotByConfiguration() throws IOException {
        // DEFLATE 配置写出的字节, 由 NONE 配置的实例解码
        Snapshot snapshot = SnapshotFixtures.snapshot();
        byte[] bytes = this.codec.encode(snapshot);
        BinarySnapshotCodec plainCodec = new BinarySnapshotCodec(CompressorRegistry.NONE);

        DecodedSnapshot decoded = plainCodec.decode(bytes);

        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot());
    }

    /**
     * 数据库保存的数据帧与完整快照中的数据段逐字节一致, 读取完整快照后继续引用原数组.
     *
     * @param compressor 本轮新块的压缩算法
     * @throws Exception 当编解码或缓存计数读取失败时
     */
    @ParameterizedTest
    @EnumSource(CompressorRegistry.class)
    void embeddedDataFrameMatchesStandaloneBytes(CompressorRegistry compressor) throws Exception {
        SnapshotDataCodec dataCodec = new SnapshotDataCodec(compressor, 0);
        BinarySnapshotCodec codec = new BinarySnapshotCodec(dataCodec);
        Snapshot source = SnapshotFixtures.snapshot();
        byte[] standalone = dataCodec.encode(source.content());
        byte[] complete = codec.encode(source);
        int offset = SnapshotFixtures.dataOffset(complete);
        assertArrayEquals(standalone, Arrays.copyOfRange(complete, offset, complete.length));
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(complete)).snapshot();
        LazySnapshotData lazy = assertInstanceOf(LazySnapshotData.class, restored.content());
        assertSame(complete, lazy.frameBytes());
        assertEquals(offset, lazy.frameOffset());
        assertEquals(standalone.length, lazy.frameLength());
        assertArrayEquals(standalone, dataCodec.encode(lazy));
        assertEquals(0, SnapshotFixtures.decodedBlockCount(restored));
    }

    /**
     * Meta 损坏使完整快照读取失败, 内部独立数据帧仍可单独校验和读取.
     *
     * @throws IOException 当测试快照编解码失败时
     */
    @Test
    void metadataChecksumIsIndependentOfDataFrame() throws IOException {
        Snapshot source = SnapshotFixtures.snapshot();
        byte[] complete = this.codec.encode(source);
        int dataOffset = SnapshotFixtures.dataOffset(complete);
        complete[9] ^= 1;
        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(complete));
        assertEquals(InvalidReason.CORRUPTED, invalid.reason());
        assertTrue(invalid.detail().contains("meta checksum"));
        SnapshotDataCodec dataCodec = new SnapshotDataCodec(CompressorRegistry.NONE);
        assertEquals(source.allData(), dataCodec.decode(Arrays.copyOfRange(complete, dataOffset, complete.length)).all());
    }

    /**
     * 完整快照需要有效的 Meta 段长, 缺失或超出数组范围时在段读取前拒绝.
     *
     * @throws IOException 当测试快照编码失败时
     */
    @Test
    void metadataLengthIsRequiredAndBounded() throws IOException {
        for (int length : new int[]{0, 65535}) {
            byte[] complete = this.codec.encode(SnapshotFixtures.snapshot());
            ByteBuffer.wrap(complete).putShort(3, (short) length);
            DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(complete));
            assertEquals(InvalidReason.CORRUPTED, invalid.reason());
            assertTrue(invalid.detail().contains("meta length"));
        }
    }

    @Test
    void decodeRejectsBadMagic() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        bytes[0] = 'X';

        DecodedSnapshot decoded = this.codec.decode(bytes);

        assertEquals(InvalidReason.BAD_MAGIC, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void decodeRejectsFutureFormat() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        bytes[2] = 99;

        DecodedSnapshot decoded = this.codec.decode(bytes);

        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void decodeReportsTruncatedBodyAsCorrupted() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());

        DecodedSnapshot decoded = this.codec.decode(Arrays.copyOf(bytes, SnapshotFixtures.blockBase(bytes) - 1));

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void decodeReportsTooShortInputAsCorrupted() {
        DecodedSnapshot decoded = this.codec.decode(new byte[]{'S', 'S'});

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }


}
