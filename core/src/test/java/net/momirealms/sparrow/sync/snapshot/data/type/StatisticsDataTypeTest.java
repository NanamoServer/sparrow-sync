package net.momirealms.sparrow.sync.snapshot.data.type;

import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatisticsDataTypeTest {

    @Test
    void codecRoundTripsNamespacedStatistics() {
        StatisticsDataType.Statistics value = new StatisticsDataType.Statistics(
                Map.of(id("minecraft:play_time"), 1200),
                Map.of(id("minecraft:mined"), Map.of(id("minecraft:stone"), 64)),
                Map.of(id("minecraft:used"), Map.of(id("minecraft:diamond_pickaxe"), 12)),
                Map.of(id("minecraft:killed"), Map.of(id("minecraft:zombie"), 5))
        );

        assertEquals(value, roundTrip(value));
    }

    @Test
    void captureOmitsZeroValues() {
        Map<Object, Integer> captured = StatisticsDataType.captureValues(
                List.of("walk", "jump", "sleep"),
                name -> id("test:" + name),
                name -> switch (name) {
                    case "walk" -> 14;
                    case "jump" -> 0;
                    case "sleep" -> 3;
                    default -> throw new IllegalStateException();
                }
        );

        assertEquals(Map.of(
                id("test:walk"), 14,
                id("test:sleep"), 3
        ), captured);
    }

    @Test
    void applyWritesSnapshotAbsencesAsZero() {
        Map<String, Integer> current = new LinkedHashMap<>(Map.of("walk", 2, "jump", 7, "sleep", 3));

        StatisticsDataType.applyComplete(
                List.of("walk", "jump", "sleep"),
                name -> id("test:" + name),
                Map.of(
                        id("test:walk"), 14,
                        id("test:sleep"), 3
                ),
                current::get,
                current::put
        );

        assertEquals(Map.of("walk", 14, "jump", 0, "sleep", 3), current);
    }

    private static Object id(String value) {
        return IdentifierProxy.INSTANCE.tryParse(value);
    }

    private static StatisticsDataType.Statistics roundTrip(StatisticsDataType.Statistics value) {
        return StatisticsDataType.Statistics.CODEC.parse(
                NBTOps.INSTANCE,
                StatisticsDataType.Statistics.CODEC.encodeStart(NBTOps.INSTANCE, value).getOrThrow()
        ).getOrThrow();
    }
}
