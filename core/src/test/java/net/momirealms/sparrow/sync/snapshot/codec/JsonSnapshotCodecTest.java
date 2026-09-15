package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSnapshotCodecTest {
    private final JsonSnapshotCodec codec = new JsonSnapshotCodec();

    @BeforeAll
    static void initializeProxy() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @Test
    void jsonExportDecodesUnknownBlockOnlyWhenRequested() throws Exception {
        DataKey unknown = DataKey.of("external", "book");
        CompoundTag value = NBT.createCompound();
        value.putInt("__v", 7);
        value.putString("entry", "retained");
        BinarySnapshotCodec binary = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0);
        Snapshot source = assertInstanceOf(DecodedSnapshot.Valid.class,
                binary.decode(binary.encode(new Snapshot(SnapshotFixtures.meta(), Map.of(unknown, value))))).snapshot();
        var subset = source.content().select(unknown::equals);
        SnapshotDataCodec dataCodec = new SnapshotDataCodec(CompressorRegistry.DEFLATE, 0);
        Snapshot retained = new Snapshot(source.meta(), dataCodec.decode(dataCodec.encode(subset)));
        assertEquals(0, SnapshotFixtures.decodedBlockCount(source));
        assertEquals(0, SnapshotFixtures.decodedBlockCount(retained));
        binary.encode(retained);
        assertEquals(0, SnapshotFixtures.decodedBlockCount(retained));

        String json = this.codec.encode(retained);
        assertEquals(1, SnapshotFixtures.decodedBlockCount(retained));
        assertEquals(0, SnapshotFixtures.decodedBlockCount(source));
        assertFalse(Document.parse(json).containsKey("dataVersions"));
        Snapshot imported = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(json)).snapshot();
        assertInstanceOf(EagerSnapshotData.class, imported.content());
        assertEquals(value, imported.data(unknown));
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, binary.decode(binary.encode(imported))).snapshot();
        assertEquals(value, restored.data(unknown));
    }

    @Test
    void eachJsonValueIsSnbtAndPreservesTypeOwnedVersion() throws Exception {
        DataKey key = DataKey.of("external", "state");
        CompoundTag tag = NBT.createCompound();
        tag.putInt("__v", 9);
        tag.putString("value", "preserved");
        Snapshot source = new Snapshot(SnapshotFixtures.meta(), Map.of(key, tag));
        Document json = Document.parse(this.codec.encode(source));
        String snbt = assertInstanceOf(String.class, json.get("data", Document.class).get(key.asString()));
        assertTrue(snbt.contains("__v:9"));
        Snapshot imported = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(json.toJson())).snapshot();
        assertEquals(source, imported);
        BinarySnapshotCodec binary = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0);
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, binary.decode(binary.encode(imported))).snapshot();
        assertEquals(0, SnapshotFixtures.decodedBlockCount(restored));
        assertEquals(tag, restored.data(key));
    }

    @Test
    void nonStringTypeValuesAreRejected() {
        Document document = Document.parse(this.codec.encode(SnapshotFixtures.snapshot()));
        Document data = document.get("data", Document.class);
        String key = data.keySet().iterator().next();
        String snbt = data.getString(key);
        for (Object value : List.of(new Document("meta", new Document()).append("data", snbt),
                new Document("data", snbt), 1, true, List.of(snbt))) {
            data.put(key, value);
            DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document.toJson()));
            assertEquals(InvalidReason.CORRUPTED, invalid.reason());
            assertTrue(invalid.detail().contains(key));
        }
        data.put(key, null);
        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document.toJson())).reason());
    }

    @Test
    void roundTripKeepsMetaAndData() {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        DecodedSnapshot decoded = codec.decode(codec.encode(snapshot));

        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot();
        assertEquals(snapshot.meta(), restored.meta());
        assertEquals(snapshot.allData(), restored.allData());
    }

    @Test
    void dataIsReadableSnbtInsteadOfBase64() {
        String json = codec.encode(SnapshotFixtures.snapshot());

        assertTrue(json.contains("heldSlot:3"), json);
        assertFalse(json.contains("$binary"), json);
    }

    @Test
    void missingIdReportsFieldName() {
        String json = "{\"player\": \"" + SnapshotFixtures.PLAYER + "\", \"ts\": 1, \"mcData\": 1, \"format\": " + SnapshotCodec.CURRENT_VERSION + ", \"data\": {}}";

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
    void versionsOutsideTheNewFormatRangeAreRejected() {
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode("{\"format\": 0}")).reason());
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode("{\"format\": 2}")).reason());
    }

    @Test
    void malformedJsonIsCorrupted() {
        DecodedSnapshot decoded = codec.decode("definitely not json");

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void brokenSnbtReportsDataKey() {
        Document document = Document.parse(this.codec.encode(SnapshotFixtures.snapshot()));
        document.get("data", Document.class).put("other:doc", "{origin:1");
        String json = document.toJson();

        DecodedSnapshot decoded = codec.decode(json);

        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, decoded);
        assertEquals(InvalidReason.CORRUPTED, invalid.reason());
        assertTrue(invalid.detail().contains("other:doc"), invalid.detail());
    }
}
