package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.map.handler.SyncMapHandler;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static net.momirealms.sparrow.sync.map.MapFlowTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(PluginConfigExtension.class)
class MapSyncLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shutdownRenewsTransitMapsAfterStoppingReceiving(boolean renewalSucceeds) {
        CompletableFuture<Boolean> renewal = new CompletableFuture<>();
        Shared shared = new Shared() {
            @Override
            @NotNull
            public CompletableFuture<Boolean> touch(int globalId) {
                assertEquals(-1, globalId);
                this.touches++;
                return renewal;
            }
        };
        NativeMaps nativeMaps = new NativeMaps();
        List<String> warnings = new ArrayList<>();
        SyncLogger logger = logger(warnings);
        MapReceiver receiver = nativeMaps.receiver(new Storage(), shared, "B-world", Runnable::run, Runnable::run, logger);
        DataRegistry registry = new DataRegistry();
        registry.register(NmsPlayerFixture.allocate(InventoryDataType.class));
        registry.freeze();
        MapPipeline pipeline = new MapPipeline(registry, List.of(new SyncMapHandler(receiver)), logger);
        MapSyncService maps = NmsPlayerFixture.allocate(MapSyncService.class);
        NmsPlayerFixture.set(MapSyncService.class, maps, "receiver", receiver);
        maps.stopReceiving();

        CompoundTag marker = NBT.createCompound();
        marker.putString("map-type", "SYNC");
        marker.putString("origin-server", "A-world");
        marker.putInt("origin-id", 1);
        CompoundTag customData = NBT.createCompound();
        customData.put("sparrow-sync", marker);
        ListTag items = NBT.createList();
        for (int i = 0; i < 2; i++) {
            CompoundTag components = NBT.createCompound();
            components.putInt("minecraft:map_id", -1);
            components.put("minecraft:custom_data", customData);
            CompoundTag item = NBT.createCompound();
            item.putString("id", "minecraft:filled_map");
            item.put("components", components);
            items.add(item);
        }
        CompoundTag inventory = NBT.createCompound();
        inventory.put("items", items);
        Snapshot original = new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1, SaveCause.SHUTDOWN, false, "B", 4440), Map.of(InventoryDataType.INVENTORY, inventory));
        CompletableFuture<Snapshot> compiling = pipeline.encodeAsync(original, MapType.SYNC, "B-world", nativeId -> { throw new AssertionError("unexpected source publication"); });
        assertEquals(1, shared.touches);
        assertFalse(compiling.isDone());
        if (renewalSucceeds) {
            renewal.complete(true);
        } else {
            renewal.completeExceptionally(new IllegalStateException("Redis unavailable"));
        }
        assertSame(original, compiling.join());
        assertEquals(renewalSucceeds ? 0 : 2, warnings.size());
        assertTrue(receiver.receive(IDENTITY).isCompletedExceptionally());
        receiver.observe(-1);
        receiver.refresh(-1);
        assertTrue(nativeMaps.updates.isEmpty());
        assertEquals(0, shared.reads);
        assertTrue(shared.writes.isEmpty());
        pipeline.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shutdownStopsReceivingBeforeFinishingPublicationOrReturningTheOriginalSnapshot(boolean publishedInTime) {
        NativeMaps nativeMaps = new NativeMaps();
        Storage storage = new Storage();
        storage.current = map(1);
        Shared shared = new Shared();
        Tasks worker = new Tasks();
        Tasks nativeThread = new Tasks();
        List<String> warnings = new ArrayList<>();
        SyncLogger logger = logger(warnings);
        MapReceiver receiver = nativeMaps.receiver(storage, shared, "A-world", worker, nativeThread, logger);
        MapPublisher publisher = new MapPublisher(storage, shared, "A-world", worker);
        DataRegistry registry = new DataRegistry();
        registry.register(NmsPlayerFixture.allocate(InventoryDataType.class));
        registry.freeze();
        MapPipeline pipeline = new MapPipeline(registry, List.of(new SyncMapHandler(receiver)), logger);
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", logger);
        MapSyncService maps = NmsPlayerFixture.allocate(MapSyncService.class);
        NmsPlayerFixture.set(MapSyncService.class, maps, "plugin", plugin);
        NmsPlayerFixture.set(MapSyncService.class, maps, "ownerId", "A-world");
        NmsPlayerFixture.set(MapSyncService.class, maps, "receiver", receiver);
        NmsPlayerFixture.set(MapSyncService.class, maps, "publisher", publisher);
        NmsPlayerFixture.set(MapSyncService.class, maps, "pipeline", pipeline);

        CompletableFuture<Integer> receiving = receiver.receive(IDENTITY);
        worker.runAll();
        assertFalse(receiving.isDone());
        maps.stopReceiving();
        assertTrue(receiving.isCompletedExceptionally());
        CompletableFuture<StoredMap> publication = publisher.publish(SOURCE, map(2).data());
        CompoundTag components = NBT.createCompound();
        components.putInt("minecraft:map_id", 1);
        CompoundTag item = NBT.createCompound();
        item.putString("id", "minecraft:filled_map");
        item.put("components", components);
        ListTag items = NBT.createList();
        items.add(item);
        CompoundTag inventory = NBT.createCompound();
        inventory.put("items", items);
        Snapshot original = new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1, SaveCause.SHUTDOWN, false, "A", 4440), Map.of(InventoryDataType.INVENTORY, inventory));
        CompletableFuture<Snapshot> compiling = pipeline.encodeAsync(original, MapType.SYNC, "A-world", nativeId -> publication);
        assertFalse(compiling.isDone());
        if (publishedInTime) {
            worker.runAll();
            assertEquals(map(2), publication.join());
        }
        maps.finishPublishing(0, TimeUnit.NANOSECONDS);
        Snapshot compiled = compiling.join();
        if (publishedInTime) {
            CompoundTag encoded = ((CompoundTag) compiled.data(InventoryDataType.INVENTORY)).getList("items").getCompound(0).getCompound("components");
            assertEquals(-1, encoded.getInt("minecraft:map_id"));
            assertEquals(List.of(2), shared.writes);
        } else {
            assertSame(original, compiled);
            assertTrue(shared.writes.isEmpty());
        }
        assertEquals(publishedInTime ? 0 : 1, warnings.size());
        assertEquals(1, components.getInt("minecraft:map_id"));
        assertTrue(publisher.publish(SOURCE, map(3).data()).isCompletedExceptionally());
        assertTrue(receiver.receive(IDENTITY).isCompletedExceptionally());
        maps.observe(-1);
        maps.invalidate(-1);
        worker.runAll();
        nativeThread.runAll();
        assertTrue(nativeMaps.updates.isEmpty());
        assertEquals(publishedInTime ? map(2) : map(1), storage.current);
        maps.stopReceiving();
        maps.finishPublishing(0, TimeUnit.NANOSECONDS);
        assertEquals(publishedInTime ? 0 : 1, warnings.size());
    }
}
