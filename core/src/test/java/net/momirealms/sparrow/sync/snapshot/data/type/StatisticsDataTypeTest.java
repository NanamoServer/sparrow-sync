package net.momirealms.sparrow.sync.snapshot.data.type;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.stats.ServerStatsCounter;
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
import org.spigotmc.SpigotConfig;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void nativeJsonUsesVanillaGroupedShape() throws Exception {
        Statistics value = new Statistics(
                new Stat<?>[]{Stats.CUSTOM.get(Stats.PLAY_TIME), Stats.BLOCK_MINED.get(Blocks.STONE)},
                new int[]{1200, 64}
        );

        JsonObject root = JsonParser.parseString(new String(encodeNativeJson(value), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<Stat<?>, Integer> decoded = vanillaStatsCodec().parse(JsonOps.INSTANCE, root.get("stats")).getOrThrow();

        assertEquals(1200, root.getAsJsonObject("stats").getAsJsonObject("minecraft:custom").get("minecraft:play_time").getAsInt());
        assertEquals(64, root.getAsJsonObject("stats").getAsJsonObject("minecraft:mined").get("minecraft:stone").getAsInt());
        assertEquals(1200, decoded.get(Stats.CUSTOM.get(Stats.PLAY_TIME)));
        assertEquals(64, decoded.get(Stats.BLOCK_MINED.get(Blocks.STONE)));
        assertTrue(root.has("DataVersion"));
    }

    @Test
    void emptyNativeJsonIsAcceptedByVanillaStatsCodec() throws Exception {
        JsonObject root = JsonParser.parseString(new String(encodeNativeJson(new Statistics(new Stat<?>[0], new int[0])), StandardCharsets.UTF_8)).getAsJsonObject();

        assertTrue(vanillaStatsCodec().parse(JsonOps.INSTANCE, root.get("stats")).getOrThrow().isEmpty());
        assertTrue(root.has("DataVersion"));
    }

    @Test
    void nativeFallbackReplacesExtraValuesWhenStatSavingIsDisabled() throws Exception {
        Stat<?> playTime = Stats.CUSTOM.get(Stats.PLAY_TIME);
        Stat<?> stone = Stats.BLOCK_MINED.get(Blocks.STONE);
        Stat<?> pickaxe = Stats.ITEM_USED.get(Items.DIAMOND_PICKAXE);
        Statistics value = new Statistics(new Stat<?>[]{playTime, stone}, new int[]{1200, 64});
        Object2IntMap<Stat<?>> current = new Object2IntOpenHashMap<>();
        Map<Object, Integer> forced = forcedStats();
        Map<Object, Integer> previous = new HashMap<>(forced);
        boolean disableStatSaving = SpigotConfig.disableStatSaving;
        try {
            SpigotConfig.disableStatSaving = true;
            forced.clear();
            forced.put(Stats.PLAY_TIME, 99);
            current.put(playTime, 99);
            current.put(stone, 64);

            current.put(pickaxe, 1);
            replace(current, value);
            assertEquals(2, current.size());
            assertEquals(99, current.getInt(playTime));
            assertEquals(64, current.getInt(stone));
            assertEquals(0, current.getInt(pickaxe));
        } finally {
            SpigotConfig.disableStatSaving = disableStatSaving;
            forced.clear();
            forced.putAll(previous);
        }
    }

    private static Map<Stat<?>, Integer> values(Statistics statistics) {
        Map<Stat<?>, Integer> values = new HashMap<>();
        for (int i = 0; i < statistics.statistics().length; i++) {
            values.put(statistics.statistics()[i], statistics.amounts()[i]);
        }
        return values;
    }

    private static byte[] encodeNativeJson(Statistics value) throws Exception {
        Method method = StatisticsDataType.class.getDeclaredMethod("encodeNativeJson", Statistics.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, value);
    }

    private static void replace(Object2IntMap<Stat<?>> current, Statistics value) throws Exception {
        Method method = StatisticsDataType.class.getDeclaredMethod("replace", Object2IntMap.class, Statistics.class);
        method.setAccessible(true);
        method.invoke(null, current, value);
    }

    @SuppressWarnings("unchecked")
    private static Codec<Map<Stat<?>, Integer>> vanillaStatsCodec() {
        try {
            Field field = ServerStatsCounter.class.getDeclaredField("STATS_CODEC");
            field.setAccessible(true);
            return (Codec<Map<Stat<?>, Integer>>) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<Object, Integer> forcedStats() {
        return (Map) SpigotConfig.forcedStats;
    }
}
