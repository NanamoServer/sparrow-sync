package net.momirealms.sparrow.sync.codec;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.codec.compressor.Compressors;
import net.momirealms.sparrow.sync.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BinarySnapshotCodecTest {
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(Compressors.DEFLATE);

    @Test
    void roundTripPreservesSnapshot() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        byte[] bytes = this.codec.encode(snapshot);
        DecodedSnapshot decoded = this.codec.decode(bytes);

        // 大快照走配置的压缩器, 元数据与全部数据字段严格一致
        assertEquals(Compressors.DEFLATE.id(), bytes[3]);
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot();
        assertEquals(snapshot, restored);
    }

    @Test
    void smallSnapshotStoredUncompressed() throws IOException {
        // 载荷低于阈值时字节头声明 NONE
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of());

        byte[] bytes = this.codec.encode(snapshot);

        assertEquals(Compressors.NONE.id(), bytes[3]);
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(bytes)).snapshot());
    }

    @Test
    void decodeSelectsCompressorByHeaderNotByConfiguration() throws IOException {
        // DEFLATE 配置写出的字节, 由 NONE 配置的实例解码
        Snapshot snapshot = SnapshotFixtures.snapshot();
        byte[] bytes = this.codec.encode(snapshot);
        BinarySnapshotCodec plainCodec = new BinarySnapshotCodec(Compressors.NONE);

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
    void decodeRejectsUnknownCompression() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        bytes[3] = 9;

        DecodedSnapshot decoded = this.codec.decode(bytes);

        assertEquals(InvalidReason.UNSUPPORTED_COMPRESSION, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void decodeReportsTruncatedBodyAsCorrupted() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());

        DecodedSnapshot decoded = this.codec.decode(Arrays.copyOf(bytes, bytes.length / 2));

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void decodeReportsTooShortInputAsCorrupted() {
        DecodedSnapshot decoded = this.codec.decode(new byte[]{'S', 'S'});

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void decodeReportsNonCompoundRootAsCorrupted() throws IOException {
        // 手工构造一个根为 string 的合法字节头载荷
        byte[] body;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(out)) {
            NBT.writeUnnamedTag(NBT.createString("not a snapshot"), data, false);
            body = out.toByteArray();
        }
        byte[] bytes = new byte[4 + body.length];
        bytes[0] = 'S';
        bytes[1] = 'S';
        bytes[2] = 1;
        bytes[3] = 0;
        System.arraycopy(body, 0, bytes, 4, body.length);

        DecodedSnapshot decoded = this.codec.decode(bytes);

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }
}
