package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BinarySnapshotCodecTest {
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE);

    @Test
    void roundTripPreservesSnapshot() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        byte[] bytes = this.codec.encode(snapshot);
        DecodedSnapshot decoded = this.codec.decode(bytes);

        // 完整容器设置 HAS_META, 元数据与全部数据字段严格一致
        assertEquals(SnapshotCodec.CURRENT_VERSION, bytes[2]);
        assertEquals(1, bytes[3]);
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

        assertEquals(1, bytes[3]);
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
