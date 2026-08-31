package net.momirealms.sparrow.sync.codec;

import net.momirealms.sparrow.sync.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.JsonSnapshotCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSnapshotCodecTest {
    private final JsonSnapshotCodec codec = new JsonSnapshotCodec();

    @Test
    void roundTripKeepsMetaAndData() {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        DecodedSnapshot decoded = codec.decode(codec.encode(snapshot));

        // SNBT 往返对 Tag 类型保真, 数组与数值后缀原样回来
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot();
        assertEquals(snapshot.meta(), restored.meta());
        assertEquals(snapshot.data(), restored.data());
    }

    @Test
    void dataIsReadableSnbtInsteadOfBase64() {
        String json = codec.encode(SnapshotFixtures.snapshot());

        assertTrue(json.contains("heldSlot:3"), json);
        assertFalse(json.contains("$binary"), json);
    }

    @Test
    void missingIdReportsFieldName() {
        String json = "{\"player\": \"" + SnapshotFixtures.PLAYER + "\", \"ts\": 1, \"mcData\": 1, \"format\": 2, \"data\": {}}";

        DecodedSnapshot decoded = codec.decode(json);

        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, decoded);
        assertEquals(InvalidReason.CORRUPTED, invalid.reason());
        assertTrue(invalid.detail().contains("field 'id'"), invalid.detail());
    }

    @Test
    void newerFormatIsRejected() {
        DecodedSnapshot decoded = codec.decode("{\"format\": 99}");

        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, decoded);
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, invalid.reason());
    }

    @Test
    void malformedJsonIsCorrupted() {
        DecodedSnapshot decoded = codec.decode("definitely not json");

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void brokenSnbtReportsDataKey() {
        // 手改坏某个类型的 SNBT 时要点名是哪个 key, 这里的 compound 少了闭合花括号
        String json = "{\"id\": \"" + SnapshotFixtures.SNAPSHOT_ID + "\", \"player\": \"" + SnapshotFixtures.PLAYER
                + "\", \"ts\": 1, \"mcData\": 1, \"format\": 2, \"data\": {\"other:doc\": \"{origin:1\"}}";

        DecodedSnapshot decoded = codec.decode(json);

        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, decoded);
        assertEquals(InvalidReason.CORRUPTED, invalid.reason());
        assertTrue(invalid.detail().contains("other:doc"), invalid.detail());
    }
}
