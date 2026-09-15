package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.FileLogWriter;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UnknownDataFlowTest {
    private static final DataKey BOOK = DataKey.of("external", "book");
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0);
    private final List<String> consoleLogs = new ArrayList<>();
    private final SyncLogger logger = new SyncLogger((PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (proxy, method, args) -> {
        this.consoleLogs.add(method.getName() + ": " + args[0]);
        return null;
    }));
    private Field configField;
    private Object previousConfig;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        this.configField = PluginConfig.class.getDeclaredField("config");
        this.configField.setAccessible(true);
        this.previousConfig = this.configField.get(null);
        this.configField.set(null, new PluginConfig.ConfigDefinition());
    }

    @AfterEach
    void tearDown() throws IllegalAccessException {
        this.configField.set(null, this.previousConfig);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disablingAndReenablingLocationUsesTheReceiverDropList(boolean discard) throws Exception {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> "world");
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> world);
        AtomicReference<Location> position = new AtomicReference<>(new Location(world, 10, 64, 10));
        AtomicInteger teleports = new AtomicInteger();
        UUID id = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "PolicyPlayer";
            case "getLocation" -> position.get().clone();
            case "getServer" -> server;
            case "teleport", "teleportAsync" -> {
                teleports.incrementAndGet();
                position.set((Location) args[0]);
                yield method.getName().equals("teleport") ? true : CompletableFuture.completedFuture(true);
            }
            default -> throw new AssertionError(method.getName());
        });
        PlayerDataPipeline dayOne = this.pipeline(new LocationDataType(), new TextType(BOOK));
        Snapshot original = this.capture(dayOne, player, EagerSnapshotData.EMPTY);
        byte[] book = bytes(original, BOOK);
        byte[] location = bytes(original, LocationDataType.LOCATION);
        position.set(new Location(world, 200, 70, 300));
        PluginConfig.ConfigDefinition config = (PluginConfig.ConfigDefinition) this.configField.get(null);
        Field synchronization = PluginConfig.ConfigDefinition.class.getDeclaredField("synchronization");
        synchronization.setAccessible(true);
        NmsPlayerFixture.set(PluginConfig.SynchronizationOptions.class, synchronization.get(config), "discardUnknownData", discard ? List.of(LocationDataType.LOCATION) : List.of());
        PlayerDataPipeline dayTwo = this.pipeline();
        SnapshotApplyContext context = ready(dayTwo, original);
        assertNull(context.takePending(LocationDataType.LOCATION));
        assertEquals(discard ? Set.of(BOOK) : Set.of(BOOK, LocationDataType.LOCATION), context.passthrough().keys());
        assertEquals(0, SnapshotFixtures.decodedBlockCount(original));
        dayTwo.apply(player, context);
        Snapshot saved = this.capture(dayTwo, player, context.passthrough());
        assertEquals(!discard, saved.keys().contains(LocationDataType.LOCATION));
        if (!discard) {
            assertArrayEquals(location, bytes(saved, LocationDataType.LOCATION));
        }
        assertArrayEquals(book, bytes(saved, BOOK));
        assertEquals(0, SnapshotFixtures.decodedBlockCount(saved));
        PlayerDataPipeline dayThree = this.pipeline(new LocationDataType());
        SnapshotApplyContext next = ready(dayThree, saved);
        assertEquals(!discard, next.pendingValues().containsKey(LocationDataType.LOCATION));
        assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, dayThree.apply(player, next));
        assertEquals(discard ? 200 : 10, position.get().getX());
        assertEquals(discard ? 300 : 10, position.get().getZ());
        assertEquals(discard ? 0 : 1, teleports.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void registeredTypeAppliesAndRefreshesCapturedValue(boolean listed) throws Exception {
        TextType type = new TextType(BOOK);
        PlayerDataPipeline pipeline = this.pipeline(listed ? Set.of(BOOK) : Set.of(), type);
        Snapshot source = this.snapshot(UUID.randomUUID(), Map.of(BOOK, NBT.createString("old")));
        SnapshotApplyContext context = ready(pipeline, source);
        assertEquals("old", context.pendingValues().get(BOOK));
        assertTrue(context.passthrough().keys().isEmpty());
        pipeline.apply(this.player(source.meta().player()), context);
        assertEquals("old", type.applied);
        Snapshot saved = this.capture(pipeline, this.player(source.meta().player()), context.passthrough());
        assertEquals("fresh", saved.data(BOOK).getAsString());
    }

    @Test
    void corruptedDropBlockIsDiscardedWithoutReadingPayload() throws Exception {
        Snapshot source = this.snapshot(UUID.randomUUID(), Map.of(BOOK, NBT.createString("broken")));
        var raw = source.content().raw(BOOK);
        raw.bytes()[(int) raw.offset() + 13] ^= 1;
        PlayerDataPipeline pipeline = this.pipeline(Set.of(BOOK));
        SnapshotApplyContext context = ready(pipeline, source);
        assertSame(EagerSnapshotData.EMPTY, context.passthrough());
        assertEquals(0, SnapshotFixtures.decodedBlockCount(source));
        DataRegistry registry = new DataRegistry();
        registry.freeze();
        new SnapshotDecoder(registry).decodeSelected(source, type -> true);
        assertTrue(source.keys().contains(BOOK));
        assertEquals(0, SnapshotFixtures.decodedBlockCount(source));
    }

    @Test
    void everyDropIsLoggedOnlyToFile(@TempDir Path directory) throws Exception {
        PlayerDataPipeline pipeline = this.pipeline(Set.of(BOOK, DataKey.of("external", "other")));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Snapshot source = this.snapshot(first, Map.of(BOOK, NBT.createInt(1)));
        try (FileLogWriter writer = new FileLogWriter(directory, "HH:mm:ss", "'drops'", this.logger.console)) {
            this.logger.attachFile(writer);
            ready(pipeline, source);
            ready(pipeline, source);
            ready(pipeline, this.snapshot(second, source.content().all()));
            ready(pipeline, this.snapshot(first, Map.of(DataKey.of("external", "other"), NBT.createInt(1))));
        }
        List<String> lines = Files.readAllLines(directory.resolve("drops.log"));
        assertEquals(4, lines.size());
        assertEquals(3, lines.stream().filter(line -> line.contains(first.toString())).count());
        assertEquals(1, lines.stream().filter(line -> line.contains(second.toString())).count());
        assertTrue(lines.stream().allMatch(line -> line.contains(LogConstants.DATA_UNKNOWN_DROPPED)));
        assertTrue(this.consoleLogs.isEmpty(), this.consoleLogs.toString());
    }

    @Test
    void receiverRegistryDeterminesUnknownRetention() throws Exception {
        Snapshot source = this.snapshot(UUID.randomUUID(), Map.of(BOOK, NBT.createString("opaque")));
        SnapshotApplyContext kept = ready(this.pipeline(), source);
        SnapshotApplyContext dropped = ready(this.pipeline(Set.of(BOOK)), source);
        assertEquals(Set.of(BOOK), kept.passthrough().keys());
        assertTrue(dropped.passthrough().keys().isEmpty());
        assertEquals(0, SnapshotFixtures.decodedBlockCount(source));
        assertArrayEquals(bytes(source, BOOK), bytes(new Snapshot(source.meta(), kept.passthrough()), BOOK));
    }

    private PlayerDataPipeline pipeline(PlayerDataType<?>... types) {
        return this.pipeline(Set.of(), types);
    }

    private PlayerDataPipeline pipeline(Set<DataKey> dropped, PlayerDataType<?>... types) {
        DataRegistry registry = new DataRegistry();
        for (DataKey key : dropped) {
            registry.registerUnknownDrop(key);
        }
        for (PlayerDataType<?> type : types) registry.register(type);
        registry.freeze();
        PlayerDataPipeline pipeline = new PlayerDataPipeline(null);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "dataRegistry", registry);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "decoder", new SnapshotDecoder(registry));
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "dataCodec", new SnapshotDataCodec(CompressorRegistry.NONE));
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "logger", this.logger);
        return pipeline;
    }

    private Snapshot capture(PlayerDataPipeline pipeline, Player player, SnapshotData retained) throws IOException {
        var captured = assertInstanceOf(PlayerDataPipeline.CaptureResult.Ready.class, pipeline.capture(player, CaptureMode.SYNC));
        var encoded = assertInstanceOf(PlayerDataPipeline.EncodeResult.Ready.class, pipeline.encode(captured));
        Snapshot snapshot = new Snapshot(SnapshotMeta.builder().player(player.getUniqueId()).timestamp(1).cause(SaveCause.COMMAND).build(), retained.with(encoded.data()));
        return assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();
    }

    private Snapshot snapshot(UUID player, Map<DataKey, Tag> data) throws IOException {
        Snapshot snapshot = new Snapshot(SnapshotMeta.builder().player(player).timestamp(1).cause(SaveCause.COMMAND).build(), new EagerSnapshotData(data));
        return assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();
    }

    private static SnapshotApplyContext ready(PlayerDataPipeline pipeline, Snapshot snapshot) {
        return assertInstanceOf(PlayerDataPipeline.DecodeResult.Ready.class, pipeline.decode(snapshot)).context();
    }

    private Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "PolicyPlayer";
            default -> throw new AssertionError(method.getName());
        });
    }

    private static byte[] bytes(Snapshot snapshot, DataKey key) {
        var block = snapshot.content().raw(key);
        return Arrays.copyOfRange(block.bytes(), (int) block.offset(), (int) block.end());
    }

    private static final class TextType implements PlayerDataType<String> {
        private final DataKey key;
        private String applied;

        private TextType(DataKey key) {
            this.key = key;
        }

        @Override
        @NotNull
        public DataKey key() { return this.key; }

        @Override
        @NotNull
        public String capture(@NotNull Player player, @NotNull CaptureMode mode) { return "fresh"; }

        @Override
        @NotNull
        public Tag encode(@NotNull String value) { return NBT.createString(value); }

        @Override
        @NotNull
        public String decode(@NotNull Tag tag) { return tag.getAsString(); }

        @Override
        public void apply(@NotNull Player player, @NotNull String value) { this.applied = value; }
    }
}
