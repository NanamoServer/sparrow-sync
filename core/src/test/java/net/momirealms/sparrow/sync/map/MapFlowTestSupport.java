package net.momirealms.sparrow.sync.map;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.momirealms.sparrow.sync.map.cache.MapCache;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapArchiveRecord;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.RegionExecutor;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class MapFlowTestSupport {
    static final MapSource SOURCE = new MapSource("A-world", 1);
    static final MapIdentity IDENTITY = new MapIdentity(SOURCE, -1);

    static void scheduler(Executor worker, Executor nativeThread) {
        Object sync = Proxy.newProxyInstance(RegionExecutor.class.getClassLoader(), new Class<?>[]{RegionExecutor.class}, (proxy, method, arguments) -> {
            if (!method.getName().equals("execute")) {
                throw new AssertionError("unexpected region scheduler call: " + method.getName());
            }
            nativeThread.execute((Runnable) arguments[0]);
            return null;
        });
        Object scheduler = Proxy.newProxyInstance(SchedulerAdapter.class.getClassLoader(), new Class<?>[]{SchedulerAdapter.class}, (proxy, method, arguments) -> switch (method.getName()) {
            case "async" -> worker;
            case "sync" -> sync;
            default -> throw new AssertionError("unexpected scheduler call: " + method.getName());
        });
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "scheduler", scheduler);
        NmsPlayerFixture.set(SparrowSync.class, null, "instance", plugin);
    }

    static final class PluginInstance implements AfterEachCallback {
        private final SparrowSync previous = SparrowSync.instance();

        @Override
        public void afterEach(ExtensionContext context) {
            NmsPlayerFixture.set(SparrowSync.class, null, "instance", this.previous);
        }
    }

    static StoredMap map(int pixel) {
        return new StoredMap(IDENTITY, new MapData(4440, MapDataTest.content(pixel)));
    }

    static String dimension(MapItemSavedData data) {
        return Level.RESOURCE_KEY_CODEC.encodeStart(NBTOps.INSTANCE, data.dimension).getOrThrow().getAsString();
    }

    static SyncLogger logger(List<String> warnings) {
        return new SyncLogger(new PluginLogger() {
            @Override
            public void info(String s) { }
            @Override
            public void warn(String s) { warnings.add(s); }
            @Override
            public void warn(String s, Throwable t) { warnings.add(s); }
            @Override
            public void error(String s) { throw new AssertionError(s); }
            @Override
            public void error(String s, Throwable t) { throw new AssertionError(s, t); }
        });
    }

    // 使用真实 NMS 地图对象验证线程切换后的更新, 内存存储预置已加载副本.
    static final class NativeMaps {
        private static final HolderLookup.Provider REGISTRIES = bootstrap();
        final MinecraftServer server = NmsPlayerFixture.allocate(DedicatedServer.class);
        final ServerLevel level = NmsPlayerFixture.allocate(ServerLevel.class);
        final NativeMapAdapter adapter = new NativeMapAdapter(REGISTRIES, 4440);
        final DimensionDataStorage storage = NmsPlayerFixture.allocate(DimensionDataStorage.class);
        final MapIdentity identity;
        final MapItemSavedData replica;
        final List<StoredMap> updates = new ArrayList<>();

        NativeMaps() {
            this(IDENTITY);
        }

        NativeMaps(MapIdentity identity) {
            this.identity = identity;
            NmsPlayerFixture.set(ServerLevel.class, this.level, "server", this.server);
            NmsPlayerFixture.set(MinecraftServer.class, this.server, "levels", Map.of(Level.OVERWORLD, this.level));
            NmsPlayerFixture.set(DimensionDataStorage.class, this.storage, "cache", new HashMap<>());
            ServerChunkCache chunks = NmsPlayerFixture.allocate(ServerChunkCache.class);
            NmsPlayerFixture.set(ServerChunkCache.class, chunks, "dataStorage", this.storage);
            NmsPlayerFixture.set(ServerLevel.class, this.level, "chunkSource", chunks);
            try {
                this.replica = this.adapter.prepareReplica(identity, new MapData(4440, MapDataTest.content(0)));
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
            this.storage.cache.put(MapItemSavedData.type(new MapId(identity.globalId())), Optional.of(this.replica));
            this.storage.cache.put(MapItemSavedData.type(new MapId(identity.source().id())), Optional.empty());
            this.storage.cache.put(MapItemSavedData.type(new MapId(-8)), Optional.empty());
            this.storage.cache.put(MapItemSavedData.type(new MapId(-2)), Optional.empty());
        }

        void sourcePresent(boolean present) {
            this.storage.cache.put(MapItemSavedData.type(new MapId(this.identity.source().id())), present
                    ? Optional.of(MapItemSavedData.createForClient((byte) 0, false, Level.OVERWORLD)) : Optional.empty());
        }

        MapReceiver receiver(MapStorage storage, MapCache shared, String ownerId, Executor worker, Executor nativeThread, SyncLogger logger) {
            Executor recording = task -> nativeThread.execute(() -> {
                byte before = this.replica.colors[0];
                task.run();
                if (before != this.replica.colors[0]) {
                    this.updates.add(new StoredMap(this.identity, new MapData(4440, MapDataTest.content(this.replica.colors[0] & 255))));
                }
            });
            MapReceiver receiver = new MapReceiver(storage, shared, this.adapter, this.server, ownerId, logger);
            scheduler(worker, recording);
            return receiver;
        }

        private static HolderLookup.Provider bootstrap() {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            BukkitProxy.init("1.21.8", List.of("paper"));
            return VanillaRegistries.createLookup();
        }
    }

    static final class Tasks implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(@NotNull Runnable task) {
            this.tasks.add(task);
        }

        synchronized void runAll() {
            while (!this.tasks.isEmpty()) this.tasks.remove().run();
        }
    }

    static class Storage implements MapStorage {
        StoredMap current;
        int registrations;
        int reads;
        final List<Integer> writes = new ArrayList<>();

        @Override
        public CompletableFuture<List<MapArchiveRecord>> scan(int beforeId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Long> sequence() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> importSequence(long sequence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> importMap(MapArchiveRecord map) {
            throw new UnsupportedOperationException();
        }

        @Override
        @NotNull
        public CompletableFuture<Optional<StoredMap>> find(int globalId) {
            this.reads++;
            return CompletableFuture.completedFuture(Optional.ofNullable(this.current));
        }

        @Override
        @NotNull
        public CompletableFuture<StoredMap> register(@NotNull MapSource source, @NotNull MapData initial) {
            this.registrations++;
            if (this.current == null) this.current = new StoredMap(IDENTITY, initial);
            return CompletableFuture.completedFuture(this.current);
        }

        @Override
        @NotNull
        public CompletableFuture<Void> update(@NotNull MapIdentity identity, @NotNull MapData data) {
            this.current = new StoredMap(identity, data);
            this.writes.add(data.getTag().getByteArray("colors")[0] & 255);
            return CompletableFuture.completedFuture(null);
        }
    }

    static class Shared implements MapCache {
        final Map<Integer, StoredMap> contents = new HashMap<>();
        final List<Integer> writes = new ArrayList<>();
        int reads;
        int touches;

        @Override
        @NotNull
        public CompletableFuture<Optional<StoredMap>> find(int globalId) {
            this.reads++;
            return CompletableFuture.completedFuture(Optional.ofNullable(this.contents.get(globalId)));
        }

        @Override
        @NotNull
        public CompletableFuture<Void> publish(@NotNull StoredMap map) {
            this.contents.put(map.identity().globalId(), map);
            this.writes.add(map.data().getTag().getByteArray("colors")[0] & 255);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        @NotNull
        public CompletableFuture<Boolean> touch(int globalId) {
            this.touches++;
            return CompletableFuture.completedFuture(this.contents.containsKey(globalId));
        }
    }
}
