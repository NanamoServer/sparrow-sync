package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.map.MapPipeline;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.map.data.MapOrigin;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.handler.MapHandler;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotLoadResult;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(PluginConfigExtension.class)
class SnapshotPreparationTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void waitsForMapPreparationBeforeDecodingAndKeepsOriginalForRestore(boolean mapSucceeds) throws Exception {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        AtomicInteger decodes = new AtomicInteger();
        CompletableFuture<CompoundTag> preparedMap = new CompletableFuture<>();
        PluginLogger output = (PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (instance, method, args) -> null);
        SyncLogger logger = new SyncLogger(output);
        DataRegistry registry = new DataRegistry();
        registry.register(new PlayerDataType<Integer>() {
            @Override
            @NotNull
            public DataKey key() { return InventoryDataType.INVENTORY; }
            @Override
            @NotNull
            public Integer capture(@NotNull Player player, @NotNull CaptureMode mode) { throw new AssertionError(); }
            @Override
            @NotNull
            public Tag encode(@NotNull Integer value) { throw new AssertionError(); }
            @Override
            @NotNull
            public Integer decode(@NotNull Tag tag, int version) {
                decodes.incrementAndGet();
                return ((CompoundTag) tag).getList("items").getCompound(0).getCompound("components").getInt("minecraft:map_id");
            }
            @Override
            public void apply(@NotNull Player player, @NotNull Integer value) { throw new AssertionError(); }
        });
        registry.freeze();
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", logger);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataRegistry", registry);
        Executor executor = work::add;
        SchedulerAdapter<?> scheduler = (SchedulerAdapter<?>) Proxy.newProxyInstance(SchedulerAdapter.class.getClassLoader(), new Class<?>[]{SchedulerAdapter.class}, (instance, method, args) -> {
            assertEquals("async", method.getName());
            return executor;
        });
        NmsPlayerFixture.set(SparrowSync.class, plugin, "scheduler", scheduler);
        MapHandler handler = new MapHandler() {
            @Override
            @NotNull
            public MapType type() { return MapType.SYNC; }
            @Override
            @NotNull
            public CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull IntFunction<CompletableFuture<StoredMap>> publish) { throw new AssertionError(); }
            @Override
            @NotNull
            public CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
                assertEquals("local", ownerId);
                return preparedMap;
            }
        };
        MapSyncService maps = new MapSyncService(plugin);
        NmsPlayerFixture.set(MapSyncService.class, maps, "ownerId", "local");
        NmsPlayerFixture.set(MapSyncService.class, maps, "pipeline", new MapPipeline(registry, List.of(handler), logger));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "mapSyncService", maps);
        PlayerDataPipeline pipeline = new PlayerDataPipeline(plugin);
        pipeline.onLoad();
        SnapshotApplier service = new SnapshotApplier(plugin);
        NmsPlayerFixture.set(SnapshotApplier.class, service, "logger", logger);
        NmsPlayerFixture.set(SnapshotApplier.class, service, "playerDataPipeline", pipeline);
        CompoundTag origin = NBT.createCompound();
        origin.putString("map-type", "SYNC");
        origin.putString("origin-server", "remote");
        origin.putInt("origin-id", 4);
        CompoundTag custom = NBT.createCompound();
        custom.put("sparrow-sync", origin);
        CompoundTag components = NBT.createCompound();
        components.putInt("minecraft:map_id", 4);
        components.put("minecraft:custom_data", custom);
        CompoundTag item = NBT.createCompound();
        item.putString("id", "minecraft:filled_map");
        item.put("components", components);
        var items = NBT.createList();
        items.add(item);
        CompoundTag inventory = NBT.createCompound();
        inventory.put("items", items);
        Snapshot original = new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1, SaveCause.COMMAND, false, "remote", 0), Map.of(InventoryDataType.INVENTORY, inventory));
        Method prepare = SnapshotApplier.class.getDeclaredMethod("prepareSnapshot", Snapshot.class, String.class);
        prepare.setAccessible(true);
        CompletableFuture<?> result = (CompletableFuture<?>) prepare.invoke(service, original, "Steve");
        assertFalse(result.isDone());
        assertTrue(work.isEmpty());
        assertEquals(0, decodes.get());
        if (mapSucceeds) {
            CompoundTag local = components.copy();
            local.putInt("minecraft:map_id", -9);
            preparedMap.complete(local);
        } else {
            preparedMap.completeExceptionally(new IllegalStateException("map unavailable"));
        }
        assertFalse(result.isDone());
        assertEquals(0, decodes.get());
        assertEquals(1, work.size());
        work.remove().run();
        SnapshotLoadResult.Ready ready = assertInstanceOf(SnapshotLoadResult.Ready.class, result.join());
        assertSame(original, ready.snapshot());
        assertEquals(mapSucceeds ? -9 : 4, ready.context().pendingValues().get(InventoryDataType.INVENTORY));
        assertEquals(4, components.getInt("minecraft:map_id"));
        assertEquals(1, decodes.get());
    }
}
