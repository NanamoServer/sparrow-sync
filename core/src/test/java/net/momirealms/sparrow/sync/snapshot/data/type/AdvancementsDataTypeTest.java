package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdvancementsDataTypeTest {

    @Test
    void codecRoundTripsCriterionCompletionTime() {
        AdvancementProgress progress = progress("tick", "entered_end");
        progress.grantProgress("tick");
        Instant obtained = progress.getCriterion("tick").getObtained();
        Object id = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        Map<Object, AdvancementProgress> decoded = roundTrip(Map.of(id, progress));

        AdvancementProgress restored = decoded.get(id);
        restored.update(AdvancementRequirements.allOf(List.of("tick", "entered_end")));
        assertEquals(obtained.truncatedTo(ChronoUnit.SECONDS), restored.getCriterion("tick").getObtained());
        assertFalse(restored.getCriterion("entered_end").isDone());
    }

    @Test
    void requirementsUpdateDropsCriteriaMissingFromTargetServer() {
        AdvancementProgress progress = progress("removed");
        progress.grantProgress("removed");
        Object id = IdentifierProxy.INSTANCE.tryParse("custom:old");
        AdvancementProgress restored = roundTrip(Map.of(id, progress)).get(id);

        restored.update(AdvancementRequirements.allOf(List.of("current")));

        assertNull(restored.getCriterion("removed"));
        assertFalse(restored.getCriterion("current").isDone());
    }

    @Test
    void advancementHolderProxyBindsIdMethod() {
        assertNotNull(AdvancementHolderProxy.INSTANCE);
    }

    private static AdvancementProgress progress(String... criteria) {
        AdvancementProgress progress = new AdvancementProgress();
        progress.update(AdvancementRequirements.allOf(List.of(criteria)));
        return progress;
    }

    private static Map<Object, AdvancementProgress> roundTrip(Map<Object, AdvancementProgress> value) {
        return AdvancementsDataType.CODEC.parse(
                NBTOps.INSTANCE,
                AdvancementsDataType.CODEC.encodeStart(NBTOps.INSTANCE, value).getOrThrow()
        ).getOrThrow();
    }
}
