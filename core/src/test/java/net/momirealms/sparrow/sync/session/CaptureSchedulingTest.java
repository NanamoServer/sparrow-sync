package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.sync.map.MapOrigin;
import net.momirealms.sparrow.sync.map.MapPipeline;
import net.momirealms.sparrow.sync.map.StoredMap;
import net.momirealms.sparrow.sync.map.handler.MapHandler;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.event.SnapshotSaveEvent;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CaptureSchedulingTest {
    private final RecordingType sync = new RecordingType("sync", false);
    private final RecordingType async = new RecordingType("async", true);
    private final List<Snapshot> written = new CopyOnWriteArrayList<>();
    private final List<SnapshotSaveEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseWorker = new CountDownLatch(1);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private CraftPlayer player;
    private PlayerSerialExecutor executor;
    private SnapshotService service;
    private SessionManager sessions;
    private PlayerSession session;
    private Object previousServer;
    private Object previousConfig;
    private Object previousServerConfig;
    private volatile boolean cancelEvent;

    @BeforeEach
    void setUp() throws Exception {
        this.player = NmsPlayerFixture.create();
        this.previousConfig = replace(PluginConfig.class, "config", new PluginConfig.ConfigDefinition());
        this.previousServerConfig = replace(ServerConfig.class, "config", new ServerConfig.ConfigDefinition());
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(), new Class<?>[]{PluginManager.class}, (proxy, method, args) -> {
            if (!method.getName().equals("callEvent")) throw new AssertionError(method.getName());
            assertSame(this.worker.get(), Thread.currentThread());
            SnapshotSaveEvent event = (SnapshotSaveEvent) args[0];
            assertTrue(event.isAsynchronous());
            event.setCancelled(this.cancelEvent);
            this.events.add(event);
            return null;
        });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> {
            if (method.getName().equals("getPluginManager")) return manager;
            throw new AssertionError(method.getName());
        });
        this.previousServer = replace(Bukkit.class, "server", server);
        PluginLogger console = (PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (proxy, method, args) -> null);
        SyncLogger logger = new SyncLogger(console);
        this.executor = new PlayerSerialExecutor(console, 1);
        CountDownLatch blocked = new CountDownLatch(1);
        this.executor.submit(this.player.getUniqueId(), () -> {
            this.worker.set(Thread.currentThread());
            blocked.countDown();
            try {
                if (!this.releaseWorker.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker not released");
            } catch (InterruptedException exception) {
                throw new AssertionError(exception);
            }
        });
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        DataRegistry registry = new DataRegistry();
        registry.register(this.sync);
        registry.register(this.async);
        registry.freeze();
        assertEquals(this.sync.key(), registry.keyAt(registry.syncCaptureSlots()[0]));
        assertEquals(this.async.key(), registry.keyAt(registry.asyncCaptureSlots()[0]));
        PlayerDataPipeline pipeline = new PlayerDataPipeline(null);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "dataRegistry", registry);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "logger", logger);
        StorageProvider storage = (StorageProvider) Proxy.newProxyInstance(StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (proxy, method, args) -> {
            if (!method.getName().equals("saveSnapshotOutcome")) throw new AssertionError(method.getName());
            this.written.add((Snapshot) args[0]);
            return new CompletableFuture<>();
        });
        this.service = new SnapshotService(null);
        NmsPlayerFixture.set(SnapshotService.class, this.service, "playerDataPipeline", pipeline);
        NmsPlayerFixture.set(SnapshotService.class, this.service, "serialExecutor", this.executor);
        NmsPlayerFixture.set(SnapshotService.class, this.service, "logger", logger);
        NmsPlayerFixture.set(SnapshotService.class, this.service, "writer", new SnapshotWriter(logger, storage, null, this.executor));
        this.sessions = new SessionManager(null);
        NmsPlayerFixture.set(SessionManager.class, this.sessions, "snapshotService", this.service);
        this.session = this.sessions.tryOpen(this.player.getUniqueId(), this.player.getName(), ConnectionFixture.create());
        this.session.transition(SessionState.ACTIVE);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.releaseWorker.countDown();
        if (this.executor != null) this.executor.shutdown(3, TimeUnit.SECONDS);
        replace(Bukkit.class, "server", this.previousServer);
        replace(PluginConfig.class, "config", this.previousConfig);
        replace(ServerConfig.class, "config", this.previousServerConfig);
    }

    @Test
    void mapPreparationDelaysSnapshotHandoffAndKeepsPlayerSubmissionOrder() throws Exception {
        Field loggerField = SnapshotService.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        SyncLogger logger = (SyncLogger) loggerField.get(this.service);
        DataRegistry registry = new DataRegistry();
        AtomicInteger id = new AtomicInteger(1);
        registry.register(new PlayerDataType<Integer>() {
            @Override
            @NotNull
            public DataKey key() { return InventoryDataType.INVENTORY; }
            @Override
            @NotNull
            public StorageFormat storage() { return StorageFormat.BINARY; }
            @Override
            @NotNull
            public Integer capture(@NotNull Player player, @NotNull CaptureMode mode) { return id.getAndIncrement(); }
            @Override
            @NotNull
            public Tag encode(@NotNull Integer value) {
                CompoundTag components = NBT.createCompound();
                components.putInt("minecraft:map_id", value);
                CompoundTag item = NBT.createCompound();
                item.putString("id", "minecraft:filled_map");
                item.put("components", components);
                CompoundTag root = NBT.createCompound();
                ListTag items = NBT.createList();
                items.add(item);
                root.put("items", items);
                return root;
            }
            @Override
            @NotNull
            public Integer decode(@NotNull Tag tag, int version) { throw new AssertionError("unexpected decode"); }
            @Override
            public void apply(@NotNull Player player, @NotNull Integer value) { throw new AssertionError("unexpected apply"); }
        });
        registry.freeze();
        PlayerDataPipeline data = new PlayerDataPipeline(null);
        NmsPlayerFixture.set(PlayerDataPipeline.class, data, "dataRegistry", registry);
        NmsPlayerFixture.set(PlayerDataPipeline.class, data, "logger", logger);
        NmsPlayerFixture.set(SnapshotService.class, this.service, "playerDataPipeline", data);
        CompletableFuture<CompoundTag> firstMap = new CompletableFuture<>();
        MapHandler handler = new MapHandler() {
            @Override
            @NotNull
            public MapType type() { return MapType.SYNC; }
            @Override
            @NotNull
            public CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull Map<Integer, CompletableFuture<StoredMap>> captured) {
                if (origin.id() == 1) return firstMap;
                CompoundTag result = components.copy();
                result.putInt("minecraft:map_id", -2);
                return CompletableFuture.completedFuture(result);
            }
        };
        MapPipeline maps = new MapPipeline(registry, List.of(handler), logger);
        Class<?> mapSaveType = Class.forName(SnapshotService.class.getName() + "$MapSave");
        Constructor<?> mapSave = mapSaveType.getDeclaredConstructors()[0];
        mapSave.setAccessible(true);
        Object preparation = mapSave.newInstance(maps, MapType.SYNC, "A-world", Map.of());
        Class<?> contextType = Class.forName(SnapshotService.class.getName() + "$SaveContext");
        Constructor<?> context = contextType.getDeclaredConstructors()[0];
        context.setAccessible(true);
        Class<?> requestType = Class.forName(SnapshotService.class.getName() + "$SaveRequest");
        Constructor<?> request = requestType.getDeclaredConstructors()[0];
        request.setAccessible(true);
        Method encode = SnapshotService.class.getDeclaredMethod("encodeAndSubmit", contextType, PlayerDataPipeline.CaptureResult.Ready.class, requestType, mapSaveType);
        encode.setAccessible(true);
        CountDownLatch encoded = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 1; i <= 2; i++) {
            SnapshotMeta meta = new SnapshotMeta(UUID.randomUUID(), this.player.getUniqueId(), i, SaveCause.WORLD_SAVE, false, "A", 4440);
            Object saveContext = context.newInstance(meta, this.player.getName(), Map.of());
            Object saveRequest = request.newInstance(this.service);
            PlayerDataPipeline.CaptureResult.Ready captured = (PlayerDataPipeline.CaptureResult.Ready) data.capture(this.player, CaptureMode.SYNC);
            this.executor.submit(this.player.getUniqueId(), () -> {
                try {
                    encode.invoke(this.service, saveContext, captured, saveRequest, preparation);
                } catch (ReflectiveOperationException exception) {
                    failure.set(exception);
                } finally {
                    encoded.countDown();
                }
            });
        }
        this.releaseWorker.countDown();
        assertTrue(encoded.await(2, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertTrue(this.written.isEmpty());
        assertEquals(2, this.pendingHandoffs());
        CompoundTag completed = NBT.createCompound();
        completed.putInt("minecraft:map_id", -1);
        firstMap.complete(completed);
        assertTrue(this.service.sealAndAwaitHandoffs(2, TimeUnit.SECONDS));
        assertEquals(List.of(-1, -2), this.written.stream().map(snapshot -> ((CompoundTag) snapshot.data(InventoryDataType.INVENTORY)).getList("items").getCompound(0).getCompound("components").getInt("minecraft:map_id")).toList());
    }

    @Test
    void worldSavePreCapturesBeforeDeathAndDoesNotWaitForWorkerOrDatabase() throws Exception {
        this.sync.value.set(1);
        this.async.value.set(1);
        CompletableFuture<SnapshotSaveResult> world = this.sessions.captureLaterAndSave(this.session, this.player, SaveCause.WORLD_SAVE);
        assertEquals(List.of(CaptureMode.SYNC), this.sync.modes);
        assertTrue(this.async.modes.isEmpty());
        this.sync.value.set(2);
        this.async.value.set(2);
        CompletableFuture<SnapshotSaveResult> death = this.sessions.captureNowAndSave(this.session, this.player, SaveCause.DEATH);
        assertEquals(List.of(CaptureMode.SYNC, CaptureMode.SYNC), this.sync.modes);
        assertEquals(List.of(CaptureMode.SYNC), this.async.modes);
        assertEquals(2, this.pendingHandoffs());
        assertTrue(this.written.isEmpty());
        this.sync.value.set(3);
        this.async.value.set(3);

        this.releaseWorker.countDown();
        assertTrue(this.service.sealAndAwaitHandoffs(2, TimeUnit.SECONDS));

        assertEquals(List.of(SaveCause.WORLD_SAVE, SaveCause.DEATH), this.written.stream().map(snapshot -> snapshot.meta().cause()).toList());
        assertTrue(this.written.get(0).meta().timestamp() < this.written.get(1).meta().timestamp());
        assertEquals("1", this.written.get(0).data().get(this.sync.key()).getAsString());
        assertEquals("3", this.written.get(0).data().get(this.async.key()).getAsString());
        assertEquals("2", this.written.get(1).data().get(this.async.key()).getAsString());
        assertEquals(List.of(CaptureMode.SYNC, CaptureMode.ASYNC), this.async.modes);
        assertSame(Thread.currentThread(), this.sync.threads.getFirst());
        assertSame(Thread.currentThread(), this.async.threads.getFirst());
        assertSame(this.worker.get(), this.async.threads.getLast());
        assertFalse(world.isDone());
        assertFalse(death.isDone());
    }

    @Test
    void finalOfflineTaskCapturesAndEncodesOnSameWorkerAndSealedSessionRejectsLateNotice() throws Exception {
        this.session.transition(SessionState.SAVING);
        assertNull(this.sessions.captureLaterAndSave(this.session, this.player, SaveCause.WORLD_SAVE));
        assertEquals(0, this.pendingHandoffs());
        this.service.captureOfflineAndSave(this.player, SaveCause.DISCONNECT, this.session.retainedData());
        assertTrue(this.sync.modes.isEmpty());
        assertTrue(this.async.modes.isEmpty());
        this.releaseWorker.countDown();
        assertTrue(this.service.sealAndAwaitHandoffs(2, TimeUnit.SECONDS));
        assertEquals(List.of(CaptureMode.OFFLINE), this.sync.modes);
        assertEquals(List.of(CaptureMode.OFFLINE), this.async.modes);
        assertSame(this.worker.get(), this.sync.threads.getFirst());
        assertSame(this.worker.get(), this.sync.encodeThread);
        assertEquals(SaveCause.DISCONNECT, this.written.getFirst().meta().cause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"syncCapture", "asyncCapture", "encode", "cancel"})
    void failuresAndCancellationFinishHandoffs(String stage) throws Exception {
        this.sync.captureFails = stage.equals("syncCapture");
        this.async.captureFails = stage.equals("asyncCapture");
        this.async.encodeFails = stage.equals("encode");
        this.cancelEvent = stage.equals("cancel");
        CompletableFuture<SnapshotSaveResult> result = this.sessions.captureLaterAndSave(this.session, this.player, SaveCause.WORLD_SAVE);
        this.releaseWorker.countDown();
        assertTrue(this.service.sealAndAwaitHandoffs(2, TimeUnit.SECONDS));
        if (this.cancelEvent) {
            assertInstanceOf(SnapshotSaveResult.Cancelled.class, result.get(2, TimeUnit.SECONDS));
        } else {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        }
        assertTrue(this.written.isEmpty());
        assertEquals(0, this.pendingHandoffs());
    }

    private int pendingHandoffs() throws Exception {
        Field handoffs = SnapshotService.class.getDeclaredField("handoffs");
        handoffs.setAccessible(true);
        Object tracker = handoffs.get(this.service);
        Field pending = SnapshotHandoffTracker.class.getDeclaredField("pending");
        pending.setAccessible(true);
        synchronized (tracker) {
            return pending.getInt(tracker);
        }
    }

    private static Object replace(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }

    private static final class RecordingType implements PlayerDataType<String> {
        private final DataKey key;
        private final boolean async;
        private final AtomicInteger value = new AtomicInteger();
        private final List<CaptureMode> modes = new CopyOnWriteArrayList<>();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private volatile Thread encodeThread;
        private boolean captureFails;
        private boolean encodeFails;

        private RecordingType(String key, boolean async) {
            this.key = DataKey.of("test", key);
            this.async = async;
        }

        @Override
        @NotNull
        public DataKey key() {
            return this.key;
        }

        @Override
        @NotNull
        public StorageFormat storage() {
            return StorageFormat.STRUCTURED;
        }

        @Override
        public boolean critical() {
            return true;
        }

        @Override
        public boolean supportsAsyncCapture() {
            return this.async;
        }

        @Override
        @NotNull
        public String capture(@NotNull Player player, @NotNull CaptureMode mode) {
            this.modes.add(mode);
            this.threads.add(Thread.currentThread());
            if (this.captureFails) throw new IllegalStateException("capture failed");
            return Integer.toString(this.value.get());
        }

        @Override
        @NotNull
        public Tag encode(@NotNull String value) {
            this.encodeThread = Thread.currentThread();
            if (this.encodeFails) throw new IllegalStateException("encode failed");
            return NBT.createString(value);
        }

        @Override
        @NotNull
        public String decode(@NotNull Tag data, int mcDataVersion) {
            return data.getAsString();
        }

        @Override
        public void apply(@NotNull Player player, @NotNull String value) {
        }
    }
}
