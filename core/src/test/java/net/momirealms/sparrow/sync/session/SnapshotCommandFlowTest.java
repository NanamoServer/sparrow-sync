package net.momirealms.sparrow.sync.session;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.cluster.HandoffManager;
import net.momirealms.sparrow.sync.cluster.message.HandoffResponseMessage;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.player.PlayerDirectory;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.player.PlayerPresenceMessage;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.EntityExecutor;
import net.momirealms.sparrow.sync.plugin.scheduler.task.DummyTask;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.redis.MessageBrokerManager;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotDecoder;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType;
import net.momirealms.sparrow.sync.event.PreApplyEvent;
import org.bukkit.World;
import java.util.ArrayList;
import java.util.function.Consumer;
import net.momirealms.sparrow.sync.cluster.RemoteSnapshotManager;
import net.kyori.adventure.text.TranslatableComponent;
import net.momirealms.sparrow.sync.plugin.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotCaptureCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotRestoreCommand;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import net.momirealms.sparrow.sync.session.operation.SnapshotCaptureResult;
import net.momirealms.sparrow.sync.session.operation.SnapshotRestoreResult;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.util.VersionHelper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotCommandFlowTest {
    @TempDir Path directory;
    private final UUID uuid = UUID.randomUUID();
    private final DataKey key = DataKey.of("test", "value");
    private final AtomicReference<String> value = new AtomicReference<>("live");
    private final AtomicBoolean online = new AtomicBoolean(true);
    private final AtomicBoolean dead = new AtomicBoolean();
    private final AtomicReference<Double> health = new AtomicReference<>(20.0);
    private final List<String> actions = new ArrayList<>();
    private final CompletableFuture<Boolean> teleport = new CompletableFuture<>();
    private Consumer<PreApplyEvent> onPreApply = event -> {};
    private Runnable onRespawn = () -> {};
    private final AtomicInteger respawns = new AtomicInteger();
    private final AtomicBoolean locked = new AtomicBoolean();
    private final BlockingQueue<Runnable> entityTasks = new LinkedBlockingQueue<>();
    private final BlockingQueue<Write> writes = new LinkedBlockingQueue<>();
    private final Map<UUID, Snapshot> stored = new ConcurrentHashMap<>();
    private Thread entityThread;
    private ExecutorService preparation;
    private PlayerSerialExecutor serial;
    private SnapshotService service;
    private RemoteSnapshotManager remote;
    private Player player;
    private SparrowSync plugin;
    private HandoffManager handoff;
    private Object oldBukkit;
    private Object oldConfig;
    private Object oldServerConfig;

    @BeforeAll
    static void initializeProxy() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        this.entityThread = Thread.currentThread();
        this.preparation = Executors.newSingleThreadExecutor();
        this.oldConfig = replace(PluginConfig.class, "config", new PluginConfig.ConfigDefinition());
        this.oldServerConfig = replace(ServerConfig.class, "config", new ServerConfig.ConfigDefinition());
        Player player = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> this.uuid;
            case "getName" -> "Steve";
            case "isOnline" -> this.online.get();
            case "isDead" -> this.dead.get();
            case "getHealth" -> this.dead.get() ? 0.0 : this.health.get();
            case "setHealth" -> {
                double health = (double) args[0];
                this.actions.add("health:" + health);
                this.health.set(health);
                this.dead.set(health <= 0);
                yield null;
            }
            case "getServer" -> Bukkit.getServer();
            case "teleportAsync" -> {
                this.actions.add("location");
                yield this.teleport;
            }
            case "getWorld" -> null;
            case "spigot" -> new Player.Spigot() {
                @Override
                public void respawn() {
                    SnapshotCommandFlowTest.this.actions.add("respawn");
                    SnapshotCommandFlowTest.this.respawns.incrementAndGet();
                    SnapshotCommandFlowTest.this.dead.set(false);
                    SnapshotCommandFlowTest.this.value.set("respawn reset");
                    SnapshotCommandFlowTest.this.onRespawn.run();
                }
            };
            default -> throw new AssertionError(method.getName());
        });
        PluginManager events = proxy(PluginManager.class, (instance, method, args) -> {
            assertEquals("callEvent", method.getName());
            if (args[0] instanceof PreApplyEvent event) this.onPreApply.accept(event);
            return null;
        });
        this.oldBukkit = replace(Bukkit.class, "server", proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
            case "getPlayer" -> this.online.get() && this.uuid.equals(args[0]) ? player : null;
            case "getPlayerExact" -> this.online.get() && "Steve".equalsIgnoreCase((String) args[0]) ? player : null;
            case "getPluginManager" -> events;
            case "getWorld" -> proxy(World.class, (world, call, values) -> null);
            default -> throw new AssertionError(method.getName());
        }));
        this.player = player;
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        this.plugin = plugin;
        PluginLogger console = proxy(PluginLogger.class, (instance, method, args) -> null);
        SyncLogger logger = new SyncLogger(console);
        this.serial = new PlayerSerialExecutor(console, 1);
        EntityExecutor entity = proxy(EntityExecutor.class, (instance, method, args) -> switch (method.getName()) {
            case "isOwnedByCurrentRegion" -> Thread.currentThread() == this.entityThread;
            case "run" -> {
                this.entityTasks.add((Runnable) args[1]);
                yield new DummyTask();
            }
            default -> throw new AssertionError(method.getName());
        });
        SchedulerAdapter<?> scheduler = proxy(SchedulerAdapter.class, (instance, method, args) -> switch (method.getName()) {
            case "async" -> this.preparation;
            case "entity" -> entity;
            default -> throw new AssertionError(method.getName());
        });
        DataRegistry registry = new DataRegistry();
        registry.register(new RecordingType());
        registry.register(new HealthType());
        registry.register(new LocationType());
        registry.freeze();
        PlayerDataPipeline pipeline = new PlayerDataPipeline(plugin);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "dataRegistry", registry);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "decoder", new SnapshotDecoder(registry));
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "logger", logger);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "mapSync", new MapSyncService(plugin));
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(instance, method, args);
            return switch (method.getName()) {
                case "snapshot" -> CompletableFuture.completedFuture(Optional.ofNullable(this.stored.get((UUID) args[0])));
                case "saveSnapshotOutcome" -> {
                    CompletableFuture<StorageProvider.SaveOutcome> result = new CompletableFuture<>();
                    this.writes.add(new Write((Snapshot) args[0], result));
                    yield result;
                }
                case "rotate" -> CompletableFuture.completedFuture(0);
                default -> throw new AssertionError("Unexpected storage call: " + method.getName());
            };
        });
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataFolderPath", this.directory);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", logger);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "scheduler", scheduler);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "playerExecutor", this.serial);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataRegistry", registry);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "playerDataPipeline", pipeline);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "storageProvider", storage);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "binaryCodec", new BinarySnapshotCodec(CompressorRegistry.NONE));
        SnapshotService service = new SnapshotService(plugin);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "snapshotService", service);
        service.onLoad();
        SessionManager sessions = new SessionManager(plugin);
        NmsPlayerFixture.set(SessionManager.class, sessions, "snapshotService", service);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "sessionManager", sessions);
        sessions.tryOpen(this.uuid, "Steve", ConnectionFixture.create()).transition(SessionState.ACTIVE);
        PlayerDirectory players = new PlayerDirectory(plugin);
        Method presence = PlayerDirectory.class.getDeclaredMethod("acceptPresence", PlayerPresenceMessage.class);
        presence.setAccessible(true);
        presence.invoke(players, new PlayerPresenceMessage("local", this.uuid, "Steve", true));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "playerDirectory", players);
        RedisAsyncCommands<byte[], byte[]> commands = proxy(RedisAsyncCommands.class, (instance, method, args) -> {
            AsyncCommand<byte[], byte[], Object> result = new AsyncCommand<>(new Command<>(CommandType.GET, null));
            if (method.getName().equals("setGet")) {
                assertFalse(this.locked.getAndSet(true));
                result.complete(null);
            } else if (method.getName().equals("eval")) {
                assertTrue(this.locked.getAndSet(false));
                result.complete(1L);
            } else {
                throw new AssertionError(method.getName());
            }
            return result;
        });
        RedisConnector redis = NmsPlayerFixture.allocate(RedisConnector.class);
        NmsPlayerFixture.set(RedisConnector.class, redis, "connection", proxy(StatefulRedisConnection.class, (instance, method, args) -> commands));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "sessionLock", new SessionLock(redis, "local"));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "messageBrokerManager", NmsPlayerFixture.allocate(MessageBrokerManager.class));
        this.service = service;
        this.remote = new RemoteSnapshotManager(plugin);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "remoteSnapshotManager", this.remote);
        this.handoff = new HandoffManager(plugin);
        this.handoff.onLoad();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (this.serial != null) this.serial.shutdown(2, TimeUnit.SECONDS);
        if (this.preparation != null) this.preparation.shutdownNow();
        replace(Bukkit.class, "server", this.oldBukkit);
        replace(PluginConfig.class, "config", this.oldConfig);
        replace(ServerConfig.class, "config", this.oldServerConfig);
    }

    @Test
    void captureUsesSyncModeAndAcknowledgesOnlyAfterStorageCompletes() throws Exception {
        CompletableFuture<SnapshotCaptureResult> completion = this.service.capture(this.player);
        Write write = this.nextWrite();
        assertFalse(completion.isDone());
        assertEquals("live", write.snapshot.data(this.key).getAsString());
        assertEquals(SaveCause.COMMAND, write.snapshot.meta().cause());
        write.complete();
        assertEquals(new SnapshotCaptureResult.Captured(write.snapshot.meta().id()), completion.get(2, TimeUnit.SECONDS));
    }

    @Test
    void localCaptureCommandUsesServiceAndWaitsForStorageWithoutRemoteTransport() throws Exception {
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "remoteSnapshotManager", null);
        CompletableFuture<String> feedback = this.command(false, "capture Steve");
        Write write = this.nextWrite();
        assertEquals("live", write.snapshot.data(this.key).getAsString());
        assertFalse(feedback.isDone());
        write.complete();
        assertEquals("command.snapshot.captured", feedback.get(2, TimeUnit.SECONDS));
    }

    @Test
    void localRestoreCommandUsesServiceWithoutRemoteTransport() throws Exception {
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "remoteSnapshotManager", null);
        Snapshot source = this.source(this.uuid);
        CompletableFuture<String> feedback = this.command(true, "restore Steve " + source.meta().id());
        Runnable task = this.entityTasks.poll(2, TimeUnit.SECONDS);
        assertNotNull(task);
        task.run();
        Write write = this.nextWrite();
        assertEquals("history", this.value.get());
        assertFalse(feedback.isDone());
        write.complete();
        assertEquals("command.snapshot.restored", feedback.get(2, TimeUnit.SECONDS));
    }

    @Test
    void remoteCaptureDispatchesToPlayerThreadAndWaitsForStorage() throws Exception {
        CompletableFuture<SnapshotCaptureResult> completion = CompletableFuture.supplyAsync(
                () -> this.remote.receiveCapture(this.uuid), this.preparation).thenCompose(result -> result);
        Runnable task = this.entityTasks.poll(2, TimeUnit.SECONDS);
        assertNotNull(task);
        assertTrue(this.writes.isEmpty());
        task.run();
        Write write = this.nextWrite();
        assertEquals("live", write.snapshot.data(this.key).getAsString());
        assertFalse(completion.isDone());
        write.complete();
        assertInstanceOf(SnapshotCaptureResult.Captured.class, completion.get(2, TimeUnit.SECONDS));
    }

    @Test
    void remoteRestoreForDepartedPlayerDoesNotFallBackToOfflineSave() throws Exception {
        Snapshot source = this.source(this.uuid);
        this.online.set(false);
        assertSame(SnapshotRestoreResult.OFFLINE, this.remote.receiveRestore(this.uuid, source.meta().id()).get(2, TimeUnit.SECONDS));
        assertFalse(this.locked.get());
        assertFalse(this.service.restoringOffline(this.uuid));
        assertTrue(this.writes.isEmpty());
        assertTrue(this.entityTasks.isEmpty());
    }

    @Test
    void restoreAppliesSelectedDataWithoutLatestReadAndWaitsForNewRecord() throws Exception {
        Snapshot source = this.source(this.uuid);
        CompletableFuture<SnapshotRestoreResult> completion = this.service.restore(this.player, source.meta().id());
        Runnable task = this.entityTasks.poll(2, TimeUnit.SECONDS);
        assertNotNull(task);
        assertEquals("live", this.value.get());
        task.run();
        assertEquals("history", this.value.get());
        Write write = this.nextWrite();
        assertFalse(completion.isDone());
        assertEquals(source.data(), write.snapshot.data());
        assertEquals(SaveCause.RESTORE, write.snapshot.meta().cause());
        assertNotEquals(source.meta().id(), write.snapshot.meta().id());
        write.complete();
        assertInstanceOf(SnapshotRestoreResult.Restored.class, completion.get(2, TimeUnit.SECONDS));
        assertSame(source, this.stored.get(source.meta().id()));
    }

    @Test
    void restoreStillChecksOwnershipBeforeApplicationOrOfflineWrite() throws Exception {
        Snapshot source = this.source(UUID.randomUUID());
        assertSame(SnapshotRestoreResult.WRONG_PLAYER, this.service.restore(this.player, source.meta().id()).get(2, TimeUnit.SECONDS));
        assertSame(SnapshotRestoreResult.WRONG_PLAYER, this.service.restoreOffline(new PlayerIdentity(this.uuid, "Steve"), source.meta().id()).get(2, TimeUnit.SECONDS));
        assertTrue(this.entityTasks.isEmpty());
        assertTrue(this.writes.isEmpty());
        assertFalse(this.locked.get());
    }

    @Test
    void deadPlayerCanRestoreOtherDataWithoutHealthOrRespawn() throws Exception {
        Snapshot source = this.source(this.uuid);
        this.dead.set(true);
        CompletableFuture<SnapshotRestoreResult> completion = this.service.restore(this.player, source.meta().id());
        Runnable task = this.entityTasks.poll(2, TimeUnit.SECONDS);
        assertNotNull(task);
        task.run();
        assertEquals(0, this.respawns.get());
        assertTrue(this.dead.get());
        assertEquals("history", this.value.get());
        Write write = this.nextWrite();
        assertFalse(completion.isDone());
        write.complete();
        assertInstanceOf(SnapshotRestoreResult.Restored.class, completion.get(2, TimeUnit.SECONDS));
    }

    @Test
    void disconnectBeforeApplicationDoesNotWriteOrApplyRestore() throws Exception {
        Snapshot source = this.source(this.uuid);
        CompletableFuture<SnapshotRestoreResult> completion = this.service.restore(this.player, source.meta().id());
        Runnable task = this.entityTasks.poll(2, TimeUnit.SECONDS);
        assertNotNull(task);
        this.online.set(false);
        task.run();
        assertSame(SnapshotRestoreResult.OFFLINE, completion.get(2, TimeUnit.SECONDS));
        assertEquals("live", this.value.get());
        assertTrue(this.writes.isEmpty());
    }

    @Test
    void offlineRestoreHoldsLockAndAnswersSavingUntilPersistenceCompletes() throws Exception {
        UUID offline = UUID.randomUUID();
        Snapshot source = this.source(offline);
        CompletableFuture<SnapshotRestoreResult> completion = this.service.restoreOffline(new PlayerIdentity(offline, "Offline"), source.meta().id());
        Write write = this.nextWrite();
        assertTrue(this.locked.get());
        assertTrue(this.service.restoringOffline(offline));
        assertEquals(HandoffResponseMessage.Status.SAVING, this.handoff.answer(offline).status());
        assertTrue(this.entityTasks.isEmpty());
        assertFalse(completion.isDone());
        write.complete();
        assertInstanceOf(SnapshotRestoreResult.RestoredOffline.class, completion.get(2, TimeUnit.SECONDS));
        assertFalse(this.locked.get());
        assertFalse(this.service.restoringOffline(offline));
    }

    @Test
    void offlineRestoreReleasesLockWhenSubmissionFails() throws Exception {
        UUID offline = UUID.randomUUID();
        Snapshot source = this.source(offline);
        this.serial.shutdown(2, TimeUnit.SECONDS);
        CompletableFuture<SnapshotRestoreResult> completion = this.service.restoreOffline(new PlayerIdentity(offline, "Offline"), source.meta().id());
        assertThrows(Exception.class, () -> completion.get(2, TimeUnit.SECONDS));
        assertFalse(this.locked.get());
        assertFalse(this.service.restoringOffline(offline));
    }

    private CompletableFuture<String> command(boolean restore, String input) throws Exception {
        CompletableFuture<String> feedback = new CompletableFuture<>();
        CommandManager manager = proxy(CommandManager.class, (instance, method, args) -> {
            assertEquals("handleCommandFeedback", method.getName());
            feedback.complete(((TranslatableComponent.Builder) args[1]).build().key());
            return null;
        });
        TestCloud cloud = new TestCloud();
        BukkitCommandFeature feature = restore ? new SnapshotRestoreCommand(manager, this.plugin) : new SnapshotCaptureCommand(manager, this.plugin);
        feature.registerCommand(cloud, cloud.commandBuilder(restore ? "restore" : "capture"));
        CommandSender sender = proxy(CommandSender.class, (instance, method, args) -> null);
        cloud.commandExecutor().executeCommand(sender, input).get(2, TimeUnit.SECONDS);
        return feedback;
    }

    private static final class TestCloud extends org.incendo.cloud.CommandManager<CommandSender> {
        private TestCloud() {
            super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(CommandSender sender, String permission) {
            return true;
        }
    }

    private Snapshot source(UUID player) {
        Snapshot snapshot = new Snapshot(new SnapshotMeta(UUID.randomUUID(), player, 1, SaveCause.COMMAND, true, "history", 0), Map.of(this.key, NBT.createString("history")));
        this.stored.put(snapshot.meta().id(), snapshot);
        return snapshot;
    }

    private Write nextWrite() throws Exception {
        Write write = this.writes.poll(2, TimeUnit.SECONDS);
        assertNotNull(write);
        return write;
    }

    private record Write(Snapshot snapshot, CompletableFuture<StorageProvider.SaveOutcome> result) {
        private void complete() {
            this.result.complete(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.SAVED, null));
        }
    }

    /**
     * 默认过滤后允许事件主动补回血量与位置, 零血量及 RESTORE 写入等待传送成功.
     *
     * @throws Exception 测试玩家调度或异步结果未能完成
     */
    @Test
    void onlineDefaultsAllowEventToRestoreHealthAndLocation() throws Exception {
        Snapshot source = this.sourceWithHealth(0);
        this.onPreApply = event -> {
            assertFalse(event.decoded().containsKey(HealthDataType.HEALTH));
            assertFalse(event.decoded().containsKey(LocationDataType.LOCATION));
            event.decoded().put(HealthDataType.HEALTH, new HealthDataType.Health(0));
            event.decoded().put(LocationDataType.LOCATION, new LocationType().capture(this.player, CaptureMode.SYNC));
        };
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(source);
        // 默认配置先过滤历史值, 事件补回的值继续参与本次应用.
        assertEquals(List.of("data", "location"), this.actions);
        assertFalse(this.dead.get());
        assertEquals(20.0, this.health.get());
        assertTrue(this.writes.isEmpty());
        this.teleport.complete(true);
        assertEquals(List.of("data", "location", "health:0.0"), this.actions);
        assertTrue(this.dead.get());
        this.nextWrite().complete();
        assertInstanceOf(SnapshotRestoreResult.Restored.class, result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void respawnCompletesBeforeDataHealthAndLocationAndSaveWaitsForTeleport() throws Exception {
        this.onlineOptions(true, true);
        this.dead.set(true);
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.sourceWithHealth(18));
        assertEquals(List.of("respawn", "data", "health:18.0", "location"), this.actions);
        assertEquals("history", this.value.get());
        assertEquals(1, this.respawns.get());
        assertTrue(this.writes.isEmpty());
        assertFalse(result.isDone());
        this.teleport.complete(true);
        this.nextWrite().complete();
        assertInstanceOf(SnapshotRestoreResult.Restored.class, result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void nativeDeathRunsAfterDataAndSuccessfulLocation() throws Exception {
        this.onlineOptions(true, true);
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.sourceWithHealth(0));
        assertEquals(List.of("data", "location"), this.actions);
        assertFalse(this.dead.get());
        this.teleport.complete(true);
        assertEquals(List.of("data", "location", "health:0.0"), this.actions);
        assertTrue(this.dead.get());
        this.nextWrite().complete();
        assertInstanceOf(SnapshotRestoreResult.Restored.class, result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void rejectedLocationDoesNotKillOrSaveThePlayer() throws Exception {
        this.onlineOptions(true, true);
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.sourceWithHealth(0));
        this.teleport.complete(false);
        assertSame(SnapshotRestoreResult.FAILED, result.get(2, TimeUnit.SECONDS));
        assertFalse(this.dead.get());
        assertTrue(this.writes.isEmpty());
    }

    @Test
    void removingHealthFromEventKeepsDeadPlayerDead() throws Exception {
        this.onlineOptions(true, false);
        this.dead.set(true);
        this.onPreApply = event -> event.decoded().remove(HealthDataType.HEALTH);
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.sourceWithHealth(18));
        assertEquals(List.of("data"), this.actions);
        assertEquals(0, this.respawns.get());
        assertTrue(this.dead.get());
        this.nextWrite().complete();
        assertInstanceOf(SnapshotRestoreResult.Restored.class, result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void disconnectDuringNativeRespawnStopsApplicationAndSave() throws Exception {
        this.onlineOptions(true, false);
        this.dead.set(true);
        this.onRespawn = () -> this.online.set(false);
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.sourceWithHealth(18));
        assertSame(SnapshotRestoreResult.OFFLINE, result.get(2, TimeUnit.SECONDS));
        assertEquals(List.of("respawn"), this.actions);
        assertTrue(this.writes.isEmpty());
    }

    private void onlineOptions(boolean health, boolean location) {
        NmsPlayerFixture.set(PluginConfig.OnlineRestoreOptions.class, PluginConfig.synchronization$onlineRestore(), "syncHealth", health);
        NmsPlayerFixture.set(PluginConfig.OnlineRestoreOptions.class, PluginConfig.synchronization$onlineRestore(), "syncLocation", location);
    }

    private Snapshot sourceWithHealth(double health) {
        Snapshot original = this.source(this.uuid);
        Snapshot snapshot = new Snapshot(original.meta(), Map.of(this.key, NBT.createString("history"),
                HealthDataType.HEALTH, NBT.createDouble(health), LocationDataType.LOCATION, NBT.createString("world")));
        this.stored.put(snapshot.meta().id(), snapshot);
        return snapshot;
    }

    private CompletableFuture<SnapshotRestoreResult> restoreAndRun(Snapshot snapshot) throws Exception {
        CompletableFuture<SnapshotRestoreResult> result = this.service.restore(this.player, snapshot.meta().id());
        Runnable task = this.entityTasks.poll(2, TimeUnit.SECONDS);
        assertNotNull(task);
        task.run();
        return result;
    }

    private final class RecordingType implements PlayerDataType<String> {
        @Override
        @NotNull
        public DataKey key() {
            return SnapshotCommandFlowTest.this.key;
        }
        @Override
        public boolean critical() {
            return true;
        }
        @Override
        public boolean supportsAsyncCapture() {
            return true;
        }
        @Override
        @NotNull
        public String capture(@NotNull Player player, @NotNull CaptureMode mode) {
            assertEquals(CaptureMode.SYNC, mode);
            assertSame(SnapshotCommandFlowTest.this.entityThread, Thread.currentThread());
            return SnapshotCommandFlowTest.this.value.get();
        }
        @Override
        @NotNull
        public Tag encode(@NotNull String value) {
            return NBT.createString(value);
        }
        @Override
        @NotNull
        public String decode(@NotNull Tag data, int version) {
            assertNotSame(SnapshotCommandFlowTest.this.entityThread, Thread.currentThread());
            return data.getAsString();
        }
        @Override
        public void apply(@NotNull Player player, @NotNull String value) {
            assertSame(SnapshotCommandFlowTest.this.entityThread, Thread.currentThread());
            SnapshotCommandFlowTest.this.actions.add("data");
            SnapshotCommandFlowTest.this.value.set(value);
        }
    }

    private static final class HealthType implements PlayerDataType<HealthDataType.Health> {
        @Override
        @NotNull
        public DataKey key() {
            return HealthDataType.HEALTH;
        }
        @Override
        public boolean critical() {
            return true;
        }
        @Override
        @NotNull
        public HealthDataType.Health capture(@NotNull Player player, @NotNull CaptureMode mode) {
            return new HealthDataType.Health(20);
        }
        @Override
        @NotNull
        public Tag encode(@NotNull HealthDataType.Health value) {
            return NBT.createDouble(value.health());
        }
        @Override
        @NotNull
        public HealthDataType.Health decode(@NotNull Tag data, int version) {
            return new HealthDataType.Health(NBTOps.INSTANCE.getNumberValue(data).getOrThrow().doubleValue());
        }
        @Override
        public void apply(@NotNull Player player, @NotNull HealthDataType.Health value) {
            player.setHealth(value.health());
        }
    }

    private static final class LocationType implements PlayerDataType<LocationDataType.PlayerLocation> {
        @Override
        @NotNull
        public DataKey key() { return LocationDataType.LOCATION; }
        @Override
        @NotNull
        public LocationDataType.PlayerLocation capture(@NotNull Player player, @NotNull CaptureMode mode) {
            return new LocationDataType.PlayerLocation("world", 10, 64, 20, 0, 0);
        }
        @Override
        @NotNull
        public Tag encode(@NotNull LocationDataType.PlayerLocation value) { return NBT.createString(value.world()); }
        @Override
        @NotNull
        public LocationDataType.PlayerLocation decode(@NotNull Tag value, int version) { return new LocationDataType.PlayerLocation("world", 10, 64, 20, 0, 0); }
        @Override
        public void apply(@NotNull Player player, @NotNull LocationDataType.PlayerLocation value) {
            throw new AssertionError("online location must use its async completion");
        }
    }

    private static Object replace(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        Object old = field.get(null);
        field.set(null, value);
        return old;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
