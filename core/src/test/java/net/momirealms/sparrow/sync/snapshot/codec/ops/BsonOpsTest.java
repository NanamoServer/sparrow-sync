package net.momirealms.sparrow.sync.snapshot.codec.ops;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.nbt.ByteArrayTag;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.DoubleTag;
import net.momirealms.sparrow.nbt.IntArrayTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType.Experience;
import net.momirealms.sparrow.sync.snapshot.codec.ops.BsonOps;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BsonOpsTest {

    @Test
    void nbtCompoundConvertsToBsonAndBack() {
        CompoundTag source = NBT.createCompound();
        source.putString("name", "sparrow");
        source.putInt("count", 42);
        source.putLong("time", 1_756_300_000_000L);
        source.putDouble("ratio", 0.5);
        source.putFloat("progress", 1.5f);
        source.putByte("flag", (byte) 3);
        source.putShort("slot", (short) 7);
        source.putBoolean("enabled", true);
        source.putByteArray("blob", new byte[]{1, 2, 3});
        source.putIntArray("ids", new int[]{10, 20, 30});
        source.putLongArray("stamps", new long[]{100L, 200L});
        CompoundTag nested = NBT.createCompound();
        nested.putString("inner", "value");
        source.put("nested", nested);

        Object bson = NBTOps.INSTANCE.convertTo(BsonOps.INSTANCE, source);

        Document document = assertInstanceOf(Document.class, bson);
        assertEquals("sparrow", document.getString("name"));
        assertEquals(42, document.getInteger("count"));
        assertEquals(1.5, document.getDouble("progress"));
        assertEquals(3, document.getInteger("flag"));
        assertInstanceOf(Binary.class, document.get("blob"));
        Document ids = assertInstanceOf(Document.class, document.get("ids"));
        assertEquals(List.of(10, 20, 30), ids.get(BsonOps.INT_ARRAY_MARKER));

        CompoundTag restored = assertInstanceOf(CompoundTag.class, BsonOps.INSTANCE.convertTo(NBTOps.INSTANCE, bson));
        assertEquals("sparrow", restored.getString("name"));
        assertArrayEquals(new int[]{10, 20, 30}, assertInstanceOf(IntArrayTag.class, restored.get("ids")).value());
        assertArrayEquals(new long[]{100L, 200L}, restored.getLongArray("stamps"));
        assertArrayEquals(new byte[]{1, 2, 3}, assertInstanceOf(ByteArrayTag.class, restored.get("blob")).value());
        assertInstanceOf(IntTag.class, restored.get("flag"));
        assertInstanceOf(DoubleTag.class, restored.get("progress"));
        assertTrue(restored.getBoolean("enabled"));
        assertEquals(1.5f, restored.getFloat("progress"));
        assertEquals("value", restored.getCompound("nested").getString("inner"));
    }

    @Test
    void uuidIntArrayRoundTripsThroughMarkerDocument() {
        UUID id = UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00");
        CompoundTag source = NBT.createCompound();
        source.putUUID("owner", id);

        Object bson = NBTOps.INSTANCE.convertTo(BsonOps.INSTANCE, source);
        CompoundTag restored = assertInstanceOf(CompoundTag.class, BsonOps.INSTANCE.convertTo(NBTOps.INSTANCE, bson));

        assertEquals(id, restored.getUUID("owner"));
    }

    @Test
    void codecEncodesDirectlyToQueryableDocument() {
        Experience value = new Experience(1024, 30, 0.45f);

        Object encoded = Experience.CODEC.encodeStart(BsonOps.INSTANCE, value).getOrThrow();

        Document document = assertInstanceOf(Document.class, encoded);
        assertEquals(1024, document.getInteger("total"));
        assertEquals(30, document.getInteger("level"));
        assertEquals(0.45, document.getDouble("progress"), 0.0001);
        assertEquals(value, Experience.CODEC.parse(BsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void booleanCodecAcceptsNativeAndNumericForms() {
        assertEquals(true, Codec.BOOL.parse(BsonOps.INSTANCE, true).getOrThrow());
        assertEquals(true, Codec.BOOL.parse(BsonOps.INSTANCE, 1).getOrThrow());
        assertEquals(false, Codec.BOOL.parse(BsonOps.INSTANCE, 0).getOrThrow());
    }

    @Test
    void markerDocumentIsExcludedFromMapSemantics() {
        Document marker = new Document(BsonOps.INT_ARRAY_MARKER, List.of(1, 2));
        Document plain = new Document("field", 1);

        assertTrue(BsonOps.INSTANCE.getMap(marker).error().isPresent());
        assertTrue(BsonOps.INSTANCE.getMap(plain).result().isPresent());
        assertTrue(BsonOps.INSTANCE.getMapValues(marker).error().isPresent());
    }

    @Test
    void malformedMarkerDocumentIsReadAsPlainMap() {
        Document malformed = new Document(BsonOps.INT_ARRAY_MARKER, List.of("a", "b"));

        CompoundTag restored = assertInstanceOf(CompoundTag.class, BsonOps.INSTANCE.convertTo(NBTOps.INSTANCE, malformed));

        assertEquals(2, restored.getList(BsonOps.INT_ARRAY_MARKER).size());
    }

    @Test
    void nullDocumentValueIsDroppedDuringConvert() {
        Document document = new Document("kept", 1);
        document.put("missing", null);

        CompoundTag restored = assertInstanceOf(CompoundTag.class, BsonOps.INSTANCE.convertTo(NBTOps.INSTANCE, document));

        assertEquals(1, restored.getInt("kept"));
        assertTrue(!restored.containsKey("missing"));
    }

    @Test
    void listCodecEncodesFromEmptyPrefix() {
        Object encoded = Codec.INT.listOf().encodeStart(BsonOps.INSTANCE, List.of(1, 2, 3)).getOrThrow();

        assertEquals(List.of(1, 2, 3), encoded);
    }

    @Test
    void longValueSurvivesNumericFallback() {
        Object value = BsonOps.INSTANCE.createNumeric(1_756_300_000_000L);

        assertInstanceOf(Long.class, value);
        assertEquals(1_756_300_000_000L, value);
    }

    @Test
    void tagListOfCompoundsRoundTrips() {
        CompoundTag item = NBT.createCompound();
        item.putInt("slot", 3);
        item.putString("id", "minecraft:stone");
        Tag list = NBT.createList(List.of(item));
        CompoundTag source = NBT.createCompound();
        source.put("items", list);

        Object bson = NBTOps.INSTANCE.convertTo(BsonOps.INSTANCE, source);
        CompoundTag restored = assertInstanceOf(CompoundTag.class, BsonOps.INSTANCE.convertTo(NBTOps.INSTANCE, bson));

        assertEquals(1, restored.getList("items").size());
        assertEquals(3, restored.getList("items").getCompound(0).getInt("slot"));
    }
}
