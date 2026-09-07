package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.map.handler.SyncMapHandler;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
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

class MapSyncLifecycleTest {
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
        SnapshotService snapshots = new SnapshotService(plugin);
        NmsPlayerFixture.set(SnapshotService.class, snapshots, "mapSync", maps);

        CompletableFuture<Integer> receiving = receiver.receive(IDENTITY);
        worker.runAll();
        assertFalse(receiving.isDone());
        snapshots.stopMapReceiving();
        assertTrue(receiving.isCompletedExceptionally());
        // 最终保存仍可在停接收后发布来源内容, 编译等待完整发布链.
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
        CompletableFuture<Snapshot> compiling = maps.compileAsync(original, new MapSyncService.Capture(MapType.SYNC, Map.of(1, publication)));
        assertFalse(compiling.isDone());
        if (publishedInTime) {
            worker.runAll();
            assertEquals(map(2), publication.join());
        }
        snapshots.finishMapPublishing(0, TimeUnit.NANOSECONDS);
        Snapshot compiled = compiling.join();
        if (publishedInTime) {
            CompoundTag encoded = ((CompoundTag) compiled.data(InventoryDataType.INVENTORY)).getList("items").getCompound(0).getCompound("components");
            assertEquals(-1, encoded.getInt("minecraft:map_id"));
            assertEquals(List.of(2), shared.writes);
        } else {
            // 期限结束释放原快照, 后续保存或 stash 继续取得原生地图 ID.
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
        snapshots.stopMapReceiving();
        snapshots.finishMapPublishing(0, TimeUnit.NANOSECONDS);
        assertEquals(publishedInTime ? 0 : 1, warnings.size());
    }
}
