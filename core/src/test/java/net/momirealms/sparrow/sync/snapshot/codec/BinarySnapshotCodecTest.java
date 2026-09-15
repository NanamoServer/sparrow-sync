package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.LazySnapshotData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
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

    @BeforeAll
    static void initializeZstd() throws Exception {
        ZstdTestSupport.initialize();
    }

    @Test
    void roundTripPreservesSnapshot() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        byte[] bytes = this.codec.encode(snapshot);
        DecodedSnapshot decoded = this.codec.decode(bytes);

        assertEquals(SnapshotCodec.CURRENT_VERSION, bytes[0]);
        assertEquals(1, bytes[0]);
        assertEquals(1, bytes[SnapshotFixtures.dataOffset(bytes)]);
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot();
        assertEquals(snapshot, restored);
    }

    @Test
    void encodingTheSameSnapshotTwiceYieldsIdenticalBytes() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        assertArrayEquals(this.codec.encode(snapshot), this.codec.encode(snapshot));
    }

    @Test
    void smallSnapshotStoredUncompressed() throws IOException {
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of());

        byte[] bytes = this.codec.encode(snapshot);

        assertEquals(1, bytes[0]);
        assertEquals(1, bytes[SnapshotFixtures.dataOffset(bytes)]);
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(bytes)).snapshot());
    }

    @Test
    void decodeSelectsCompressorByHeaderNotByConfiguration() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();
        byte[] bytes = this.codec.encode(snapshot);
        BinarySnapshotCodec plainCodec = new BinarySnapshotCodec(CompressorRegistry.NONE);

        DecodedSnapshot decoded = plainCodec.decode(bytes);

        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot());
    }

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

    @Test
    void metadataChecksumIsIndependentOfDataFrame() throws IOException {
        Snapshot source = SnapshotFixtures.snapshot();
        byte[] complete = this.codec.encode(source);
        int dataOffset = SnapshotFixtures.dataOffset(complete);
        complete[7] ^= 1;
        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(complete));
        assertEquals(InvalidReason.CORRUPTED, invalid.reason());
        assertTrue(invalid.detail().contains("meta checksum"));
        SnapshotDataCodec dataCodec = new SnapshotDataCodec(CompressorRegistry.NONE);
        assertEquals(source.allData(), dataCodec.decode(Arrays.copyOfRange(complete, dataOffset, complete.length)).all());
    }

    @Test
    void metadataLengthIsRequiredAndBounded() throws IOException {
        for (int length : new int[]{0, 65535}) {
            byte[] complete = this.codec.encode(SnapshotFixtures.snapshot());
            ByteBuffer.wrap(complete).putShort(1, (short) length);
            DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(complete));
            assertEquals(InvalidReason.CORRUPTED, invalid.reason());
            assertTrue(invalid.detail().contains("meta length"));
        }
    }

    @Test
    void decodeRejectsZeroFormat() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        bytes[0] = 0;

        DecodedSnapshot decoded = this.codec.decode(bytes);

        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void decodeRejectsFutureFormat() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        bytes[0] = 99;

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
