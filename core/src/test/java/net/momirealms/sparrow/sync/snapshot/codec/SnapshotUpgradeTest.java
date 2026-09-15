package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotUpgradeTest {
    private final BinarySnapshotCodec binary = new BinarySnapshotCodec(CompressorRegistry.DEFLATE);
    private final DocumentSnapshotCodec document = new DocumentSnapshotCodec(new SnapshotDataCodec(CompressorRegistry.NONE));

    @Test
    void legacyBinaryFormatsAreRejected() throws IOException {
        for (int version : new int[]{0, 2, 3, 255}) {
            byte[] bytes = this.binary.encode(SnapshotFixtures.snapshot());
            bytes[0] = (byte) version;
            DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.binary.decode(bytes));
            assertEquals(InvalidReason.UNSUPPORTED_FORMAT, invalid.reason());
            assertTrue(invalid.detail().contains("supported range"));
        }
    }

    @Test
    void legacyDocumentDataIsRejectedButMetadataRemainsReadable() throws IOException {
        for (int version : new int[]{0, 2, 3}) {
            Document encoded = this.document.encode(SnapshotFixtures.snapshot());
            encoded.put("format", version);
            assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, this.document.decode(encoded)).reason());
            assertEquals(SnapshotFixtures.meta(), DocumentSnapshotCodec.decodeMeta(encoded));
        }
    }

    @Test
    void legacyNonUuidIdIsNotSynthesized() throws IOException {
        Document encoded = this.document.encode(SnapshotFixtures.snapshot());
        encoded.put("format", 1);
        encoded.put("_id", "legacy-object-id");
        assertThrows(ClassCastException.class, () -> DocumentSnapshotCodec.decodeMeta(encoded));
    }

    @Test
    void futureFormatIsRejectedEvenWhenItWouldStillParse() throws IOException {
        Document encoded = this.document.encode(SnapshotFixtures.snapshot());
        encoded.put("format", SnapshotCodec.CURRENT_VERSION + 1);
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, this.document.decode(encoded)).reason());
    }

    @Test
    void futureFormatStillListsInMetadata() throws IOException {
        Document encoded = this.document.encode(SnapshotFixtures.snapshot());
        encoded.put("format", SnapshotCodec.CURRENT_VERSION + 1);
        assertEquals(SnapshotFixtures.meta(), DocumentSnapshotCodec.decodeMeta(encoded));
    }

}
