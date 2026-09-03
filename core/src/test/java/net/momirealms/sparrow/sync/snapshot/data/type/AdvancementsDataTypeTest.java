package net.momirealms.sparrow.sync.snapshot.data.type;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.AdvancementValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.Advancements;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementsDataTypeTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void parallelArrayFormatRoundTripsDetachedCriterionCompletionTimes() throws IOException {
        Instant first = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        Instant second = first.plusMillis(125);
        Object firstId = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        Object secondId = IdentifierProxy.INSTANCE.tryParse("example:custom");
        Advancements expected = new Advancements(new AdvancementValue[]{
                new AdvancementValue(firstId, new String[]{"tick", "second"}, new Instant[]{first, second}, false),
                new AdvancementValue(secondId, new String[]{"complete"}, new Instant[]{second}, true)
        });
        AdvancementsDataType type = new AdvancementsDataType();

        Tag encoded = type.encode(expected);
        Advancements decoded = type.decode(encoded, 0);

        assertEquals(firstId, decoded.values()[0].id());
        assertArrayEquals(new String[]{"tick", "second"}, decoded.values()[0].criteria());
        assertArrayEquals(new Instant[]{first, second}, decoded.values()[0].obtained());
        assertFalse(decoded.values()[0].done());
        assertEquals(secondId, decoded.values()[1].id());
        assertTrue(decoded.values()[1].done());

        CompoundTag root = assertInstanceOf(CompoundTag.class, encoded);
        ListTag ids = assertInstanceOf(ListTag.class, root.get("ids"));
        ListTag criteria = assertInstanceOf(ListTag.class, root.get("criteria"));
        assertEquals("minecraft:adventure/root", ids.getString(0));
        assertEquals("example:custom", ids.getString(1));
        assertEquals("tick", criteria.getString(0));
        assertEquals("complete", criteria.getString(2));
        assertArrayEquals(new int[]{2, 1}, root.getIntArray("counts"));
        assertArrayEquals(new long[]{first.toEpochMilli(), second.toEpochMilli(), second.toEpochMilli()}, root.getLongArray("obtained"));
        assertArrayEquals(new byte[]{0, 1}, root.getByteArray("done"));
    }

    @Test
    void advancementProxiesBindRequiredMembers() {
        assertNotNull(AdvancementHolderProxy.INSTANCE);
        assertNotNull(AdvancementProgressProxy.INSTANCE);
        assertTrue(AdvancementProgressProxy.INSTANCE.getCriteria(new AdvancementProgress()).isEmpty());
    }

    @Test
    void captureProgressKeepsOnlyObtainedCriteriaAndDefersIdLookup() throws Exception {
        assertNull(captureProgress(new Object(), new AdvancementProgress()));

        Object id = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        AdvancementHolder holder = (AdvancementHolder) AdvancementHolder.class.getDeclaredConstructors()[0].newInstance(id, null);
        AdvancementProgress progress = new AdvancementProgress();
        progress.update(AdvancementRequirements.allOf(List.of("complete", "pending")));
        Instant obtained = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CriterionProgress complete = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(progress).get("complete");
        CriterionProgressProxy.INSTANCE.setObtained(complete, obtained);

        AdvancementValue captured = captureProgress(holder, progress);

        assertNotNull(captured);
        assertEquals(id, captured.id());
        assertArrayEquals(new String[]{"complete"}, captured.criteria());
        assertArrayEquals(new Instant[]{obtained}, captured.obtained());
        assertFalse(captured.done());
    }

    @Test
    void decodeRejectsMisalignedParallelArrays() {
        CompoundTag root = NBT.createCompound();
        ListTag ids = NBT.createList();
        ids.add(NBT.createString("minecraft:adventure/root"));
        ListTag criteria = NBT.createList();
        criteria.add(NBT.createString("tick"));
        root.put("ids", ids);
        root.put("criteria", criteria);
        root.putIntArray("counts", new int[]{2});
        root.putLongArray("obtained", new long[]{Instant.now().toEpochMilli()});
        root.putByteArray("done", new byte[]{0});

        assertThrows(IOException.class, () -> new AdvancementsDataType().decode(root, 0));
    }

    @Test
    void criterionObtainedCanBePatchedDirectly() {
        Instant original = Instant.now().minusSeconds(60);
        Instant replacement = Instant.now();
        CriterionProgress progress = new CriterionProgress(original);

        CriterionProgressProxy.INSTANCE.setObtained(progress, replacement);
        assertEquals(replacement, progress.getObtained());
        CriterionProgressProxy.INSTANCE.setObtained(progress, null);
        assertNull(progress.getObtained());
        assertFalse(progress.isDone());
    }

    @Test
    void nativeJsonUsesVanillaProgressShape() throws Exception {
        Instant obtained = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        Object id = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        Advancements value = new Advancements(new AdvancementValue[]{
                new AdvancementValue(id, new String[]{"tick"}, new Instant[]{obtained}, false)
        });

        JsonObject root = JsonParser.parseString(new String(encodeNativeJson(value), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<Object, AdvancementProgress> decoded = vanillaCodec().parse(JsonOps.INSTANCE, root).getOrThrow();
        AdvancementProgress progress = decoded.get(id);
        CriterionProgress criterion = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(progress).get("tick");

        assertTrue(root.has("DataVersion"));
        assertTrue(root.getAsJsonObject("minecraft:adventure/root").getAsJsonObject("criteria").has("tick"));
        assertFalse(root.getAsJsonObject("minecraft:adventure/root").get("done").getAsBoolean());
        assertEquals(obtained, criterion.getObtained());
    }

    @Test
    void emptyNativeJsonIsAcceptedByVanillaDataFixWrapper() throws Exception {
        JsonObject root = JsonParser.parseString(new String(encodeNativeJson(new Advancements(new AdvancementValue[0])), StandardCharsets.UTF_8)).getAsJsonObject();

        assertTrue(vanillaCodec().parse(JsonOps.INSTANCE, root).getOrThrow().isEmpty());
        assertTrue(root.has("DataVersion"));
    }

    private static AdvancementValue captureProgress(Object advancement, AdvancementProgress progress) throws Exception {
        Method method = AdvancementsDataType.class.getDeclaredMethod("captureProgress", Object.class, AdvancementProgress.class);
        method.setAccessible(true);
        return (AdvancementValue) method.invoke(null, advancement, progress);
    }

    private static byte[] encodeNativeJson(Advancements value) throws Exception {
        Method method = AdvancementsDataType.class.getDeclaredMethod("encodeNativeJson", Advancements.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, value);
    }

    private static Codec<Map<Object, AdvancementProgress>> vanillaCodec() {
        Codec<Map<Object, AdvancementProgress>> codec = Codec.unboundedMap(IdentifierProxy.INSTANCE.getCodec(), AdvancementProgress.CODEC);
        return DataFixTypes.ADVANCEMENTS.wrapCodec(codec, DataFixers.getDataFixer(), 1343);
    }
}
