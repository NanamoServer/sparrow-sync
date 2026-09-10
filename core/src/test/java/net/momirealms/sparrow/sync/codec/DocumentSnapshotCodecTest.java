package net.momirealms.sparrow.sync.codec;

import com.mongodb.MongoClientSettings;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ByteTag;
import net.momirealms.sparrow.nbt.FloatTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.JsonSnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.types.Binary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentSnapshotCodecTest {
    private final DocumentSnapshotCodec codec = new DocumentSnapshotCodec(new BinarySnapshotCodec(CompressorRegistry.DEFLATE));

    @BeforeAll
    static void initializeProxy() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @ParameterizedTest
    @EnumSource(CompressorRegistry.class)
    void largeRawDataRoundTripsAndOnlyEncodedFrameSizeDependsOnCompression(CompressorRegistry compressor) throws IOException {
        var codec = new DocumentSnapshotCodec(new BinarySnapshotCodec(compressor));
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of(SnapshotFixtures.UNKNOWN_DOC, NBT.createByteArray(new byte[17 * 1024 * 1024])));
        var encoded = codec.encode(snapshot);
        int frameSize = encoded.get("data", Binary.class).getData().length;
        assertEquals(compressor == CompressorRegistry.NONE, frameSize > 15 * 1024 * 1024);
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(encoded)).snapshot());
    }

    @Test
    void roundTripPreservesSnapshot() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        Document document = this.codec.encode(snapshot);
        DecodedSnapshot decoded = this.codec.decode(document);

        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot());
    }

    @Test
    void dataIsOneFrameContainingOnlyDataKeys() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();
        Document document = this.codec.encode(snapshot);

        Binary payload = assertInstanceOf(Binary.class, document.get("data"));
        CompoundTag data = assertInstanceOf(CompoundTag.class, new BinarySnapshotCodec(CompressorRegistry.NONE).deframe(payload.getData()));

        assertEquals(snapshot.data().size(), data.size());
        snapshot.data().forEach((key, value) -> assertEquals(value, data.get(key.asString())));
        assertFalse(data.containsKey("player"));
        assertFalse(data.containsKey("id"));
    }

    @Test
    void metaFieldsStoredAtTopLevel() throws IOException {
        Document document = this.codec.encode(SnapshotFixtures.snapshot());

        assertEquals(SnapshotFixtures.PLAYER, document.get("player"));
        assertEquals(SnapshotFixtures.SNAPSHOT_ID, document.get("_id"));
        assertEquals("DISCONNECT", document.getString("cause"));
        assertEquals(SnapshotCodec.CURRENT_VERSION, document.getInteger("format"));
        assertEquals(1_756_300_000_000L, document.getDate("ts").getTime());
    }

    @Test
    void unknownFieldsSurviveDocumentRoundTrip() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();

        // 本服未注册的两个字段原样透传
        assertEquals(snapshot.data(SnapshotFixtures.UNKNOWN_BLOB), restored.data(SnapshotFixtures.UNKNOWN_BLOB));
        assertEquals(snapshot.data(SnapshotFixtures.UNKNOWN_DOC), restored.data(SnapshotFixtures.UNKNOWN_DOC));
    }

    @Test
    void narrowNumericTypesKeepTheirTagTypes() throws IOException {
        CompoundTag structured = NBT.createCompound();
        structured.putBoolean("flying", true);
        structured.putFloat("exhaustion", 1.5f);
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of(SnapshotFixtures.HEALTH, structured));

        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();

        CompoundTag health = (CompoundTag) restored.data(SnapshotFixtures.HEALTH);
        assertInstanceOf(ByteTag.class, health.get("flying"));
        assertInstanceOf(FloatTag.class, health.get("exhaustion"));
        assertTrue(health.getBoolean("flying"));
        assertEquals(1.5f, health.getFloat("exhaustion"));
        assertEquals(snapshot, restored);
    }

    @Test
    void decodeRejectsMissingOrNullData() throws IOException {
        Document document = this.codec.encode(SnapshotFixtures.snapshot());
        document.remove("data");

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document)).reason());
        document.put("data", null);
        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document)).reason());
    }

    @Test
    void emptyDataRoundTripsAsAnEmptyCompoundFrame() throws IOException {
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of());

        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot());
    }

    @Test
    void metadataCanBeReadWithoutDecodingData() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();
        Document document = this.codec.encode(snapshot);
        document.remove("data");

        assertEquals(snapshot.meta(), DocumentSnapshotCodec.decodeMeta(document));
        document.put("data", new Binary(new byte[]{0}));
        assertEquals(snapshot.meta(), DocumentSnapshotCodec.decodeMeta(document));
    }

    @Test
    void decodeRejectsNonCompoundDataFrame() throws IOException {
        Document document = this.codec.encode(SnapshotFixtures.snapshot());
        document.put("data", new Binary(new BinarySnapshotCodec(CompressorRegistry.NONE).frame(NBT.createInt(3))));

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document)).reason());
    }

    @Test
    void decodeRejectsMissingFormat() {
        Document document = new Document().append("player", SnapshotFixtures.PLAYER);

        DecodedSnapshot decoded = this.codec.decode(document);

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void decodeRejectsFutureFormat() {
        Document document = new Document().append("format", 99).append("player", SnapshotFixtures.PLAYER);

        DecodedSnapshot decoded = this.codec.decode(document);

        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void decodeRejectsMissingPlayer() {
        Document document = new Document().append("format", 1);

        DecodedSnapshot decoded = this.codec.decode(document);

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void binaryFieldWithBadMagicReportsBadMagic() throws IOException {
        Document document = this.codec.encode(SnapshotFixtures.snapshot());
        document.put("data", new Binary(new byte[]{99, 1, 2, 3}));

        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document));

        assertEquals(InvalidReason.BAD_MAGIC, invalid.reason());
    }

    @Test
    void binaryFieldWithUnknownCompressionReportsUnsupportedCompression() throws IOException {
        Document document = this.codec.encode(SnapshotFixtures.snapshot());
        document.put("data", new Binary(new byte[]{'S', 'S', 1, 9}));

        DecodedSnapshot decoded = this.codec.decode(document);

        assertEquals(InvalidReason.UNSUPPORTED_COMPRESSION, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void binaryFieldWithGarbageBodyReportsCorrupted() throws IOException {
        // 帧头合法但 NBT 体是垃圾字节
        Document document = this.codec.encode(SnapshotFixtures.snapshot());
        document.put("data", new Binary(new byte[]{'S', 'S', 1, 0, 11, 22}));

        DecodedSnapshot decoded = this.codec.decode(document);

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void decodeRejectsLegacyMixedDocumentData() throws IOException {
        Document document = this.codec.encode(SnapshotFixtures.snapshot());
        document.put("data", new Document()
                .append(SnapshotFixtures.INVENTORY.asString(), new Binary(new byte[]{'S', 'S', 1, 0}))
                .append(SnapshotFixtures.HEALTH.asString(), new Document("value", 19.5)));

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document)).reason());
    }

    @Test
    void uuidAndArraysRoundTripWithoutReservedMarkerKeys() throws IOException {
        UUID owner = UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00");
        CompoundTag structured = NBT.createCompound();
        structured.putUUID("owner", owner);
        structured.putLongArray("values", new long[]{Long.MIN_VALUE, Long.MAX_VALUE});
        structured.putString("__i32a", "ordinary field");
        structured.putString("__i64a", "ordinary field");
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of(SnapshotFixtures.HEALTH, structured));

        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();

        CompoundTag health = assertInstanceOf(CompoundTag.class, restored.data(SnapshotFixtures.HEALTH));
        assertEquals(owner, health.getUUID("owner"));
        assertEquals(snapshot, restored);
    }

    @Test
    void documentSurvivesRealBsonCodecRoundTrip() throws IOException {
        // 经真实 BSON 编解码往返, 覆盖 UUID 的 UuidRepresentation.STANDARD 契约
        Snapshot snapshot = SnapshotFixtures.snapshot();
        Document document = this.codec.encode(snapshot);
        CodecRegistry registry = CodecRegistries.withUuidRepresentation(MongoClientSettings.getDefaultCodecRegistry(), UuidRepresentation.STANDARD);
        BsonDocument bson = document.toBsonDocument(BsonDocument.class, registry);
        Document decodedDocument = registry.get(Document.class).decode(new BsonDocumentReader(bson), DecoderContext.builder().build());

        DecodedSnapshot decoded = this.codec.decode(decodedDocument);

        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot());
    }

    @Test
    void dataFrameUsesItsOwnCompressionHeader() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();
        Document document = this.codec.encode(snapshot);
        DocumentSnapshotCodec plainCodec = new DocumentSnapshotCodec(new BinarySnapshotCodec(CompressorRegistry.NONE));

        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, plainCodec.decode(document)).snapshot();

        assertEquals(snapshot, restored);
    }

    @Test
    void dataAcceptsByteArrayRepresentation() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();
        Document document = this.codec.encode(snapshot);
        document.put("data", document.get("data", Binary.class).getData());

        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(document)).snapshot());
    }

    @Test
    void storedSnapshotCanBeDumpedAndReadInBothFormats() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();
        Snapshot stored = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();
        JsonSnapshotCodec json = new JsonSnapshotCodec();
        BinarySnapshotCodec binary = new BinarySnapshotCodec(CompressorRegistry.DEFLATE);

        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, json.decode(json.encode(stored))).snapshot());
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, binary.decode(binary.encode(stored))).snapshot());
    }
}
