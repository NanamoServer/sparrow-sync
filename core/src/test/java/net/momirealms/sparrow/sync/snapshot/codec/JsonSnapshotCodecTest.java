package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.model.BlockMeta;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotBlock;
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

    /**
     * 未知类型直到 JSON 导出才读取 Tag, 类型自有版本字段随 SNBT 往返.
     *
     * @throws Exception 当测试快照编解码或解块计数读取失败时
     */
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

    /**
     * JSON 中每个类型独立携带 meta 和 data, 未知处理开关与数据内部的版本一起往返.
     *
     * @throws Exception 当快照编解码或计数读取失败时
     */
    @Test
    void eachJsonBlockCarriesMetadataAndSnbtTogether() throws Exception {
        DataKey key = DataKey.of("external", "state");
        CompoundTag tag = NBT.createCompound();
        tag.putInt("__v", 9);
        tag.putString("value", "preserved");
        Snapshot source = new Snapshot(SnapshotFixtures.meta(), new EagerSnapshotData(Map.of(key, new SnapshotBlock(BlockMeta.DISCARD_UNKNOWN, tag))));
        Document json = Document.parse(this.codec.encode(source));
        Document block = json.get("data", Document.class).get(key.asString(), Document.class);
        assertEquals(2, block.size());
        assertEquals(1, block.get("meta", Document.class).getInteger("version"));
        assertFalse(block.get("meta", Document.class).getBoolean("keepUnknown"));
        assertTrue(block.get("data") instanceof String);
        assertFalse(json.containsKey("dataPolicies"));
        Snapshot imported = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(json.toJson())).snapshot();
        assertEquals(source, imported);
        assertFalse(imported.content().select(key::equals).meta(key).keepUnknown());
        BinarySnapshotCodec binary = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0);
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, binary.decode(binary.encode(imported))).snapshot();
        assertFalse(restored.content().meta(key).keepUnknown());
        assertEquals(0, SnapshotFixtures.decodedBlockCount(restored));
        assertEquals(tag, restored.data(key));
    }

    /** JSON 严格区分元信息版本和布尔值, 并拒绝缺少完整块结构的旧字符串形态. */
    @Test
    void malformedBlockMetadataAndLegacyJsonShapeAreRejected() {
        Document document = Document.parse(this.codec.encode(SnapshotFixtures.snapshot()));
        Document data = document.get("data", Document.class);
        String key = data.keySet().iterator().next();
        Document block = data.get(key, Document.class);
        Document meta = block.get("meta", Document.class);
        meta.put("keepUnknown", 0);
        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document.toJson()));
        assertEquals(InvalidReason.CORRUPTED, invalid.reason());
        assertTrue(invalid.detail().contains(key));
        meta.put("keepUnknown", true);
        meta.put("version", 2);
        invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document.toJson()));
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, invalid.reason());
        assertTrue(invalid.detail().contains("block metadata version"));
        data.put(key, block.getString("data"));
        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(document.toJson())).reason());
    }

    @Test
    void roundTripKeepsMetaAndData() {
        Snapshot snapshot = SnapshotFixtures.snapshot();

        DecodedSnapshot decoded = codec.decode(codec.encode(snapshot));

        // SNBT 往返对 Tag 类型保真, 数组与数值后缀原样回来
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

    /** 版本检查先于字段读取, 零版本与旧开发期的 format 2 都明确拒绝. */
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
        // 手改坏某个类型的 SNBT 时要点名是哪个 key, 这里的 compound 少了闭合花括号
        Document document = Document.parse(this.codec.encode(SnapshotFixtures.snapshot()));
        document.get("data", Document.class).get("other:doc", Document.class).put("data", "{origin:1");
        String json = document.toJson();

        DecodedSnapshot decoded = codec.decode(json);

        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, decoded);
        assertEquals(InvalidReason.CORRUPTED, invalid.reason());
        assertTrue(invalid.detail().contains("other:doc"), invalid.detail());
    }
}
