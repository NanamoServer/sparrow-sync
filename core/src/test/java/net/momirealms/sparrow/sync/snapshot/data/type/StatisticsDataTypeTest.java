package net.momirealms.sparrow.sync.snapshot.data.type;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.proxy.minecraft.stats.StatsCounterProxy;
import net.momirealms.sparrow.sync.snapshot.data.type.StatisticsDataType.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatisticsDataTypeTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void parallelArrayFormatRoundTripsSparseStatistics() throws IOException {
        Stat<?> playTime = Stats.CUSTOM.get(Stats.PLAY_TIME);
        Stat<?> stone = Stats.BLOCK_MINED.get(Blocks.STONE);
        Stat<?> pickaxe = Stats.ITEM_USED.get(Items.DIAMOND_PICKAXE);
        Stat<?> zombie = Stats.ENTITY_KILLED.get(EntityType.ZOMBIE);
        Statistics expected = new Statistics(
                new Stat<?>[]{playTime, stone, pickaxe, zombie},
                new int[]{1200, 64, 12, 5}
        );
        StatisticsDataType type = new StatisticsDataType();

        Tag encoded = type.encode(expected);
        Statistics decoded = type.decode(encoded, 0);

        assertEquals(values(expected), values(decoded));
        CompoundTag root = (CompoundTag) encoded;
        ListTag types = root.getList("types");
        ListTag statisticValues = root.getList("values");
        assertEquals("minecraft:custom", types.getString(0));
        assertEquals("minecraft:mined", types.getString(1));
        assertEquals("minecraft:used", types.getString(2));
        assertEquals("minecraft:killed", types.getString(3));
        assertEquals("minecraft:play_time", statisticValues.getString(0));
        assertEquals("minecraft:stone", statisticValues.getString(1));
        assertEquals("minecraft:diamond_pickaxe", statisticValues.getString(2));
        assertEquals("minecraft:zombie", statisticValues.getString(3));
        assertArrayEquals(new int[]{1200, 64, 12, 5}, root.getIntArray("amounts"));
    }

    @Test
    void decodeRequiresParallelArrayFields() {
        CompoundTag incompatible = NBT.createCompound();
        incompatible.put("untyped", NBT.createCompound());
        incompatible.put("blocks", NBT.createCompound());
        incompatible.put("items", NBT.createCompound());
        incompatible.put("entities", NBT.createCompound());

        assertThrows(IOException.class, () -> new StatisticsDataType().decode(incompatible, 0));
    }

    @Test
    void statsCounterProxyBindsSparseMap() {
        assertNotNull(StatsCounterProxy.INSTANCE);
        assertInstanceOf(Object2IntMap.class, StatsCounterProxy.INSTANCE.getStats(new StatsCounter()));
    }

    private static Map<Stat<?>, Integer> values(Statistics statistics) {
        Map<Stat<?>, Integer> values = new HashMap<>();
        for (int i = 0; i < statistics.statistics().length; i++) {
            values.put(statistics.statistics()[i], statistics.amounts()[i]);
        }
        return values;
    }
}
