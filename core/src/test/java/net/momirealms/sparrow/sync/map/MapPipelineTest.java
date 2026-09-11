package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapOrigin;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.handler.HideMapHandler;
import net.momirealms.sparrow.sync.map.handler.MapHandler;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.map.handler.SyncMapHandler;
import net.momirealms.sparrow.sync.plugin.logger.FileLogWriter;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MapPipelineTest {
    @RegisterExtension
    private final MapFlowTestSupport.PluginInstance pluginInstance = new MapFlowTestSupport.PluginInstance();

    private static final DataRegistry REGISTRY = registry();
    private static final SyncLogger LOGGER = new SyncLogger(new RecordingLogger());
    private static final MapPipeline PIPELINE = new MapPipeline(REGISTRY, List.of(new HideMapHandler()), LOGGER);
    private static final String OWNER = "A-world-1";

    private final MapFlowTestSupport.NativeMaps nativeMaps = new MapFlowTestSupport.NativeMaps(new MapIdentity(new MapSource(OWNER, 7), -1));

    @TempDir
    Path directory;

    @Test
    void mapFreeItemsAreVisitedOnceAndKeepTheOriginalSnapshot() {
        int[] visits = {0};
        CompoundTag item = new CompoundTag(item("minecraft:stone").tags) {
            @Override
            public Tag get(@NotNull String key) {
                if (key.equals("components")) visits[0]++;
                return super.get(key);
            }
        };
        Snapshot original = snapshot(item);
        CompletableFuture<Snapshot> compiled = PIPELINE.encodeAsync(original, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); });
        assertTrue(compiled.isDone());
        assertSame(original, compiled.join());
        assertEquals(1, visits[0]);
        assertSame(original, PIPELINE.decodeAsync(original, OWNER).join());
        assertEquals(2, visits[0]);
    }

    @Test
    void syncCompilationWaitsForPublicationAndTransitKeepsItsMode() {
        MapFlowTestSupport.Shared shared = new MapFlowTestSupport.Shared();
        MapFlowTestSupport.Storage storage = new MapFlowTestSupport.Storage();
        MapReceiver receiver = this.nativeMaps.receiver(storage, shared, "B-world", Runnable::run, Runnable::run, LOGGER);
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(new HideMapHandler(), new SyncMapHandler(receiver)), LOGGER);
        Snapshot original = snapshot(map(7));
        CompletableFuture<StoredMap> published = new CompletableFuture<>();
        CompletableFuture<Snapshot> waiting = pipeline.encodeAsync(original, MapType.SYNC, OWNER, nativeMapId -> Map.<Integer, CompletableFuture<StoredMap>>of(7, published).get(nativeMapId));
        assertFalse(waiting.isDone());
        assertEquals(7, components(original).getInt("minecraft:map_id"));
        published.complete(new StoredMap(new MapIdentity(new MapSource(OWNER, 7), -1), MapFlowTestSupport.map(5).data()));
        Snapshot compiled = waiting.join();
        assertEquals(-1, components(compiled).getInt("minecraft:map_id"));
        assertEquals("SYNC", marker(compiled).getString("map-type"));
        assertSame(compiled, pipeline.encodeAsync(compiled, MapType.HIDE, "B", nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join());
        assertEquals(1, shared.touches);
        assertEquals(0, storage.registrations);
        assertTrue(shared.writes.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "missing", "failed-future", "throw"})
    void transitCopiesShareRenewalWithinOneCompilationAndKeepTheirOwnComponents(String outcome) {
        List<Integer> renewed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<CompletableFuture<Boolean>> pending = new ArrayList<>();
        MapFlowTestSupport.Shared shared = new MapFlowTestSupport.Shared() {
            @Override
            @NotNull
            public CompletableFuture<Boolean> touch(int globalId) {
                renewed.add(globalId);
                if (globalId == -2) return CompletableFuture.completedFuture(true);
                if (outcome.equals("throw")) throw new IllegalStateException("Redis rejected command");
                if (outcome.equals("failed-future")) return CompletableFuture.failedFuture(new IllegalStateException("Redis unavailable"));
                CompletableFuture<Boolean> future = new CompletableFuture<>();
                pending.add(future);
                return future;
            }
        };
        MapReceiver receiver = this.nativeMaps.receiver(new MapFlowTestSupport.Storage(), shared, "B-world", Runnable::run, Runnable::run, LOGGER);
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(new HideMapHandler(), new SyncMapHandler(receiver)), MapFlowTestSupport.logger(warnings));
        CompoundTag first = this.transitMap(-1, 7, "first");
        CompoundTag second = this.transitMap(-1, 7, "second");
        CompoundTag third = this.transitMap(-2, 8, "third");
        CompoundTag bundle = item("minecraft:bundle");
        bundle.getCompound("components").put("minecraft:bundle_contents", list(second, third));
        CompoundTag inventory = NBT.createCompound();
        inventory.put("items", list(first, bundle));
        Snapshot original = new Snapshot(meta("B"), Map.of(InventoryDataType.INVENTORY, inventory));
        CompletableFuture<Snapshot> result = pipeline.encodeAsync(original, MapType.HIDE, "B-world", nativeMapId -> { throw new AssertionError("unexpected source publication"); });
        assertEquals(List.of(-1, -2), renewed);
        if (!pending.isEmpty()) {
            assertFalse(result.isDone());
            pending.getFirst().complete(outcome.equals("success"));
        }
        assertSame(original, result.join());
        assertEquals("first", first.getCompound("components").getString("minecraft:custom_name"));
        assertEquals("second", second.getCompound("components").getString("minecraft:custom_name"));
        assertEquals(outcome.startsWith("failed") || outcome.equals("throw") ? 2 : 0, warnings.size());
        CompletableFuture<Snapshot> next = pipeline.encodeAsync(original, MapType.HIDE, "B-world", nativeMapId -> { throw new AssertionError("unexpected source publication"); });
        assertEquals(List.of(-1, -2, -1, -2), renewed);
        if (pending.size() == 2) pending.getLast().complete(true);
        assertSame(original, next.join());
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failed-future", "throw"})
    void sourceCopiesShareOnePublicationPerEncodingAndKeepIndependentFallbacks(String outcome) {
        List<Integer> sampled = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        MapReceiver receiver = this.nativeMaps.receiver(new MapFlowTestSupport.Storage(), new MapFlowTestSupport.Shared(), OWNER, Runnable::run, Runnable::run, LOGGER);
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(new HideMapHandler(), new SyncMapHandler(receiver)), MapFlowTestSupport.logger(warnings));
        CompoundTag first = map(7);
        CompoundTag second = map(7);
        first.getCompound("components").putString("minecraft:custom_name", "first");
        second.getCompound("components").putString("minecraft:custom_name", "second");
        CompoundTag bundle = item("minecraft:bundle");
        bundle.getCompound("components").put("minecraft:bundle_contents", list(second, map(8)));
        CompoundTag inventory = NBT.createCompound();
        inventory.put("items", list(first, bundle));
        Snapshot original = new Snapshot(meta("A"), Map.of(InventoryDataType.INVENTORY, inventory));
        IntFunction<CompletableFuture<StoredMap>> publish = id -> {
            sampled.add(id);
            if (id == 7 && outcome.equals("throw")) throw new IllegalStateException("capture failed");
            if (id == 7 && outcome.equals("failed-future")) return CompletableFuture.failedFuture(new IllegalStateException("publish failed"));
            return CompletableFuture.completedFuture(new StoredMap(new MapIdentity(new MapSource(OWNER, id), id == 7 ? -1 : -2), MapFlowTestSupport.map(5).data()));
        };
        for (int i = 0; i < 2; i++) {
            Snapshot compiled = pipeline.encodeAsync(original, MapType.SYNC, OWNER, publish).join();
            ListTag contents = ((CompoundTag) compiled.data(InventoryDataType.INVENTORY)).getList("items");
            CompoundTag a = contents.getCompound(0).getCompound("components");
            ListTag nested = contents.getCompound(1).getCompound("components").getList("minecraft:bundle_contents");
            CompoundTag b = nested.getCompound(0).getCompound("components");
            assertEquals(outcome.equals("success") ? -1 : 7, a.getInt("minecraft:map_id"));
            assertEquals(a.getInt("minecraft:map_id"), b.getInt("minecraft:map_id"));
            assertEquals("first", a.getString("minecraft:custom_name"));
            assertEquals("second", b.getString("minecraft:custom_name"));
            assertEquals(-2, nested.getCompound(1).getCompound("components").getInt("minecraft:map_id"));
            assertEquals(7, first.getCompound("components").getInt("minecraft:map_id"));
        }
        assertEquals(List.of(7, 8, 7, 8), sampled);
        assertEquals(outcome.equals("success") ? 0 : 4, warnings.size());
        receiver.close();
    }

    private CompoundTag transitMap(int globalId, int sourceId, String name) {
        CompoundTag item = map(globalId);
        CompoundTag components = item.getCompound("components");
        components.putString("minecraft:custom_name", name);
        CompoundTag origin = NBT.createCompound();
        origin.putString("map-type", "SYNC");
        origin.putString("origin-server", OWNER);
        origin.putInt("origin-id", sourceId);
        CompoundTag custom = NBT.createCompound();
        custom.put("sparrow-sync", origin);
        components.put("minecraft:custom_data", custom);
        return item;
    }

    @Test
    void asyncDecodeWaitsForReplicaAndOnlyClearsOriginAfterSuccessfulReturn() {
        MapFlowTestSupport.Storage storage = new MapFlowTestSupport.Storage();
        storage.current = new StoredMap(new MapIdentity(new MapSource(OWNER, 7), -1), MapFlowTestSupport.map(4).data());
        MapFlowTestSupport.Tasks nativeThread = new MapFlowTestSupport.Tasks();
        MapReceiver receiver = this.nativeMaps.receiver(storage, new MapFlowTestSupport.Shared(), OWNER, Runnable::run, nativeThread, LOGGER);
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(new HideMapHandler(), new SyncMapHandler(receiver)), LOGGER);
        Snapshot original = snapshot(map(7));
        Snapshot compiled = pipeline.encodeAsync(original, MapType.SYNC, OWNER, nativeMapId -> Map.<Integer, CompletableFuture<StoredMap>>of(7, CompletableFuture.completedFuture(storage.current)).get(nativeMapId)).join();
        CompletableFuture<Snapshot> waiting = pipeline.decodeAsync(compiled, OWNER);
        assertFalse(waiting.isDone());
        nativeThread.runAll();
        assertSame(compiled, waiting.join());
        assertEquals(OWNER, marker(waiting.join()).getString("origin-server"));
        this.nativeMaps.sourcePresent(true);
        CompletableFuture<Snapshot> returned = pipeline.decodeAsync(compiled, OWNER);
        nativeThread.runAll();
        assertEquals(original.data(), returned.join().data());
        assertEquals(-1, components(compiled).getInt("minecraft:map_id"));
    }

    @Test
    void asynchronousFailureKeepsWholeItemWhileOtherMapsCompile() {
        List<String> warnings = new ArrayList<>();
        MapReceiver receiver = this.nativeMaps.receiver(new MapFlowTestSupport.Storage(), new MapFlowTestSupport.Shared(), "B-world", Runnable::run, Runnable::run, LOGGER);
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(new SyncMapHandler(receiver)), MapFlowTestSupport.logger(warnings));
        CompoundTag inventory = NBT.createCompound();
        ListTag items = list(map(7), map(8));
        inventory.put("items", items);
        Snapshot snapshot = new Snapshot(meta("A"), Map.of(InventoryDataType.INVENTORY, inventory));
        StoredMap success = new StoredMap(new MapIdentity(new MapSource(OWNER, 8), -2), MapFlowTestSupport.map(4).data());
        Snapshot compiled = pipeline.encodeAsync(snapshot, MapType.SYNC, OWNER, nativeMapId -> Map.<Integer, CompletableFuture<StoredMap>>of(7, CompletableFuture.failedFuture(new IllegalStateException("database unavailable")), 8, CompletableFuture.completedFuture(success)).get(nativeMapId)).join();
        ListTag result = ((CompoundTag) compiled.data(InventoryDataType.INVENTORY)).getList("items");
        assertSame(items.get(0), result.get(0));
        assertEquals(-2, result.getCompound(1).getCompound("components").getInt("minecraft:map_id"));
        assertEquals(1, warnings.size());
    }

    @Test
    void timeoutReleasesSnapshotWithoutTerminatingThePublicationChain() throws Exception {
        MapReceiver receiver = this.nativeMaps.receiver(new MapFlowTestSupport.Storage(), new MapFlowTestSupport.Shared(), "B-world", Runnable::run, Runnable::run, LOGGER);
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(new SyncMapHandler(receiver)), LOGGER);
        Snapshot original = snapshot(map(7));
        CompletableFuture<StoredMap> storage = new CompletableFuture<>();
        assertSame(original, pipeline.encodeAsync(original, MapType.SYNC, OWNER, nativeMapId -> Map.<Integer, CompletableFuture<StoredMap>>of(7, storage).get(nativeMapId)).get(7, TimeUnit.SECONDS));
        assertFalse(storage.isDone());
        storage.complete(new StoredMap(new MapIdentity(new MapSource(OWNER, 7), -1), MapFlowTestSupport.map(5).data()));
        assertEquals(7, components(original).getInt("minecraft:map_id"));
    }

    @Test
    void completedOperationsReleaseCloseSignalDependencies() throws ReflectiveOperationException {
        this.nativeMaps.sourcePresent(true);
        MapFlowTestSupport.Storage storage = new MapFlowTestSupport.Storage();
        storage.current = new StoredMap(new MapIdentity(new MapSource(OWNER, 7), -1), MapFlowTestSupport.map(4).data());
        MapFlowTestSupport.Tasks nativeThread = new MapFlowTestSupport.Tasks();
        MapReceiver receiver = this.nativeMaps.receiver(storage, new MapFlowTestSupport.Shared(), OWNER, Runnable::run, nativeThread, LOGGER);
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(new HideMapHandler(), new SyncMapHandler(receiver)), LOGGER);
        Field field = MapPipeline.class.getDeclaredField("closed");
        field.setAccessible(true);
        CompletableFuture<?> closed = (CompletableFuture<?>) field.get(pipeline);

        for (int i = 0; i < 5; i++) {
            Snapshot empty = new Snapshot(meta("A"), Map.of());
            assertSame(empty, pipeline.encodeAsync(empty, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join());
            assertSame(empty, pipeline.decodeAsync(empty, OWNER).join());
            assertEquals(0, closed.getNumberOfDependents());

            Snapshot original = snapshot(map(7));
            Snapshot hidden = pipeline.encodeAsync(original, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();
            assertEquals(original.data(), pipeline.decodeAsync(hidden, OWNER).join().data());
            assertEquals(0, closed.getNumberOfDependents());

            CompletableFuture<StoredMap> published = new CompletableFuture<>();
            CompletableFuture<Snapshot> compiling = pipeline.encodeAsync(original, MapType.SYNC, OWNER, nativeMapId -> Map.<Integer, CompletableFuture<StoredMap>>of(7, published).get(nativeMapId));
            assertFalse(compiling.isDone());
            assertTrue(closed.getNumberOfDependents() > 0);
            published.complete(storage.current);
            Snapshot compiled = compiling.join();
            assertEquals(-1, components(compiled).getInt("minecraft:map_id"));
            assertEquals(0, closed.getNumberOfDependents());

            CompletableFuture<Snapshot> decoding = pipeline.decodeAsync(compiled, OWNER);
            assertFalse(decoding.isDone());
            nativeThread.runAll();
            assertEquals(original.data(), decoding.join().data());
            assertEquals(0, closed.getNumberOfDependents());
        }
        assertFalse(closed.isDone());
    }

    @Test
    void closeReleasesCompileAndDecodeWithoutCompletingTheirUnderlyingWork() {
        this.nativeMaps.sourcePresent(true);
        MapFlowTestSupport.Storage storage = new MapFlowTestSupport.Storage();
        storage.current = new StoredMap(new MapIdentity(new MapSource(OWNER, 7), -1), MapFlowTestSupport.map(4).data());
        MapFlowTestSupport.Tasks nativeThread = new MapFlowTestSupport.Tasks();
        MapReceiver receiver = this.nativeMaps.receiver(storage, new MapFlowTestSupport.Shared(), OWNER, Runnable::run, nativeThread, LOGGER);
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(new HideMapHandler(), new SyncMapHandler(receiver)), LOGGER);
        Snapshot original = snapshot(map(7));
        Snapshot compiled = pipeline.encodeAsync(original, MapType.SYNC, OWNER, nativeMapId -> Map.<Integer, CompletableFuture<StoredMap>>of(7, CompletableFuture.completedFuture(storage.current)).get(nativeMapId)).join();
        CompletableFuture<StoredMap> published = new CompletableFuture<>();
        CompletableFuture<Snapshot> compiling = pipeline.encodeAsync(original, MapType.SYNC, OWNER, nativeMapId -> Map.<Integer, CompletableFuture<StoredMap>>of(7, published).get(nativeMapId));
        CompletableFuture<Snapshot> decoding = pipeline.decodeAsync(compiled, OWNER);
        CompletableFuture<Integer> received = receiver.receive(storage.current.identity());
        assertFalse(compiling.isDone());
        assertFalse(decoding.isDone());

        pipeline.close();
        assertSame(original, compiling.getNow(null));
        assertSame(compiled, decoding.getNow(null));
        assertFalse(published.isDone());
        assertFalse(received.isDone());
        assertSame(original, pipeline.encodeAsync(original, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).getNow(null));
        assertSame(compiled, pipeline.decodeAsync(compiled, OWNER).getNow(null));

        published.complete(storage.current);
        nativeThread.runAll();
        assertEquals(7, received.join());
        assertSame(original, compiling.join());
        assertSame(compiled, decoding.join());
        assertEquals(7, components(original).getInt("minecraft:map_id"));
    }

    @Test
    void unregisteredSyncModePassesThroughWithoutHalfEncoding() {
        Snapshot original = snapshot(map(7));
        assertSame(original, PIPELINE.encodeAsync(original, MapType.SYNC, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join());
    }

    @Test
    void missingOriginKeepsReplicaMetadataUntilNativeIdWasRestored() {
        boolean[] restore = {false};
        MapHandler handler = new MapHandler() {
            @Override
            public @NonNull MapType type() {
                return MapType.SYNC;
            }

            @Override
            public @NonNull CompletableFuture<CompoundTag> compileAsync(@NonNull CompoundTag components, @NonNull MapOrigin origin, @NonNull IntFunction<CompletableFuture<StoredMap>> captured) {
                CompoundTag result = components.copy();
                result.putInt("minecraft:map_id", -1);
                return CompletableFuture.completedFuture(result);
            }

            @Override
            public @NonNull CompletableFuture<CompoundTag> decodeAsync(@NonNull CompoundTag components, @NonNull MapOrigin origin, @NonNull String ownerId) {
                return restore[0] ? new HideMapHandler().decodeAsync(components, origin, ownerId) : CompletableFuture.completedFuture(components);
            }
        };
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(handler), LOGGER);
        Snapshot original = snapshot(map(7));
        Snapshot compiled = pipeline.encodeAsync(original, MapType.SYNC, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();
        Snapshot missing = pipeline.decodeAsync(compiled, OWNER).join();
        assertEquals(-1, components(missing).getInt("minecraft:map_id"));
        assertEquals(OWNER, marker(missing).getString("origin-server"));
        restore[0] = true;
        assertEquals(original.data(), pipeline.decodeAsync(compiled, OWNER).join().data());
    }

    @Test
    void existingOriginIsNotRecompiledByAnotherServer() {
        Snapshot compiled = PIPELINE.encodeAsync(snapshot(map(7)), MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();
        assertSame(compiled, PIPELINE.decodeAsync(compiled, "B-world-2").join());
        assertSame(compiled, PIPELINE.encodeAsync(compiled, MapType.HIDE, "B-world-2", nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join());
        assertEquals(OWNER, marker(compiled).getString("origin-server"));
    }

    @Test
    void hideCompilesMapZeroIntoTheSpecifiedSchemaAndLeavesTheSourceUnchanged() {
        Snapshot original = snapshot(map(0));
        CompoundTag expected = firstItem(original).copy();
        Snapshot compiled = PIPELINE.encodeAsync(original, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();

        assertFalse(components(compiled).containsKey("minecraft:map_id"));
        assertEquals(3, marker(compiled).size());
        assertEquals("HIDE", marker(compiled).getString("map-type"));
        assertEquals(OWNER, marker(compiled).getString("origin-server"));
        assertEquals(0, marker(compiled).getInt("origin-id"));
        assertEquals(expected, firstItem(original));
        assertEquals(original.data(), PIPELINE.decodeAsync(compiled, OWNER).join().data());
    }

    @Test
    void hiddenMapsKeepTheirOwnerAcrossHopsAndRemainHiddenAfterAWorldReset() {
        Snapshot original = snapshot(map(42));
        Snapshot compiled = PIPELINE.encodeAsync(original, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();
        Snapshot inB = PIPELINE.decodeAsync(compiled, "B-world-2").join();
        Snapshot savedByB = new Snapshot(meta("B"), inB.data());
        Snapshot inC = PIPELINE.encodeAsync(savedByB, MapType.HIDE, "B-world-2", nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();

        assertSame(savedByB, inC);
        assertSame(inC, PIPELINE.decodeAsync(inC, "C-world-3").join());
        assertSame(inC, PIPELINE.decodeAsync(inC, "A-rebuilt-world").join());
        assertFalse(components(inC).containsKey("minecraft:map_id"));
        assertEquals(original.data(), PIPELINE.decodeAsync(inC, OWNER).join().data());
    }

    @Test
    void returnToOwnerRemovesOnlyMapMetadataFromCustomData() {
        MapFlowTestSupport.Storage storage = new MapFlowTestSupport.Storage();
        storage.current = new StoredMap(new MapIdentity(new MapSource(OWNER, 7), -1), MapFlowTestSupport.map(4).data());
        this.nativeMaps.sourcePresent(true);
        MapReceiver receiver = this.nativeMaps.receiver(storage, new MapFlowTestSupport.Shared(), OWNER, Runnable::run, Runnable::run, LOGGER);
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(new HideMapHandler(), new SyncMapHandler(receiver)), LOGGER);
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
            Snapshot compiled = pipeline.encodeAsync(original, type, OWNER, nativeMapId -> Map.<Integer, CompletableFuture<StoredMap>>of(7, CompletableFuture.completedFuture(storage.current)).get(nativeMapId)).join();
            assertEquals(type.name(), marker(compiled).getString("map-type"));
            assertEquals(original.data(), pipeline.decodeAsync(compiled, OWNER).join().data());
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

        Snapshot compiled = PIPELINE.encodeAsync(original, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();
        for (Tag value : compiled.data().values()) {
            ListTag items = ((CompoundTag) value).getList("items");
            CompoundTag hiddenBundle = items.getCompound(0).getCompound("components").getList("minecraft:container").getCompound(0).getCompound("item");
            assertFalse(hiddenBundle.getCompound("components").getList("minecraft:bundle_contents").getCompound(1).getCompound("components").containsKey("minecraft:map_id"));
            assertFalse(items.getCompound(1).getCompound("components").getList("minecraft:charged_projectiles").getCompound(0).getCompound("components").containsKey("minecraft:map_id"));
            assertFalse(items.getCompound(2).getCompound("components").getCompound("minecraft:use_remainder").getCompound("components").containsKey("minecraft:map_id"));
        }
        assertEquals(original.data(), PIPELINE.decodeAsync(compiled, OWNER).join().data());
    }

    @Test
    void disabledTypesAndItemLikeCustomDataAreNotCompiled() {
        Snapshot original = snapshot(map(7));
        MapPipeline disabled = new MapPipeline(new DataRegistry(), List.of(new HideMapHandler()), LOGGER);
        assertSame(original, disabled.encodeAsync(original, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join());
        Snapshot unknown = new Snapshot(meta("A"), Map.of(DataKey.of("other", "inventory"), original.data(InventoryDataType.INVENTORY)));
        assertSame(unknown, PIPELINE.encodeAsync(unknown, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join());

        CompoundTag stone = item("minecraft:stone");
        CompoundTag custom = NBT.createCompound();
        custom.put("display_example", map(7));
        stone.getCompound("components").put("minecraft:custom_data", custom);
        Snapshot customSnapshot = snapshot(stone);
        assertSame(customSnapshot, PIPELINE.encodeAsync(customSnapshot, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join());
    }

    @Test
    void decodeDoesNotGuessTheOwnerOfUncompiledItems() {
        Snapshot original = snapshot(map(7));
        assertSame(original, PIPELINE.decodeAsync(original, "B-world-2").join());
        Snapshot idless = snapshot(item("minecraft:filled_map"));
        assertSame(idless, PIPELINE.encodeAsync(idless, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join());
        assertSame(idless, PIPELINE.decodeAsync(idless, OWNER).join());
    }

    @Test
    void malformedOriginMetadataKeepsTheOriginalMap() {
        Snapshot compiled = PIPELINE.encodeAsync(snapshot(map(7)), MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();
        CompoundTag brokenItem = firstItem(compiled).copy();
        brokenItem.getCompound("components").getCompound("minecraft:custom_data").getCompound("sparrow-sync").remove("origin-id");
        Snapshot broken = snapshot(brokenItem);
        assertSame(broken, PIPELINE.decodeAsync(broken, OWNER).join());
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
            public CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull IntFunction<CompletableFuture<StoredMap>> captured) {
                calls[0]++;
                assertEquals(new MapOrigin(MapType.HIDE, OWNER, 7), origin);
                return new HideMapHandler().compileAsync(components, origin, captured);
            }

            @Override
            @NotNull
            public CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
                calls[1]++;
                assertEquals(new MapOrigin(MapType.HIDE, OWNER, 7), origin);
                assertEquals(calls[1] == 1 ? "B-world-2" : OWNER, ownerId);
                return new HideMapHandler().decodeAsync(components, origin, ownerId);
            }
        };
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(handler), LOGGER);
        Snapshot compiled = pipeline.encodeAsync(snapshot(map(7)), MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();
        pipeline.encodeAsync(compiled, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();
        pipeline.decodeAsync(compiled, "B-world-2").join();
        assertArrayEquals(new int[]{1, 1}, calls);
        pipeline.decodeAsync(compiled, OWNER).join();
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
            public CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull IntFunction<CompletableFuture<StoredMap>> captured) {
                if (origin.id() == 7) {
                    throw new IllegalStateException("compile failure");
                }
                return new HideMapHandler().compileAsync(components, origin, captured);
            }

            @Override
            @NotNull
            public CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
                if (origin.id() == 8) {
                    throw new IllegalStateException("decode failure");
                }
                return new HideMapHandler().decodeAsync(components, origin, ownerId);
            }
        };
        MapPipeline pipeline = new MapPipeline(REGISTRY, List.of(failing), logger);
        CompoundTag inventory = NBT.createCompound();
        ListTag originalItems = list(map(7), map(8), map(9));
        inventory.put("items", originalItems);
        Snapshot original = new Snapshot(meta("A"), Map.of(InventoryDataType.INVENTORY, inventory));
        try {
            Snapshot compiled = pipeline.encodeAsync(original, MapType.HIDE, OWNER, nativeMapId -> { throw new AssertionError("unexpected source publication"); }).join();
            ListTag compiledItems = ((CompoundTag) compiled.data(InventoryDataType.INVENTORY)).getList("items");
            assertSame(originalItems.get(0), compiledItems.get(0));
            assertFalse(compiledItems.getCompound(1).getCompound("components").containsKey("minecraft:map_id"));
            Snapshot decoded = pipeline.decodeAsync(compiled, OWNER).join();
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
