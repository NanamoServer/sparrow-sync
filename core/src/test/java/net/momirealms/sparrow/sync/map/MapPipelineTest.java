package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.FileLogWriter;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MapPipelineTest {
    private static final DataRegistry REGISTRY = registry();
    private static final SyncLogger LOGGER = new SyncLogger(new RecordingLogger());
    private static final MapPipeline PIPELINE = new MapPipeline(REGISTRY, List.of(MapType.values()), LOGGER);
    private static final String OWNER = "A-world-1";

    @TempDir
    Path directory;

    @Test
    void existingOriginIsNotRecompiledByAnotherServer() {
        Snapshot compiled = PIPELINE.compile(snapshot(map(7)), MapType.HIDE, OWNER);
        assertSame(compiled, PIPELINE.decode(compiled, "B-world-2"));
        assertSame(compiled, PIPELINE.compile(compiled, MapType.HIDE, "B-world-2"));
        assertEquals(OWNER, marker(compiled).getString("origin-server"));
    }

    @Test
    void hideCompilesMapZeroIntoTheSpecifiedSchemaAndLeavesTheSourceUnchanged() {
        Snapshot original = snapshot(map(0));
        CompoundTag expected = firstItem(original).copy();
        Snapshot compiled = PIPELINE.compile(original, MapType.HIDE, OWNER);

        assertFalse(components(compiled).containsKey("minecraft:map_id"));
        assertEquals(3, marker(compiled).size());
        assertEquals("HIDE", marker(compiled).getString("map-type"));
        assertEquals(OWNER, marker(compiled).getString("origin-server"));
        assertEquals(0, marker(compiled).getInt("origin-id"));
        assertEquals(expected, firstItem(original));
        assertEquals(original.data(), PIPELINE.decode(compiled, OWNER).data());
    }

    @Test
    void hiddenMapsKeepTheirOwnerAcrossHopsAndRemainHiddenAfterAWorldReset() {
        Snapshot original = snapshot(map(42));
        Snapshot compiled = PIPELINE.compile(original, MapType.HIDE, OWNER);
        Snapshot inB = PIPELINE.decode(compiled, "B-world-2");
        Snapshot savedByB = new Snapshot(meta("B"), inB.data());
        Snapshot inC = PIPELINE.compile(savedByB, MapType.HIDE, "B-world-2");

        assertSame(savedByB, inC);
        assertSame(inC, PIPELINE.decode(inC, "C-world-3"));
        assertSame(inC, PIPELINE.decode(inC, "A-rebuilt-world"));
        assertFalse(components(inC).containsKey("minecraft:map_id"));
        assertEquals(original.data(), PIPELINE.decode(inC, OWNER).data());
    }

    @Test
    void returnToOwnerRemovesOnlyMapMetadataFromCustomData() {
        CompoundTag item = map(7);
        CompoundTag components = item.getCompound("components");
        components.putString("minecraft:custom_name", "A's map");
        CompoundTag namespace = NBT.createCompound();
        namespace.putString("other-setting", "kept");
        CompoundTag custom = NBT.createCompound();
        custom.put("sparrow-sync", namespace);
        custom.putString("other:marker", "kept");
        components.put("minecraft:custom_data", custom);
        Snapshot original = snapshot(item);

        for (MapType type : MapType.values()) {
            Snapshot compiled = PIPELINE.compile(original, type, OWNER);
            assertEquals(original.data(), PIPELINE.decode(compiled, OWNER).data());
        }
    }

    @Test
    void knownNestedItemComponentsRoundTripThroughBothInventoryTypes() {
        CompoundTag bundle = item("minecraft:bundle");
        bundle.getCompound("components").put("minecraft:bundle_contents", list(map(3), map(4)));
        CompoundTag entry = NBT.createCompound();
        entry.putInt("slot", 12);
        entry.put("item", bundle);
        CompoundTag box = item("minecraft:shulker_box");
        box.getCompound("components").put("minecraft:container", list(entry));
        CompoundTag crossbow = item("minecraft:crossbow");
        crossbow.getCompound("components").put("minecraft:charged_projectiles", list(map(5)));
        CompoundTag food = item("minecraft:apple");
        food.getCompound("components").put("minecraft:use_remainder", map(6));
        CompoundTag contents = NBT.createCompound();
        contents.put("items", list(box, crossbow, food));
        Snapshot original = new Snapshot(meta("A"), Map.of(InventoryDataType.INVENTORY, contents, EnderChestDataType.ENDER_CHEST, contents));

        Snapshot compiled = PIPELINE.compile(original, MapType.HIDE, OWNER);
        for (Tag value : compiled.data().values()) {
            ListTag items = ((CompoundTag) value).getList("items");
            CompoundTag hiddenBundle = items.getCompound(0).getCompound("components").getList("minecraft:container").getCompound(0).getCompound("item");
            assertFalse(hiddenBundle.getCompound("components").getList("minecraft:bundle_contents").getCompound(1).getCompound("components").containsKey("minecraft:map_id"));
            assertFalse(items.getCompound(1).getCompound("components").getList("minecraft:charged_projectiles").getCompound(0).getCompound("components").containsKey("minecraft:map_id"));
            assertFalse(items.getCompound(2).getCompound("components").getCompound("minecraft:use_remainder").getCompound("components").containsKey("minecraft:map_id"));
        }
        assertEquals(original.data(), PIPELINE.decode(compiled, OWNER).data());
    }

    @Test
    void disabledTypesAndItemLikeCustomDataAreNotCompiled() {
        Snapshot original = snapshot(map(7));
        MapPipeline disabled = new MapPipeline(new DataRegistry(), List.of(MapType.values()), LOGGER);
        assertSame(original, disabled.compile(original, MapType.HIDE, OWNER));
        Snapshot unknown = new Snapshot(meta("A"), Map.of(DataKey.of("other", "inventory"), original.data(InventoryDataType.INVENTORY)));
        assertSame(unknown, PIPELINE.compile(unknown, MapType.HIDE, OWNER));

        CompoundTag stone = item("minecraft:stone");
        CompoundTag custom = NBT.createCompound();
        custom.put("display_example", map(7));
        stone.getCompound("components").put("minecraft:custom_data", custom);
        Snapshot customSnapshot = snapshot(stone);
        assertSame(customSnapshot, PIPELINE.compile(customSnapshot, MapType.HIDE, OWNER));
    }

    @Test
    void decodeDoesNotGuessTheOwnerOfUncompiledItems() {
        Snapshot original = snapshot(map(7));
        assertSame(original, PIPELINE.decode(original, "B-world-2"));
        Snapshot idless = snapshot(item("minecraft:filled_map"));
        assertSame(idless, PIPELINE.compile(idless, MapType.HIDE, OWNER));
        assertSame(idless, PIPELINE.decode(idless, OWNER));
    }

    @Test
    void malformedOriginMetadataKeepsTheOriginalMap() {
        Snapshot compiled = PIPELINE.compile(snapshot(map(7)), MapType.HIDE, OWNER);
        CompoundTag brokenItem = firstItem(compiled).copy();
        brokenItem.getCompound("components").getCompound("minecraft:custom_data").getCompound("sparrow-sync").remove("origin-id");
        Snapshot broken = snapshot(brokenItem);
        assertSame(broken, PIPELINE.decode(broken, OWNER));
    }

    @Test
    void handlersReceiveTheOriginAndCurrentOwnerOnBothDecodeRoutes() {
        int[] calls = new int[2];
        MapHandler handler = new MapHandler() {
            @Override
            @NotNull
            public MapType type() {
                return MapType.HIDE;
            }

            @Override
            @NotNull
            public CompoundTag compile(@NotNull CompoundTag components, @NotNull MapOrigin origin) {
                calls[0]++;
                assertEquals(new MapOrigin(MapType.HIDE, OWNER, 7), origin);
                return MapType.HIDE.compile(components, origin);
            }

            @Override
            @NotNull
            public CompoundTag decode(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
                calls[1]++;
                assertEquals(new MapOrigin(MapType.HIDE, OWNER, 7), origin);
                assertEquals(calls[1] == 1 ? "B-world-2" : OWNER, ownerId);
                return MapType.HIDE.decode(components, origin, ownerId);
            }
        };
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(handler), LOGGER);
        Snapshot compiled = pipeline.compile(snapshot(map(7)), MapType.HIDE, OWNER);
        pipeline.compile(compiled, MapType.HIDE, OWNER);
        pipeline.decode(compiled, "B-world-2");
        assertArrayEquals(new int[]{1, 1}, calls);
        pipeline.decode(compiled, OWNER);
        assertArrayEquals(new int[]{1, 2}, calls);
    }

    @Test
    void failedMapsPassThroughWhileOtherMapsContinueAndBothLogsReceiveWarnings() throws Exception {
        RecordingLogger console = new RecordingLogger();
        SyncLogger logger = new SyncLogger(console);
        logger.attachFile(new FileLogWriter(this.directory, console));
        MapHandler failing = new MapHandler() {
            @Override
            @NotNull
            public MapType type() {
                return MapType.HIDE;
            }

            @Override
            @NotNull
            public CompoundTag compile(@NotNull CompoundTag components, @NotNull MapOrigin origin) {
                if (origin.id() == 7) {
                    throw new IllegalStateException("compile failure");
                }
                return MapType.HIDE.compile(components, origin);
            }

            @Override
            @NotNull
            public CompoundTag decode(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
                if (origin.id() == 8) {
                    throw new IllegalStateException("decode failure");
                }
                return MapType.HIDE.decode(components, origin, ownerId);
            }
        };
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(failing), logger);
        CompoundTag inventory = NBT.createCompound();
        ListTag originalItems = list(map(7), map(8), map(9));
        inventory.put("items", originalItems);
        Snapshot original = new Snapshot(meta("A"), Map.of(InventoryDataType.INVENTORY, inventory));
        try {
            Snapshot compiled = pipeline.compile(original, MapType.HIDE, OWNER);
            ListTag compiledItems = ((CompoundTag) compiled.data(InventoryDataType.INVENTORY)).getList("items");
            assertSame(originalItems.get(0), compiledItems.get(0));
            assertFalse(compiledItems.getCompound(1).getCompound("components").containsKey("minecraft:map_id"));
            Snapshot decoded = pipeline.decode(compiled, OWNER);
            ListTag decodedItems = ((CompoundTag) decoded.data(InventoryDataType.INVENTORY)).getList("items");
            assertSame(originalItems.get(0), decodedItems.get(0));
            assertSame(compiledItems.get(1), decodedItems.get(1));
            assertEquals(originalItems.get(2), decodedItems.get(2));
        } finally {
            logger.close();
        }

        assertEquals(List.of(LogConstants.DATA_MAP_COMPILE_FAILED, LogConstants.DATA_MAP_DECODE_FAILED), console.warnings);
        try (Stream<Path> files = Files.list(this.directory)) {
            String content = Files.readString(files.findFirst().orElseThrow());
            assertTrue(content.contains(LogConstants.DATA_MAP_COMPILE_FAILED), content);
            assertTrue(content.contains(LogConstants.DATA_MAP_DECODE_FAILED), content);
            assertTrue(content.contains("java.lang.IllegalStateException: compile failure"), content);
            assertTrue(content.contains("java.lang.IllegalStateException: decode failure"), content);
            assertTrue(content.contains(original.meta().player().toString()), content);
        }
    }

    private static Snapshot snapshot(CompoundTag item) {
        CompoundTag inventory = NBT.createCompound();
        inventory.putInt("size", 43);
        inventory.put("items", list(item));
        return new Snapshot(meta("A"), Map.of(InventoryDataType.INVENTORY, inventory));
    }

    private static SnapshotMeta meta(String server) {
        return new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1L, SaveCause.DISCONNECT, false, server, 0);
    }

    private static CompoundTag firstItem(Snapshot snapshot) {
        return ((CompoundTag) snapshot.data(InventoryDataType.INVENTORY)).getList("items").getCompound(0);
    }

    private static CompoundTag components(Snapshot snapshot) {
        return firstItem(snapshot).getCompound("components");
    }

    private static CompoundTag marker(Snapshot snapshot) {
        return components(snapshot).getCompound("minecraft:custom_data").getCompound("sparrow-sync");
    }

    private static CompoundTag map(int id) {
        CompoundTag item = item("minecraft:filled_map");
        item.getCompound("components").putInt("minecraft:map_id", id);
        return item;
    }

    private static CompoundTag item(String id) {
        CompoundTag item = NBT.createCompound();
        item.putString("id", id);
        item.putInt("count", 1);
        item.put("components", NBT.createCompound());
        return item;
    }

    private static ListTag list(Tag... items) {
        ListTag list = NBT.createList();
        for (int i = 0; i < items.length; i++) list.add(items[i]);
        return list;
    }

    private static DataRegistry registry() {
        DataRegistry registry = new DataRegistry();
        registry.register(NmsPlayerFixture.allocate(InventoryDataType.class));
        registry.register(NmsPlayerFixture.allocate(EnderChestDataType.class));
        registry.freeze();
        return registry;
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            this.warnings.add(message);
        }

        @Override
        public void warn(String message, Throwable cause) {
            this.warnings.add(message);
        }

        @Override
        public void error(String message) {
            throw new AssertionError(message);
        }

        @Override
        public void error(String message, Throwable cause) {
            throw new AssertionError(message, cause);
        }
    }
}
