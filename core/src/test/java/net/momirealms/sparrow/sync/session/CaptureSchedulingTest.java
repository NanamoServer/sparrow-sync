package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.snapshot.exception.ExceptionHeader;
import net.minecraft.world.item.ItemStack;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.sync.map.data.MapOrigin;
import net.momirealms.sparrow.sync.map.MapPipeline;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.handler.MapHandler;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.event.SnapshotSaveEvent;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.session.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotDecoder;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotStash;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CaptureSchedulingTest {
    @TempDir
    Path directory;
    private final RecordingType sync = new RecordingType("sync", false);
    private final RecordingType async = new RecordingType("async", true);
    private final List<Snapshot> written = new CopyOnWriteArrayList<>();
    private final List<CompletableFuture<StorageProvider.SaveOutcome>> writes = new CopyOnWriteArrayList<>(); // 测试控制数据库确认时刻
    private final List<SnapshotSaveEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseWorker = new CountDownLatch(1);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private CraftPlayer player;
    private PlayerSerialExecutor executor;
    private SnapshotService service;
    private SnapshotSaver saver;
    private SessionManager sessions;
    private PlayerSession session;
    private Object previousServer;
    private Object previousConfig;
    private Object previousServerConfig;
    private volatile boolean cancelEvent;
    private java.util.function.Consumer<SnapshotSaveEvent> eventAction = event -> {}; // 控制监听器返回的时刻
    private Throwable writeFailure; // 模拟任务已经入队后, 存储提交同步抛出的失败

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
            this.eventAction.accept(event);
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
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "decoder", new SnapshotDecoder(registry));
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "logger", logger);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "mapSync", NmsPlayerFixture.allocate(MapSyncService.class));
        StorageProvider storage = (StorageProvider) Proxy.newProxyInstance(StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (proxy, method, args) -> {
            if (method.getName().equals("rotate")) return CompletableFuture.completedFuture(0);
            if (!method.getName().equals("saveSnapshotOutcome")) throw new AssertionError(method.getName());
            if (this.writeFailure != null) {
                throw this.writeFailure;
            }
            this.written.add((Snapshot) args[0]);
            CompletableFuture<StorageProvider.SaveOutcome> write = new CompletableFuture<>();
            this.writes.add(write);
            return write;
        });
        SparrowSync savePlugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, savePlugin, "logger", logger);
        NmsPlayerFixture.set(SparrowSync.class, savePlugin, "playerDataPipeline", pipeline);
        NmsPlayerFixture.set(SparrowSync.class, savePlugin, "playerExecutor", this.executor);
        NmsPlayerFixture.set(SparrowSync.class, savePlugin, "storageProvider", storage);
        this.saver = new SnapshotSaver(savePlugin);
        this.service = new SnapshotService(savePlugin);
        NmsPlayerFixture.set(SnapshotService.class, this.service, "saver", this.saver);
        NmsPlayerFixture.set(SnapshotSaver.class, this.saver, "playerDataPipeline", pipeline);
        NmsPlayerFixture.set(SnapshotSaver.class, this.saver, "serialExecutor", this.executor);
        NmsPlayerFixture.set(SnapshotSaver.class, this.saver, "logger", logger);
        NmsPlayerFixture.set(SnapshotSaver.class, this.saver, "writer", new SnapshotWriter(logger, storage, new SnapshotStash(this.directory, new BinarySnapshotCodec(CompressorRegistry.DEFLATE), logger), this.executor));
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

    /**
     * 用受控地图结果验证原串行任务续行、关闭回退、普通异常及同桶阻塞.
     *
     * @param outcome 地图完成或停服发生的时点
     * @throws Exception 调度、反射装配或文件检查失败时
     */
    @ParameterizedTest
    @ValueSource(strings = {"published", "closed", "stash", "singleFailure", "chainFailure", "buckets", "restore"})
    void mapWaitKeepsSubmissionInTheSameSerialTask(String outcome) throws Exception {
        if (outcome.equals("buckets")) {
            this.releaseWorker.countDown();
            this.executor.shutdown(2, TimeUnit.SECONDS);
            PluginLogger console = (PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (proxy, method, args) -> null);
            this.executor = new PlayerSerialExecutor(console, 2);
            NmsPlayerFixture.set(SnapshotSaver.class, this.saver, "serialExecutor", this.executor);
            this.executor.submit(this.player.getUniqueId(), () -> this.worker.set(Thread.currentThread()));
        }
        NmsPlayerFixture.set(PluginConfig.MapOptions.class, PluginConfig.synchronization$map(), "enabled", true);
        Field loggerField = SnapshotSaver.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        SyncLogger logger = (SyncLogger) loggerField.get(this.saver);
        DataRegistry registry = new DataRegistry();
        AtomicInteger id = new AtomicInteger(1);
        CompletableFuture<Void> secondEncodeRelease = new CompletableFuture<>();
        registry.register(new PlayerDataType<Integer>() {
            @Override
            @NotNull
            public DataKey key() { return InventoryDataType.INVENTORY; }
            @Override
            @NotNull
            public Integer capture(@NotNull Player player, @NotNull CaptureMode mode) { return id.getAndIncrement(); }
            @Override
            @NotNull
            public Tag encode(@NotNull Integer value) {
                if (value == 2 && outcome.equals("stash")) {
                    // 关停中断后队列仍可能进入第二次编码, 保持该正文未发布以固定暂存检查时刻.
                    secondEncodeRelease.join();
                }
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
        NmsPlayerFixture.set(PlayerDataPipeline.class, data, "decoder", new SnapshotDecoder(registry));
        NmsPlayerFixture.set(PlayerDataPipeline.class, data, "logger", logger);
        NmsPlayerFixture.set(SnapshotSaver.class, this.saver, "playerDataPipeline", data);
        CompletableFuture<CompoundTag> firstMap = new CompletableFuture<>();
        CountDownLatch mapStarted = new CountDownLatch(1);
        MapHandler handler = new MapHandler() {
            @Override
            @NotNull
            public MapType type() { return MapType.SYNC; }
            @Override
            @NotNull
            public CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull IntFunction<CompletableFuture<StoredMap>> captured) {
                if (origin.id() == 1) {
                    mapStarted.countDown();
                    return firstMap;
                }
                CompoundTag result = components.copy();
                result.putInt("minecraft:map_id", -2);
                return CompletableFuture.completedFuture(result);
            }
            @Override
            @NotNull
            public CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
                throw new AssertionError("unexpected map decode");
            }
        };
        MapPipeline maps = new MapPipeline(registry, List.of(handler), logger);
        MapSyncService mapSync = NmsPlayerFixture.allocate(MapSyncService.class);
        NmsPlayerFixture.set(MapSyncService.class, mapSync, "pipeline", maps);
        NmsPlayerFixture.set(MapSyncService.class, mapSync, "ownerId", "A-world");
        NmsPlayerFixture.set(PlayerDataPipeline.class, data, "mapSync", mapSync);
        NmsPlayerFixture.set(PluginConfig.MapOptions.class, PluginConfig.synchronization$map(), "type", MapType.SYNC);
        if (outcome.equals("restore")) {
            PlayerDataPipeline.CaptureResult.Ready captured = assertInstanceOf(PlayerDataPipeline.CaptureResult.Ready.class, data.capture(this.player, CaptureMode.SYNC));
            Tag inventory = assertInstanceOf(PlayerDataPipeline.EncodeResult.Ready.class, data.encode(captured)).data().get(InventoryDataType.INVENTORY);
            Snapshot source = new Snapshot(new SnapshotMeta(UUID.randomUUID(), this.player.getUniqueId(), 1, SaveCause.COMMAND, true, "old", 4440),
                    Map.of(InventoryDataType.INVENTORY, inventory, DataKey.of("external", "retained"), NBT.createString("unknown")));
            this.saver.saveRestored(source, this.player.getName());
            this.releaseWorker.countDown();
            this.awaitSubmissions();
            assertEquals(1, mapStarted.getCount(), "RESTORE 原内容不得再次准备或发布地图");
            assertEquals(source.data(), this.written.getFirst().data());
            assertEquals(SaveCause.RESTORE, this.written.getFirst().meta().cause());
            this.finishWrites();
            assertTrue(this.service.sealAndAwaitSaves(2, TimeUnit.SECONDS));
            return;
        }
        CompletableFuture<SnapshotSaveResult> first = this.service.captureNowAndSave(this.player, SaveCause.WORLD_SAVE, Map.of());
        CompletableFuture<SnapshotSaveResult> second = this.service.captureNowAndSave(this.player, SaveCause.WORLD_SAVE, Map.of());
        CountDownLatch afterSaves = new CountDownLatch(1);
        this.executor.submit(this.player.getUniqueId(), () -> {
            if (!outcome.equals("stash")) {
                assertEquals(outcome.equals("chainFailure") ? 1 : 2, this.written.size(), "保存应在各自原任务中提交, 队尾标记不能越过续行");
            }
            afterSaves.countDown();
        });
        this.releaseWorker.countDown();
        assertTrue(mapStarted.await(2, TimeUnit.SECONDS));
        assertTrue(this.written.isEmpty());
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertFalse(this.service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        if (outcome.equals("buckets")) {
            CountDownLatch sameBucket = new CountDownLatch(1);
            CountDownLatch otherBucket = new CountDownLatch(1);
            int bucket = this.player.getUniqueId().hashCode() & 1;
            this.executor.submit(new UUID(1, bucket ^ 1), sameBucket::countDown);
            this.executor.submit(new UUID(1, bucket), otherBucket::countDown);
            assertTrue(otherBucket.await(2, TimeUnit.SECONDS));
            assertEquals(1, sameBucket.getCount(), "同桶玩家应等待当前地图准备");
            assertFalse(first.isDone());
        }
        if (outcome.equals("stash")) {
            BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE);
            SnapshotStash stash = new SnapshotStash(this.directory, codec, logger);
            Field writerField = SnapshotSaver.class.getDeclaredField("writer");
            writerField.setAccessible(true);
            NmsPlayerFixture.set(SnapshotWriter.class, writerField.get(this.saver), "stash", stash);
            this.executor.shutdown(1, TimeUnit.SECONDS);
            this.service.stashUnsettled();
            this.service.stashUnsettled();
            assertInstanceOf(SnapshotSaveResult.Settled.class, first.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, assertThrows(ExecutionException.class, () -> second.get(2, TimeUnit.SECONDS)).getCause());
            assertTrue(this.service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
            try (var files = Files.list(this.directory.resolve("snapshot/pending"))) {
                List<Path> allFiles = files.toList();
                List<Path> pending = allFiles.stream().filter(path -> path.toString().endsWith(".snapshot")).sorted().toList();
                assertEquals(2, allFiles.size());
                assertEquals(1, pending.size());
                for (int i = 0; i < pending.size(); i++) {
                    Snapshot raw = assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(Files.readAllBytes(pending.get(i)))).snapshot();
                    assertEquals(raw.meta(), ExceptionHeader.read(pending.get(i)).meta());
                    assertEquals(i + 1, ((CompoundTag) raw.data(InventoryDataType.INVENTORY)).getList("items").getCompound(0).getCompound("components").getInt("minecraft:map_id"));
                }
            }
            secondEncodeRelease.complete(null);
            maps.close();
            assertTrue(this.written.isEmpty());
            assertTrue(this.service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
            return;
        }
        CompoundTag completed = NBT.createCompound();
        completed.putInt("minecraft:map_id", -1);
        if (outcome.equals("closed")) {
            maps.close();
        } else if (outcome.equals("singleFailure")) {
            firstMap.completeExceptionally(new IllegalStateException("one map failed"));
        } else {
            if (outcome.equals("chainFailure")) {
                CompoundTag malformedItem = NBT.createCompound();
                malformedItem.put("components", NBT.createCompound());
                malformedItem.putInt("id", 1);
                completed.put("minecraft:use_remainder", malformedItem);
            }
            firstMap.complete(completed);
        }
        assertTrue(afterSaves.await(2, TimeUnit.SECONDS));
        assertFalse(this.service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        this.finishWrites();
        assertTrue(this.service.sealAndAwaitSaves(2, TimeUnit.SECONDS));
        List<Integer> expected = switch (outcome) {
            case "closed" -> List.of(1, 2);
            case "singleFailure" -> List.of(1, -2);
            case "chainFailure" -> List.of(-2);
            default -> List.of(-1, -2);
        };
        if (outcome.equals("chainFailure")) {
            assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            this.service.stashUnsettled();
            assertFalse(Files.exists(this.directory.resolve("snapshot/pending")), "地图整体异常不得在关服时补写");
        }
        assertEquals(expected, this.written.stream().map(snapshot -> ((CompoundTag) snapshot.data(InventoryDataType.INVENTORY)).getList("items").getCompound(0).getCompound("components").getInt("minecraft:map_id")).toList());
    }

    /**
     * 世界保存先采集同步组, 死亡保存独立采集, 两者返回时无需等待 worker 或数据库确认.
     *
     * @throws Exception 测试调度、结果等待或文件检查失败时
     */
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
        assertFalse(world.isDone());
        assertFalse(death.isDone());
        assertTrue(this.written.isEmpty());
        this.sync.value.set(3);
        this.async.value.set(3);

        this.releaseWorker.countDown();
        this.awaitSubmissions();
        assertFalse(this.service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));

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
        this.finishWrites();
        assertTrue(this.service.sealAndAwaitSaves(2, TimeUnit.SECONDS));
    }

    /**
     * 在三种采集模式下验证编码后才开始地图准备, 排队期间重载不改变请求固定的地图模式.
     *
     * @param mode 当前验证的采集模式
     * @throws Exception 测试调度、结果等待或文件检查失败时
     */
    @ParameterizedTest
    @ValueSource(strings = {"SYNC", "ASYNC", "OFFLINE"})
    void mapsRunAfterEncodingOnExistingWorkerAndKeepRequestMode(String mode) throws Exception {
        SyncLogger logger = NmsPlayerFixture.allocate(SyncLogger.class);
        AtomicReference<Thread> processedOn = new AtomicReference<>();
        AtomicReference<Thread> capturedOn = new AtomicReference<>();
        AtomicReference<Thread> encodedOn = new AtomicReference<>();
        DataRegistry registry = new DataRegistry();
        registry.register(new PlayerDataType<InventoryDataType.Inventory>() {
            @Override
            @NotNull
            public DataKey key() { return InventoryDataType.INVENTORY; }

            @Override
            @NotNull
            public InventoryDataType.Inventory capture(@NotNull Player player, @NotNull CaptureMode captureMode) {
                capturedOn.set(Thread.currentThread());
                return new InventoryDataType.Inventory(new ItemStack[0], 0, 0);
            }

            @Override
            @NotNull
            public Tag encode(@NotNull InventoryDataType.Inventory value) {
                assertNull(processedOn.get());
                encodedOn.set(Thread.currentThread());
                CompoundTag tag = NBT.createCompound();
                CompoundTag item = NBT.createCompound();
                item.putString("id", "minecraft:filled_map");
                CompoundTag components = NBT.createCompound();
                components.putInt("minecraft:map_id", 1);
                item.put("components", components);
                var items = NBT.createList();
                items.add(item);
                tag.put("items", items);
                return tag;
            }

            @Override
            @NotNull
            public InventoryDataType.Inventory decode(@NotNull Tag tag, int version) { throw new AssertionError(); }

            @Override
            public void apply(@NotNull Player player, @NotNull InventoryDataType.Inventory value) { throw new AssertionError(); }
        });
        registry.freeze();
        PlayerDataPipeline pipeline = new PlayerDataPipeline(null);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "dataRegistry", registry);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "decoder", new SnapshotDecoder(registry));
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "logger", logger);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "mapSync", NmsPlayerFixture.allocate(MapSyncService.class));
        NmsPlayerFixture.set(SnapshotSaver.class, this.saver, "playerDataPipeline", pipeline);
        MapSyncService maps = NmsPlayerFixture.allocate(MapSyncService.class);
        NmsPlayerFixture.set(MapSyncService.class, maps, "ownerId", "A-world");
        MapHandler handler = new MapHandler() {
            @Override
            @NotNull
            public MapType type() { return MapType.SYNC; }
            @Override
            @NotNull
            public CompletableFuture<CompoundTag> compileAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull IntFunction<CompletableFuture<StoredMap>> publish) {
                assertSame(CaptureSchedulingTest.this.worker.get(), encodedOn.get());
                assertEquals(MapType.SYNC, origin.type());
                processedOn.set(Thread.currentThread());
                return CompletableFuture.completedFuture(components);
            }
            @Override
            @NotNull
            public CompletableFuture<CompoundTag> decodeAsync(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String owner) { throw new AssertionError(); }
        };
        NmsPlayerFixture.set(MapSyncService.class, maps, "pipeline", new MapPipeline(registry, List.of(handler), logger));
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "mapSync", maps);
        NmsPlayerFixture.set(PluginConfig.MapOptions.class, PluginConfig.synchronization$map(), "type", MapType.SYNC);
        switch (CaptureMode.valueOf(mode)) {
            case SYNC -> this.service.captureNowAndSave(this.player, SaveCause.DEATH, Map.of());
            case ASYNC -> this.service.captureLaterAndSave(this.player, SaveCause.WORLD_SAVE, Map.of());
            case OFFLINE -> this.service.captureLogoutAndSave(this.player, SaveCause.DISCONNECT, Map.of());
        }
        assertNull(processedOn.get());
        if (mode.equals("OFFLINE")) {
            assertNull(capturedOn.get());
        } else {
            assertSame(Thread.currentThread(), capturedOn.get());
        }
        NmsPlayerFixture.set(PluginConfig.MapOptions.class, PluginConfig.synchronization$map(), "type", MapType.HIDE);
        this.releaseWorker.countDown();
        this.awaitSubmissions();
        this.finishWrites();
        assertTrue(this.service.sealAndAwaitSaves(2, TimeUnit.SECONDS));
        assertSame(this.worker.get(), processedOn.get());
        assertEquals(1, this.written.size());
        if (mode.equals("OFFLINE")) assertSame(this.worker.get(), capturedOn.get());
    }

    /**
     * RESTORE 保留历史原内容并创建新身份, 与普通采集共享同玩家逻辑时间顺序.
     *
     * @throws Exception 测试调度、结果等待或文件检查失败时
     */
    @Test
    void restoreCreatesNewIdentityAndSharesTimestampOrderWithCaptures() throws Exception {
        Snapshot source = new Snapshot(new SnapshotMeta(UUID.randomUUID(), this.player.getUniqueId(), 1, SaveCause.COMMAND, true, "old", 0),
                Map.of(DataKey.of("external", "retained"), NBT.createCompound()));
        this.service.captureNowAndSave(this.player, SaveCause.COMMAND, Map.of());
        this.saver.saveRestored(source, this.player.getName());
        this.service.captureNowAndSave(this.player, SaveCause.COMMAND, Map.of());
        this.releaseWorker.countDown();
        this.awaitSubmissions();
        this.finishWrites();
        assertTrue(this.service.sealAndAwaitSaves(2, TimeUnit.SECONDS));
        assertEquals(3, this.written.size());
        Snapshot restored = this.written.get(1);
        assertEquals(SaveCause.RESTORE, restored.meta().cause());
        assertNotEquals(source.meta().id(), restored.meta().id());
        assertFalse(restored.meta().pinned());
        assertEquals(source.data(), restored.data());
        assertTrue(this.written.get(0).meta().timestamp() < restored.meta().timestamp());
        assertTrue(restored.meta().timestamp() < this.written.get(2).meta().timestamp());
        assertEquals(1, source.meta().timestamp());
    }

    /**
     * 会话停止接受普通保存后, 退出的静止状态仍由同一 worker 采集和编码.
     *
     * @throws Exception 测试调度、结果等待或文件检查失败时
     */
    @Test
    void finalOfflineTaskCapturesAndEncodesOnSameWorkerAndSealedSessionRejectsLateNotice() throws Exception {
        this.session.transition(SessionState.SAVING);
        assertNull(this.sessions.captureLaterAndSave(this.session, this.player, SaveCause.WORLD_SAVE));
        assertTrue(this.sync.modes.isEmpty());
        this.service.captureLogoutAndSave(this.player, SaveCause.DISCONNECT, this.session.retainedData());
        assertTrue(this.sync.modes.isEmpty());
        assertTrue(this.async.modes.isEmpty());
        this.releaseWorker.countDown();
        this.awaitSubmissions();
        this.finishWrites();
        assertTrue(this.service.sealAndAwaitSaves(2, TimeUnit.SECONDS));
        assertEquals(List.of(CaptureMode.OFFLINE), this.sync.modes);
        assertEquals(List.of(CaptureMode.OFFLINE), this.async.modes);
        assertSame(this.worker.get(), this.sync.threads.getFirst());
        assertSame(this.worker.get(), this.sync.encodeThread);
        assertEquals(SaveCause.DISCONNECT, this.written.getFirst().meta().cause());
    }

    /**
     * 关键采集、编码失败和事件取消均结束请求, 关服清理不会补写这些正文.
     *
     * @param stage 当前验证的失败或取消阶段
     * @throws Exception 测试调度、结果等待或文件检查失败时
     */
    @ParameterizedTest
    @ValueSource(strings = {"syncCapture", "asyncCapture", "encode", "cancel"})
    void failuresAndCancellationFinishAcceptedSaves(String stage) throws Exception {
        this.sync.captureFails = stage.equals("syncCapture");
        this.async.captureFails = stage.equals("asyncCapture");
        this.async.encodeFails = stage.equals("encode");
        this.cancelEvent = stage.equals("cancel");
        CompletableFuture<SnapshotSaveResult> result = this.sessions.captureLaterAndSave(this.session, this.player, SaveCause.WORLD_SAVE);
        this.releaseWorker.countDown();
        assertTrue(this.service.sealAndAwaitSaves(2, TimeUnit.SECONDS));
        if (this.cancelEvent) {
            assertSame(SnapshotSaveResult.CANCELLED, result.get(2, TimeUnit.SECONDS));
        } else {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        }
        assertTrue(this.written.isEmpty());
        this.service.stashUnsettled();
        assertFalse(Files.exists(this.directory.resolve("snapshot/pending")));
    }

    /**
     * 执行器关闭后拒绝新任务, 保存仍以失败回执终结且不遗留在途请求.
     *
     * @throws Exception 测试任务未能按期限结束或等待最终保存失败
     */
    @Test
    void rejectedSubmissionCompletesAcceptedSave() throws Exception {
        this.releaseWorker.countDown();
        this.executor.shutdown(2, TimeUnit.SECONDS);

        CompletableFuture<SnapshotSaveResult> result = this.service.captureNowAndSave(this.player, SaveCause.COMMAND, Map.of());

        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        assertTrue(this.service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        assertTrue(this.written.isEmpty());
    }

    /**
     * 已入队任务异常时结束保存回执, 原异常继续交给执行器计数和报告.
     *
     * @param kind 同步抛出的异常类别
     * @throws Exception 测试任务未能按期限结束或等待最终保存失败
     */
    @ParameterizedTest
    @ValueSource(strings = {"runtime", "error"})
    void taskFailureCompletesSaveAndStillReachesExecutor(String kind) throws Exception {
        this.writeFailure = kind.equals("runtime") ? new IllegalStateException("write submission failed") : new AssertionError("write submission failed");
        CompletableFuture<SnapshotSaveResult> result = this.sessions.captureLaterAndSave(this.session, this.player, SaveCause.WORLD_SAVE);
        this.releaseWorker.countDown();

        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertSame(this.writeFailure, failure.getCause());
        assertTrue(this.service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        // 回执在执行器报告之前完成, 排在其后的任务用于等待该次报告结束.
        CountDownLatch reported = new CountDownLatch(1);
        this.executor.submit(this.player.getUniqueId(), reported::countDown);
        assertTrue(reported.await(2, TimeUnit.SECONDS));
        assertEquals(1, this.executor.failureCount());
        assertTrue(this.written.isEmpty());
    }

    /**
     * 管理操作停止以后, ACTIVE 会话的最终保存仍能先被接受, 封口后拒绝新采集.
     *
     * @throws Exception 保存队列未按期限结束时
     */
    @Test
    void shutdownSaveIsAcceptedAfterManagementStopsAndBeforeWriterSeals() throws Exception {
        this.service.stopOperations();
        CompletableFuture<SnapshotSaveResult> result = this.sessions.captureNowAndSave(this.session, this.player, SaveCause.SHUTDOWN);
        assertNotNull(result);
        assertFalse(this.service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        int captures = this.sync.modes.size();
        assertThrows(RejectedExecutionException.class, () -> this.service.captureNowAndSave(this.player, SaveCause.COMMAND, Map.of()));
        assertEquals(captures, this.sync.modes.size());
        this.releaseWorker.countDown();
        this.awaitSubmissions();
        this.finishWrites();
        assertTrue(this.service.sealAndAwaitSaves(2, TimeUnit.SECONDS));
        assertEquals(SaveCause.SHUTDOWN, this.written.getFirst().meta().cause());
    }

    /**
     * 监听器还在处理事件时停服先决定暂存, 迟到取消保留已完成的暂存结果.
     *
     * @throws Exception 监听器信号或文件检查失败时
     */
    @Test
    void lateEventCancellationDoesNotRetractShutdownStash() throws Exception {
        CountDownLatch enteredEvent = new CountDownLatch(1);
        CompletableFuture<Void> returnFromEvent = new CompletableFuture<>();
        this.eventAction = event -> {
            enteredEvent.countDown();
            returnFromEvent.join();
            event.setCancelled(true);
        };
        CompletableFuture<SnapshotSaveResult> result = this.service.captureNowAndSave(this.player, SaveCause.COMMAND, Map.of());
        this.releaseWorker.countDown();
        try {
            assertTrue(enteredEvent.await(2, TimeUnit.SECONDS));
            assertFalse(this.service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
            this.service.stashUnsettled();
            assertEquals(StorageProvider.SaveResult.RETRY_LATER, assertInstanceOf(SnapshotSaveResult.Settled.class, result.get(2, TimeUnit.SECONDS)).result());
        } finally {
            returnFromEvent.complete(null);
        }
        this.awaitSubmissions();
        this.service.stashUnsettled();
        assertTrue(this.written.isEmpty());
        assertTrue(this.events.getFirst().isCancelled());
        try (var paths = Files.list(this.directory.resolve("snapshot/pending"))) {
            assertEquals(2, paths.count(), "迟到取消不得撤回或重复发布正文与头");
        }
    }

    /**
     * 等待已经投递的保存任务退出, 供测试在数据库尚未确认时检查提交结果.
     *
     * @throws InterruptedException 等待测试队列标记时被中断
     */
    private void awaitSubmissions() throws InterruptedException {
        CountDownLatch submitted = new CountDownLatch(1);
        this.executor.submit(this.player.getUniqueId(), submitted::countDown);
        assertTrue(submitted.await(2, TimeUnit.SECONDS));
    }

    /** 由测试确认已发出的全部数据库写入, 保存回执随后才允许完成. */
    private void finishWrites() {
        for (int i = 0; i < this.writes.size(); i++) {
            this.writes.get(i).complete(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.SAVED, null));
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
