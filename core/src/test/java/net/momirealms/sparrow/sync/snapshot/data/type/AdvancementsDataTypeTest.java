package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.advancements.AdvancementProgress;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.AdvancementValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.Advancements;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementsDataTypeTest {

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
}
