package net.momirealms.sparrow.sync.snapshot.data.type;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.proxy.minecraft.stats.ServerStatsCounterProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.stats.StatsCounterProxy;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.type.StatisticsDataType.Statistics;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spigotmc.SpigotConfig;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsDataTypeTest {
    private Map<Object, Integer> previousForced;
    private boolean previousDisableSaving;

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void isolateSpigotStatisticsOptions() {
        this.previousForced = new HashMap<>(forcedStats());
        this.previousDisableSaving = SpigotConfig.disableStatSaving;
        forcedStats().clear();
        SpigotConfig.disableStatSaving = false;
    }

    @AfterEach
    void restoreSpigotStatisticsOptions() {
        forcedStats().clear();
        forcedStats().putAll(this.previousForced);
        SpigotConfig.disableStatSaving = this.previousDisableSaving;
    }

    @Test
    void captureOmitsZeroValuesAndDetachesCountsFromThePlayer() throws Exception {
        PlayerFixture fixture = playerFixture();
        Stat<?> playTime = Stats.CUSTOM.get(Stats.PLAY_TIME);
        Stat<?> stone = Stats.BLOCK_MINED.get(Blocks.STONE);
        Stat<?> pickaxe = Stats.ITEM_USED.get(Items.DIAMOND_PICKAXE);
        fixture.current.put(playTime, 1200);
        fixture.current.put(stone, 64);
        fixture.current.put(pickaxe, 0);
        StatisticsDataType type = new StatisticsDataType();

        Statistics captured = type.capture(fixture.player, CaptureMode.SYNC);
        fixture.current.put(playTime, 2400);
        fixture.current.removeInt(stone);
        fixture.current.put(pickaxe, 3);

        assertEquals(Map.of(playTime, 1200, stone, 64), values(captured));
        assertEquals(Map.of(playTime, 2400, pickaxe, 3), values(type.capture(fixture.player, CaptureMode.SYNC)));
    }

    @Test
    void asyncCaptureSharesTheVanillaWritersMonitor() throws Exception {
        PlayerFixture fixture = playerFixture();
        StatisticsDataType type = new StatisticsDataType();
        Stat<?> first = Stats.ITEM_USED.get(Items.DIAMOND);
        Stat<?> second = Stats.ITEM_USED.get(Items.GOLD_INGOT);
        CountDownLatch start = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var captures = worker.submit(() -> {
                assertTrue(start.await(2, TimeUnit.SECONDS));
                for (int i = 0; i < 1000; i++) {
                    Map<Stat<?>, Integer> captured = values(type.capture(fixture.player, CaptureMode.ASYNC));
                    assertEquals(captured.get(first), captured.get(second));
                }
                return null;
            });
            start.countDown();
            for (int i = 1; i <= 1000; i++) {
                // 原版写入走同一同步 Map. 读端应在这组更新之前或之后取得一致的键值数组
                synchronized (fixture.current) {
                    fixture.counter.setValue(fixture.player.getHandle(), first, i);
                    fixture.counter.setValue(fixture.player.getHandle(), second, i);
                }
            }
            captures.get(3, TimeUnit.SECONDS);
        }
        assertEquals(Map.of(first, 1000, second, 1000), values(type.capture(fixture.player, CaptureMode.OFFLINE)));
    }

    @Test
    void captureHandlesEmptyAndAllZeroCounters() throws Exception {
        PlayerFixture fixture = playerFixture();
        StatisticsDataType type = new StatisticsDataType();
        assertTrue(values(type.capture(fixture.player, CaptureMode.SYNC)).isEmpty());

        fixture.current.put(Stats.CUSTOM.get(Stats.PLAY_TIME), 0);
        fixture.current.put(Stats.BLOCK_MINED.get(Blocks.STONE), 0);
        Statistics captured = type.capture(fixture.player, CaptureMode.SYNC);

        assertEquals(0, captured.statistics().length);
        assertEquals(0, captured.amounts().length);
        assertEquals(2, fixture.current.size());
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
    void serverStatsCounterProxyBindsTheOriginalDirtySet() throws Exception {
        PlayerFixture fixture = playerFixture();

        assertSame(fixture.dirty, ServerStatsCounterProxy.INSTANCE.getDirty(fixture.counter));
        fixture.counter.setValue(fixture.player.getHandle(), Stats.CUSTOM.get(Stats.PLAY_TIME), 1);
        assertTrue(fixture.dirty.contains(Stats.CUSTOM.get(Stats.PLAY_TIME)));
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
        PlayerFixture fixture = playerFixture();
        Stat<?> playTime = Stats.CUSTOM.get(Stats.PLAY_TIME);
        Stat<?> stone = Stats.BLOCK_MINED.get(Blocks.STONE);
        Stat<?> pickaxe = Stats.ITEM_USED.get(Items.DIAMOND_PICKAXE);
        Statistics value = new Statistics(new Stat<?>[]{playTime, stone}, new int[]{1200, 64});
        SpigotConfig.disableStatSaving = true;
        forcedStats().put(Stats.PLAY_TIME, 99);
        forcedStats().put(Stats.JUMP, 0);
        fixture.current.put(playTime, 99);
        fixture.current.put(stone, 64);
        fixture.current.put(pickaxe, 1);

        new StatisticsDataType().apply(fixture.player, value);

        assertEquals(Map.of(playTime, 99, stone, 64, Stats.CUSTOM.get(Stats.JUMP), 0), fixture.current);
        assertFalse(fixture.current.containsKey(pickaxe));
        assertEquals(Map.of(playTime, 99, stone, 64, pickaxe, 0, Stats.CUSTOM.get(Stats.JUMP), 0), fixture.connection.packets.getFirst().stats());
        assertTrue(fixture.dirty.isEmpty());
    }

    @Test
    void applySendsZerosForRemovedAndAlreadyDirtyKeys() throws Exception {
        PlayerFixture fixture = playerFixture();
        Stat<?> playTime = Stats.CUSTOM.get(Stats.PLAY_TIME);
        Stat<?> stone = Stats.BLOCK_MINED.get(Blocks.STONE);
        Stat<?> pickaxe = Stats.ITEM_USED.get(Items.DIAMOND_PICKAXE);
        fixture.current.put(playTime, 20);
        fixture.current.put(stone, 64);
        fixture.dirty.add(playTime);
        fixture.dirty.add(pickaxe);

        new StatisticsDataType().apply(fixture.player, new Statistics(new Stat<?>[]{playTime}, new int[]{1200}));

        assertSame(fixture.current, StatsCounterProxy.INSTANCE.getStats(fixture.counter));
        assertSame(fixture.dirty, ServerStatsCounterProxy.INSTANCE.getDirty(fixture.counter));
        assertEquals(Map.of(playTime, 1200), fixture.current);
        assertEquals(1, fixture.connection.packets.size());
        assertEquals(Map.of(playTime, 1200, stone, 0, pickaxe, 0), fixture.connection.packets.getFirst().stats());
        assertTrue(fixture.dirty.isEmpty());
    }

    @Test
    void sameStateApplySendsFullValuesAndPacketsStayDetached() throws Exception {
        PlayerFixture fixture = playerFixture();
        Stat<?> playTime = Stats.CUSTOM.get(Stats.PLAY_TIME);
        fixture.current.put(playTime, 1200);
        StatisticsDataType type = new StatisticsDataType();
        Statistics value = new Statistics(new Stat<?>[]{playTime}, new int[]{1200});

        type.apply(fixture.player, value);
        Object2IntMap<Stat<?>> firstPacket = fixture.connection.packets.getFirst().stats();
        fixture.counter.setValue(fixture.player.getHandle(), playTime, 2400);
        type.apply(fixture.player, value);

        assertEquals(2, fixture.connection.packets.size());
        assertEquals(Map.of(playTime, 1200), firstPacket);
        assertEquals(Map.of(playTime, 1200), fixture.connection.packets.getLast().stats());
        fixture.current.clear();
        assertEquals(Map.of(playTime, 1200), firstPacket);
    }

    @Test
    void emptyApplyClearsValuesAndStillSendsAPacket() throws Exception {
        PlayerFixture fixture = playerFixture();
        Stat<?> stone = Stats.BLOCK_MINED.get(Blocks.STONE);
        fixture.current.put(stone, 64);
        StatisticsDataType type = new StatisticsDataType();
        Statistics empty = new Statistics(new Stat<?>[0], new int[0]);

        type.apply(fixture.player, empty);

        assertTrue(fixture.current.isEmpty());
        assertEquals(Map.of(stone, 0), fixture.connection.packets.getFirst().stats());
        type.apply(fixture.player, empty);
        assertEquals(2, fixture.connection.packets.size());
        assertTrue(fixture.connection.packets.getLast().stats().isEmpty());
    }

    @Test
    void replacementsMatchThePreviousVanillaDirtyAndSendPath() throws Exception {
        PlayerFixture actual = playerFixture();
        PlayerFixture reference = playerFixture();
        Stat<?>[] pool = BuiltInRegistries.ITEM.stream().limit(96).map(Stats.ITEM_USED::get).toArray(Stat<?>[]::new);
        Random random = new Random(1782);
        StatisticsDataType type = new StatisticsDataType();
        for (int round = 0; round < 200; round++) {
            actual.current.clear();
            actual.dirty.clear();
            actual.connection.packets.clear();
            reference.current.clear();
            reference.dirty.clear();
            reference.connection.packets.clear();
            forcedStats().put(Stats.PLAY_TIME, round);
            SpigotConfig.disableStatSaving = (round & 1) == 0;
            for (int i = 0; i < pool.length; i++) {
                Stat<?> statistic = pool[i];
                if (random.nextBoolean()) actual.current.put(statistic, random.nextInt(100));
                if (random.nextBoolean()) actual.dirty.add(statistic);
            }
            reference.current.putAll(actual.current);
            reference.dirty.addAll(actual.dirty);
            Stat<?>[] statistics = new Stat<?>[64];
            int[] amounts = new int[64];
            for (int i = 0; i < statistics.length; i++) {
                statistics[i] = pool[random.nextInt(pool.length)];
                amounts[i] = random.nextInt(4) == 0 ? 0 : random.nextInt(Integer.MAX_VALUE);
            }
            Statistics value = new Statistics(statistics, amounts);

            type.apply(actual.player, value);
            referenceApply(reference, value);

            assertEquals(reference.current, actual.current, "counter round " + round);
            assertEquals(reference.connection.packets.getFirst().stats(), actual.connection.packets.getFirst().stats(), "packet round " + round);
            assertTrue(actual.dirty.isEmpty());
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

    // 用原版 dirty 合并与 sendStats 作为发包参考, 快照在本测试中只含 item 统计.
    private static void referenceApply(PlayerFixture fixture, Statistics value) {
        synchronized (fixture.current) {
            fixture.counter.markAllDirty();
            fixture.current.clear();
            for (int i = 0; i < value.statistics().length; i++) {
                if (value.amounts()[i] != 0) fixture.current.put(value.statistics()[i], value.amounts()[i]);
            }
            fixture.current.put(Stats.CUSTOM.get(Stats.PLAY_TIME), forcedStats().get(Stats.PLAY_TIME).intValue());
            fixture.counter.markAllDirty();
        }
        fixture.counter.sendStats(fixture.player.getHandle());
    }

    @SuppressWarnings("unchecked")
    private static PlayerFixture playerFixture() throws Exception {
        ServerStatsCounter counter = allocateWithoutConstructor(ServerStatsCounter.class);
        Object2IntMap<Stat<?>> current = (Object2IntMap<Stat<?>>) StatsCounterProxy.INSTANCE.getStats(new StatsCounter());
        Set<Stat<?>> dirty = new HashSet<>();
        setField(StatsCounter.class, counter, "stats", current);
        setField(ServerStatsCounter.class, counter, "dirty", dirty);
        ServerPlayer handle = allocateWithoutConstructor(ServerPlayer.class);
        setField(ServerPlayer.class, handle, "stats", counter);
        setField(Entity.class, handle, "uuid", new UUID(0, 1782));
        RecordingConnection connection = allocateWithoutConstructor(RecordingConnection.class);
        connection.packets = new ArrayList<>();
        handle.connection = connection;
        CraftPlayer player = allocateWithoutConstructor(CraftPlayer.class);
        setField(CraftEntity.class, player, "entity", handle);
        return new PlayerFixture(player, counter, current, dirty, connection);
    }

    // 隔离服务器构造与网络连接, 字段装配后仍调用真实的 capture、apply 和原版发包逻辑.
    private static <T> T allocateWithoutConstructor(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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

    private record PlayerFixture(CraftPlayer player, ServerStatsCounter counter, Object2IntMap<Stat<?>> current, Set<Stat<?>> dirty, RecordingConnection connection) {
    }

    private static final class RecordingConnection extends ServerGamePacketListenerImpl {
        private List<ClientboundAwardStatsPacket> packets;

        private RecordingConnection() {
            super(null, null, null, null);
        }

        @Override
        public void send(Packet<?> packet) {
            this.packets.add((ClientboundAwardStatsPacket) packet);
        }
    }
}
