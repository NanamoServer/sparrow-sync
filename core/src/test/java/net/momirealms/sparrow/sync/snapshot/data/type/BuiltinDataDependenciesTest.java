package net.momirealms.sparrow.sync.snapshot.data.type;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuiltinDataDependenciesTest {

    @Test
    void dependentTypesDeclareTheM16ApplicationOrder() {
        assertEquals(Set.of(AdvancementsDataType.ADVANCEMENTS), new ExperienceDataType().dependencies());
        assertEquals(Set.of(AttributesDataType.ATTRIBUTES), new HealthDataType().dependencies());
        assertEquals(Set.of(AttributesDataType.ATTRIBUTES), new HungerDataType().dependencies());
        assertEquals(Set.of(GameModeDataType.GAME_MODE), new FlightStatusDataType().dependencies());
        assertEquals(
                Set.of(InventoryDataType.INVENTORY, PotionEffectsDataType.POTION_EFFECTS),
                new AttributesDataType().dependencies()
        );
        assertEquals(
                Set.of(FlightStatusDataType.FLIGHT_STATUS, PotionEffectsDataType.POTION_EFFECTS),
                new LocationDataType().dependencies()
        );
    }
}
