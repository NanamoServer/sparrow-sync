package net.momirealms.sparrow.sync.snapshot.data.type;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.AdvancementValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.Advancements;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementsDataTypeTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void codecRoundTripsDetachedCriterionCompletionTime() {
        Instant obtained = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        Object id = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        Advancements expected = new Advancements(new AdvancementValue[]{
                new AdvancementValue(id, new String[]{"tick"}, new Instant[]{obtained}, false)
        });

        Tag encoded = AdvancementsDataType.CODEC.encodeStart(NBTOps.INSTANCE, expected).getOrThrow();
        Advancements decoded = AdvancementsDataType.CODEC.parse(NBTOps.INSTANCE, encoded).getOrThrow();

        assertEquals(id, decoded.values()[0].id());
        assertArrayEquals(new String[]{"tick"}, decoded.values()[0].criteria());
        assertArrayEquals(new Instant[]{obtained}, decoded.values()[0].obtained());
        CompoundTag progress = (CompoundTag) ((CompoundTag) encoded).get(id.toString());
        assertFalse(progress.getBoolean("done"));
    }

    @Test
    void advancementProxiesBindRequiredMembers() {
        assertNotNull(AdvancementHolderProxy.INSTANCE);
        assertNotNull(AdvancementProgressProxy.INSTANCE);
        assertTrue(AdvancementProgressProxy.INSTANCE.getCriteria(new AdvancementProgress()).isEmpty());
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
        Codec<Advancements> vanillaCodec = DataFixTypes.ADVANCEMENTS.wrapCodec(AdvancementsDataType.CODEC, DataFixers.getDataFixer(), 1343);
        Advancements decoded = vanillaCodec.parse(JsonOps.INSTANCE, root).getOrThrow();

        assertTrue(root.has("DataVersion"));
        assertTrue(root.getAsJsonObject("minecraft:adventure/root").getAsJsonObject("criteria").has("tick"));
        assertFalse(root.getAsJsonObject("minecraft:adventure/root").get("done").getAsBoolean());
        assertArrayEquals(new Instant[]{obtained}, decoded.values()[0].obtained());
    }

    @Test
    void emptyNativeJsonIsAcceptedByVanillaDataFixWrapper() throws Exception {
        JsonObject root = JsonParser.parseString(new String(encodeNativeJson(new Advancements(new AdvancementValue[0])), StandardCharsets.UTF_8)).getAsJsonObject();
        Codec<Advancements> vanillaCodec = DataFixTypes.ADVANCEMENTS.wrapCodec(AdvancementsDataType.CODEC, DataFixers.getDataFixer(), 1343);

        assertEquals(0, vanillaCodec.parse(JsonOps.INSTANCE, root).getOrThrow().values().length);
        assertTrue(root.has("DataVersion"));
    }

    private static byte[] encodeNativeJson(Advancements value) throws Exception {
        Method method = AdvancementsDataType.class.getDeclaredMethod("encodeNativeJson", Advancements.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, value);
    }
}
