package net.momirealms.sparrow.sync.codec;

import com.mongodb.MongoClientSettings;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.DoubleTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.codec.compressor.Compressors;
import net.momirealms.sparrow.sync.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.types.Binary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentSnapshotCodecTest {
    private final DocumentSnapshotCodec codec = new DocumentSnapshotCodec(SnapshotFixtures.registry(), new BinarySnapshotCodec(Compressors.DEFLATE));

    @Test
    void roundTripPreservesSnapshot() throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        Document document = this.codec.encode(snapshot);
        DecodedSnapshot decoded = this.codec.decode(document);

        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot());
    }

    @Test
    void binaryFormFieldsStoredAsBinary() throws IOException {
        Document document = this.codec.encode(SnapshotFixtures.snapshot());

        Document data = document.get("data", Document.class);
        // BINARY 注册类型与未知二进制字段落为 Binary, STRUCTURED 类型落为可读文档
        assertInstanceOf(Binary.class, data.get(SnapshotFixtures.INVENTORY.asString()));
        assertInstanceOf(Binary.class, data.get(SnapshotFixtures.UNKNOWN_BLOB.asString()));
        assertInstanceOf(Document.class, data.get(SnapshotFixtures.HEALTH.asString()));
    }

    @Test
    void structuredFieldsAreQueryableInDocument() throws IOException {
        Document document = this.codec.encode(SnapshotFixtures.snapshot());

        Document health = document.get("data", Document.class).get(SnapshotFixtures.HEALTH.asString(), Document.class);

        assertEquals(19.5, health.getDouble("value"));
        assertEquals(18, health.getInteger("food"));
        assertEquals("SURVIVAL", health.getString("mode"));
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
    void narrowNumericTypesWidenButKeepValue() throws IOException {
        // 结构化字段里的 byte 与 float 经 BSON 往返后升宽为 int 与 double
        CompoundTag structured = NBT.createCompound();
        structured.putBoolean("flying", true);
        structured.putFloat("exhaustion", 1.5f);
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of(SnapshotFixtures.HEALTH, structured));

        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();

        CompoundTag health = (CompoundTag) restored.data(SnapshotFixtures.HEALTH);
        assertInstanceOf(IntTag.class, health.get("flying"));
        assertInstanceOf(DoubleTag.class, health.get("exhaustion"));
        // NumericTag 宽容取值下语义不变
        assertTrue(health.getBoolean("flying"));
        assertEquals(1.5f, health.getFloat("exhaustion"));
    }

    @Test
    void nullDataFieldIsSkipped() throws IOException {
        // 人工写入的 null 字段视为缺失, 不落为 EndTag 拖垮后续二进制编码
        Document document = this.codec.encode(SnapshotFixtures.snapshot());
        document.get("data", Document.class).put("sparrow_sync:broken", null);

        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(document)).snapshot();

        assertTrue(restored.data().keySet().stream().noneMatch(key -> key.value().equals("broken")));
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
        document.get("data", Document.class).put(SnapshotFixtures.INVENTORY.asString(), new Binary(new byte[]{99, 1, 2, 3}));

        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document));

        // 帧头错误按精确原因上报, detail 指出出错字段
        assertEquals(InvalidReason.BAD_MAGIC, invalid.reason());
        assertTrue(invalid.detail().contains(SnapshotFixtures.INVENTORY.asString()));
    }

    @Test
    void binaryFieldWithUnknownCompressionReportsUnsupportedCompression() throws IOException {
        Document document = this.codec.encode(SnapshotFixtures.snapshot());
        document.get("data", Document.class).put(SnapshotFixtures.INVENTORY.asString(), new Binary(new byte[]{'S', 'S', 1, 9}));

        DecodedSnapshot decoded = this.codec.decode(document);

        assertEquals(InvalidReason.UNSUPPORTED_COMPRESSION, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void binaryFieldWithGarbageBodyReportsCorrupted() throws IOException {
        // 帧头合法但 NBT 体是垃圾字节
        Document document = this.codec.encode(SnapshotFixtures.snapshot());
        document.get("data", Document.class).put(SnapshotFixtures.INVENTORY.asString(), new Binary(new byte[]{'S', 'S', 1, 0, 11, 22}));

        DecodedSnapshot decoded = this.codec.decode(document);

        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void storageFormDriftFallsBackToValueType() throws IOException {
        // 模拟写方把 BINARY 注册的字段按结构化落盘, 读方以值类型为准还原而不是判损坏
        Document document = this.codec.encode(SnapshotFixtures.snapshot());
        document.get("data", Document.class).put(SnapshotFixtures.INVENTORY.asString(), new Document("heldSlot", 3));

        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(document)).snapshot();

        CompoundTag inventory = assertInstanceOf(CompoundTag.class, restored.data(SnapshotFixtures.INVENTORY));
        assertEquals(3, inventory.getInt("heldSlot"));
    }

    @Test
    void structuredUuidRoundTripsThroughMarkerDocument() throws IOException {
        // NBT 中 UUID 即 IntArrayTag, 经 __i32a 标记文档无损往返
        UUID owner = UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00");
        CompoundTag structured = NBT.createCompound();
        structured.putUUID("owner", owner);
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of(SnapshotFixtures.HEALTH, structured));

        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();

        CompoundTag health = assertInstanceOf(CompoundTag.class, restored.data(SnapshotFixtures.HEALTH));
        assertEquals(owner, health.getUUID("owner"));
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
    void binaryFieldBytesRoundTripThroughOwnHeader() throws IOException {
        // BINARY 字段字节自带压缩标识, 与实例配置无关
        Snapshot snapshot = SnapshotFixtures.snapshot();
        Document document = this.codec.encode(snapshot);
        DocumentSnapshotCodec plainCodec = new DocumentSnapshotCodec(SnapshotFixtures.registry(), new BinarySnapshotCodec(Compressors.NONE));

        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, plainCodec.decode(document)).snapshot();

        Tag inventory = restored.data(SnapshotFixtures.INVENTORY);
        assertEquals(snapshot.data(SnapshotFixtures.INVENTORY), inventory);
    }
}
