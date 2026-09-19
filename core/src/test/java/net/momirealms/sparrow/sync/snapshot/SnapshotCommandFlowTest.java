package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.api.event.SnapshotSaveEvent;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotStash;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
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
import net.momirealms.sparrow.sync.plugin.scheduler.executor.PlatformExecutor;
import net.momirealms.sparrow.sync.plugin.scheduler.task.DummyTask;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.redis.MessageBrokerManager;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotDecoder;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType;
import net.momirealms.sparrow.sync.api.event.PreApplyEvent;
import net.momirealms.sparrow.sync.api.SparrowSyncAPI;
import net.momirealms.sparrow.sync.api.event.PlayerDataReadyEvent;
import net.momirealms.sparrow.sync.cluster.message.SnapshotCaptureRequestMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotCaptureResponseMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotRestoreRequestMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotRestoreResponseMessage;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionListener;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplyContext;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotLoadResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotApplyResult;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import org.bukkit.event.player.PlayerJoinEvent;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
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
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotCaptureResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotRestoreResult;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.NoopSnapshotCache;
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
    private Consumer<SnapshotSaveEvent> onSave = event -> {};
    private Consumer<PlayerDataReadyEvent> onReady = event -> {};
    private SparrowSyncAPI api;
    private int readyEvents;
    private int kicks;
    private String lockOwner;
    private RuntimeException lookupFailure;
    private int lockAttempts;
    private CompletableFuture<Optional<Snapshot>> snapshotRead;
    private boolean critical = true;
    private RuntimeException decodeFailure;
    private RuntimeException applyFailure;
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
        ServerConfig.ConfigDefinition serverConfig = new ServerConfig.ConfigDefinition();
        NmsPlayerFixture.set(ServerConfig.ConfigDefinition.class, serverConfig, "serverId", "local");
        this.oldServerConfig = replace(ServerConfig.class, "config", serverConfig);
        Player player = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> this.uuid;
            case "getName" -> "Steve";
            case "isOnline" -> this.online.get();
            case "kick" -> {
                this.kicks++;
                yield null;
            }
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
            if (args[0] instanceof SnapshotSaveEvent event) this.onSave.accept(event);
            if (args[0] instanceof PlayerDataReadyEvent event) {
                this.readyEvents++;
                assertFalse(event.isAsynchronous());
                assertSame(this.entityThread, Thread.currentThread());
                this.onReady.accept(event);
            }
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
        PlatformExecutor entity = proxy(PlatformExecutor.class, (instance, method, args) -> switch (method.getName()) {
            case "isOwnedByCurrentRegion" -> Thread.currentThread() == this.entityThread;
            case "runLater" -> {
                this.entityTasks.add((Runnable) args[0]);
                yield new DummyTask();
            }
            default -> throw new AssertionError(method.getName());
        });
        SchedulerAdapter scheduler = proxy(SchedulerAdapter.class, (instance, method, args) -> switch (method.getName()) {
            case "async" -> this.preparation;
            case "platform" -> entity;
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
                case "snapshot" -> this.snapshotRead != null ? this.snapshotRead : CompletableFuture.completedFuture(Optional.ofNullable(this.stored.get((UUID) args[0])));
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
        SnapshotDataCodec dataCodec = new SnapshotDataCodec(CompressorRegistry.NONE);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataCodec", dataCodec);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "binaryCodec", new BinarySnapshotCodec(dataCodec));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "snapshotCache", new NoopSnapshotCache());
        NmsPlayerFixture.set(SparrowSync.class, plugin, "snapshotStash", new SnapshotStash(this.directory, plugin.binaryCodec(), logger, new NoopSnapshotCache()));
        SnapshotService service = new SnapshotService(plugin);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "snapshotService", service);
        service.onLoad();
        SessionManager sessions = new SessionManager(plugin);
        NmsPlayerFixture.set(SessionManager.class, sessions, "snapshotService", service);
        NmsPlayerFixture.set(SessionManager.class, sessions, "logger", logger);
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
                this.lockAttempts++;
                boolean held = this.locked.getAndSet(true);
                result.complete(held ? ("remote:" + this.uuid).getBytes(StandardCharsets.UTF_8) : null);
            } else if (method.getName().equals("get")) {
                if (this.lookupFailure != null) {
                    result.completeExceptionally(this.lookupFailure);
                } else {
                    String key = new String((byte[]) args[0], StandardCharsets.UTF_8);
                    result.complete(key.startsWith("sparrow-sync:server:") ? new byte[]{1}
                            : this.lockOwner == null ? null : this.lockOwner.getBytes(StandardCharsets.UTF_8));
                }
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
        NmsPlayerFixture.set(SparrowSync.class, plugin, "redisConnector", redis);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "sessionLock", new SessionLock(redis, "local"));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "messageBrokerManager", NmsPlayerFixture.allocate(MessageBrokerManager.class));
        this.service = service;
        this.remote = new RemoteSnapshotManager(plugin);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "remoteSnapshotManager", this.remote);
        this.handoff = new HandoffManager(plugin);
        this.handoff.onLoad();
        this.api = new SparrowSyncAPI(plugin);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "apiReady", true);
    }

    @AfterEach
    void cleanup() throws Exception {
        this.remote.shutdown();
        if (this.serial != null) this.serial.shutdown(2, TimeUnit.SECONDS);
        if (this.preparation != null) this.preparation.shutdownNow();
        replace(Bukkit.class, "server", this.oldBukkit);
        replace(PluginConfig.class, "config", this.oldConfig);
        replace(ServerConfig.class, "config", this.oldServerConfig);
    }

    @Test
    void captureUsesSyncModeAndAcknowledgesOnlyAfterStorageCompletes() throws Exception {
        CompletableFuture<SnapshotCaptureResult> completion = this.service.capture(this.player, SaveCause.COMMAND);
        Write write = this.nextWrite();
        assertFalse(completion.isDone());
        assertEquals("live", write.snapshot.data(this.key).getAsString());
        assertEquals(SaveCause.COMMAND, write.snapshot.meta().cause());
        write.complete();
        assertEquals(new SnapshotCaptureResult.Captured(write.snapshot.meta().id()), completion.get(2, TimeUnit.SECONDS));
    }

    @Test
    void captureDispatchesAsyncCallerAndCancellationKeepsAcceptedWrite() throws Exception {
        CompletableFuture<SnapshotCaptureResult> result = this.preparation.submit(() -> this.service.capture(this.player, SaveCause.COMMAND)).get(2, TimeUnit.SECONDS);
        assertTrue(this.writes.isEmpty());
        Runnable task = this.entityTasks.poll(2, TimeUnit.SECONDS);
        assertNotNull(task);
        result.cancel(false);
        task.run();
        Write write = this.nextWrite();
        assertEquals(SaveCause.COMMAND, write.snapshot.meta().cause());
        write.complete();
    }

    @Test
    void captureRejectsReplacedSessionBeforeCapture() throws Exception {
        CompletableFuture<SnapshotCaptureResult> result = this.preparation.submit(() -> this.service.capture(this.player, SaveCause.COMMAND)).get(2, TimeUnit.SECONDS);
        this.replaceActiveSession();
        Runnable task = this.entityTasks.poll(2, TimeUnit.SECONDS);
        assertNotNull(task);
        task.run();
        assertSame(SnapshotCaptureResult.OFFLINE, result.get(2, TimeUnit.SECONDS));
        assertTrue(this.writes.isEmpty());
    }

    @Test
    void captureReportsEventCancellationAndLocalStashAsUnstored() throws Exception {
        this.onSave = event -> event.setCancelled(true);
        assertSame(SnapshotCaptureResult.CANCELLED, this.service.capture(this.player, SaveCause.COMMAND).get(2, TimeUnit.SECONDS));
        assertTrue(this.writes.isEmpty());
        this.onSave = event -> {};
        CompletableFuture<SnapshotCaptureResult> result = this.service.capture(this.player, SaveCause.COMMAND);
        Write write = this.nextWrite();
        write.result.complete(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.REJECTED_MALFORMED, new IllegalArgumentException("rejected")));
        assertSame(SnapshotCaptureResult.FAILED, result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void operationsRejectInactiveSessionAndUnknownOrForeignSnapshots() throws Exception {
        assertSame(SnapshotRestoreResult.NOT_FOUND, this.service.restore(this.player, UUID.randomUUID()).get(2, TimeUnit.SECONDS));
        assertSame(SnapshotRestoreResult.WRONG_PLAYER, this.service.restore(this.player, this.source(UUID.randomUUID()).meta().id()).get(2, TimeUnit.SECONDS));
        this.plugin.sessionManager().find(this.uuid).transition(SessionState.SAVING);
        assertSame(SnapshotCaptureResult.OFFLINE, this.service.capture(this.player, SaveCause.COMMAND).get(2, TimeUnit.SECONDS));
        assertSame(SnapshotRestoreResult.OFFLINE, this.service.restore(this.player, UUID.randomUUID()).get(2, TimeUnit.SECONDS));
        assertTrue(this.entityTasks.isEmpty());
        assertTrue(this.writes.isEmpty());
    }

    @Test
    void restoreBindsSessionBeforeDatabaseRead() throws Exception {
        Snapshot source = this.source(this.uuid);
        this.snapshotRead = new CompletableFuture<>();
        CompletableFuture<SnapshotRestoreResult> result = this.service.restore(this.player, source.meta().id());
        this.replaceActiveSession();
        this.snapshotRead.complete(Optional.of(source));
        assertSame(SnapshotRestoreResult.OFFLINE, result.get(2, TimeUnit.SECONDS));
        assertTrue(this.entityTasks.isEmpty());
        assertTrue(this.writes.isEmpty());
        assertEquals("live", this.value.get());
    }

    @Test
    void restoreReportsSkippedTypesAndWaitsForNewRecord() throws Exception {
        this.skipOnlineData(this.key);
        Snapshot source = this.source(this.uuid);
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(source);
        assertEquals("live", this.value.get());
        Write write = this.nextWrite();
        assertFalse(result.isDone());
        assertNotEquals(source.meta().id(), write.snapshot.meta().id());
        assertEquals(VersionHelper.WORLD_VERSION, write.snapshot.meta().mcDataVersion());
        write.complete();
        SnapshotRestoreResult.Restored restored = assertInstanceOf(SnapshotRestoreResult.Restored.class, result.get(2, TimeUnit.SECONDS));
        assertEquals(write.snapshot.meta().id(), restored.snapshotId());
        assertTrue(restored.skipped().contains(this.key));
        assertThrows(UnsupportedOperationException.class, restored.skipped()::clear);
    }

    @Test
    void restoreReportsSaveCancellationAfterPlayerWasChanged() throws Exception {
        this.onSave = event -> event.setCancelled(true);
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.source(this.uuid));
        SnapshotRestoreResult.Cancelled cancelled = assertInstanceOf(SnapshotRestoreResult.Cancelled.class, result.get(2, TimeUnit.SECONDS));
        assertEquals(SnapshotRestoreResult.Stage.SAVE, cancelled.stage());
        assertEquals("history", this.value.get());
        assertTrue(this.writes.isEmpty());
    }

    @Test
    void restoreReportsUnstoredResultAfterPlayerWasChanged() throws Exception {
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.source(this.uuid));
        Write write = this.nextWrite();
        write.result.complete(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.REJECTED_MALFORMED, null));
        SnapshotRestoreResult.Failed failed = assertInstanceOf(SnapshotRestoreResult.Failed.class, result.get(2, TimeUnit.SECONDS));
        assertEquals(SnapshotRestoreResult.Stage.SAVE, failed.stage());
        assertEquals("history", this.value.get());
    }

    @Test
    void restorePreservesExceptionAfterPlayerWasChanged() throws Exception {
        RuntimeException failure = new IllegalStateException("save event failed");
        this.onSave = event -> { throw failure; };
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.source(this.uuid));
        SnapshotRestoreResult.Failed failed = assertInstanceOf(SnapshotRestoreResult.Failed.class, result.get(2, TimeUnit.SECONDS));
        assertEquals(SnapshotRestoreResult.Stage.SAVE, failed.stage());
        assertSame(failure, failed.cause());
        assertEquals("history", this.value.get());
    }

    @Test
    void restoreDistinguishesPreparationAndApplicationFailure() throws Exception {
        this.decodeFailure = new IllegalArgumentException("invalid type data");
        Snapshot source = this.source(this.uuid);
        SnapshotRestoreResult.Failed preparation = assertInstanceOf(SnapshotRestoreResult.Failed.class, this.service.restore(this.player, source.meta().id()).get(2, TimeUnit.SECONDS));
        assertEquals(SnapshotRestoreResult.Stage.PREPARE, preparation.stage());
        assertEquals("live", this.value.get());
        assertTrue(this.entityTasks.isEmpty());
        this.decodeFailure = null;
        this.applyFailure = new IllegalArgumentException("apply failed");
        SnapshotRestoreResult.Failed applied = assertInstanceOf(SnapshotRestoreResult.Failed.class, this.restoreAndRun(source).get(2, TimeUnit.SECONDS));
        assertEquals(SnapshotRestoreResult.Stage.APPLY, applied.stage());
        assertEquals("history", this.value.get());
        assertTrue(this.writes.isEmpty());
    }

    @Test
    void restoreReportsNonCriticalFailureAsSkipped() throws Exception {
        this.critical = false;
        this.applyFailure = new IllegalArgumentException("optional type failed");
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.source(this.uuid));
        this.nextWrite().complete();
        SnapshotRestoreResult.Restored restored = assertInstanceOf(SnapshotRestoreResult.Restored.class, result.get(2, TimeUnit.SECONDS));
        assertTrue(restored.skipped().contains(this.key));
    }

    @SuppressWarnings("unchecked")
    private void replaceActiveSession() throws Exception {
        SessionManager sessions = this.plugin.sessionManager();
        var field = SessionManager.class.getDeclaredField("sessions");
        field.setAccessible(true);
        ((Map<?, ?>) field.get(sessions)).clear();
        sessions.tryOpen(this.uuid, "Steve", ConnectionFixture.create()).transition(SessionState.ACTIVE);
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
                () -> this.remote.receiveCapture(this.uuid, SaveCause.COMMAND), this.preparation).thenCompose(result -> result);
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
        assertEquals(source.allData(), write.snapshot.allData());
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

    @Test
    void apiSavesLocallyWithApiCauseAndRestoresAfterStorageAcknowledgement() throws Exception {
        CompletableFuture<SnapshotCaptureResult> saved = this.api.save(this.uuid);
        Write savedWrite = this.nextWrite();
        assertEquals(SaveCause.API, savedWrite.snapshot.meta().cause());
        assertFalse(saved.isDone());
        savedWrite.complete();
        assertInstanceOf(SnapshotCaptureResult.Captured.class, saved.get(2, TimeUnit.SECONDS));
        Snapshot source = this.source(this.uuid);
        CompletableFuture<SnapshotRestoreResult> restored = this.api.restore(this.uuid, source.meta().id());
        Runnable apply = this.entityTasks.poll(2, TimeUnit.SECONDS);
        assertNotNull(apply);
        apply.run();
        Write restoredWrite = this.nextWrite();
        assertEquals("history", this.value.get());
        assertEquals(SaveCause.RESTORE, restoredWrite.snapshot.meta().cause());
        assertNotEquals(source.meta().id(), restoredWrite.snapshot.meta().id());
        assertFalse(restored.isDone());
        restoredWrite.complete();
        assertInstanceOf(SnapshotRestoreResult.Restored.class, restored.get(2, TimeUnit.SECONDS));
        assertEquals(0, this.readyEvents);
    }

    @Test
    void apiOfflineRestoreWritesNewHistoryAndReleasesLockBeforeCompletion() throws Exception {
        this.offlineApiTarget();
        Snapshot source = this.source(this.uuid);
        assertSame(SnapshotCaptureResult.OFFLINE, this.api.save(this.uuid).join());
        CompletableFuture<SnapshotRestoreResult> restored = this.api.restore(this.uuid, source.meta().id());
        Write write = this.nextWrite();
        assertTrue(this.locked.get());
        assertFalse(restored.isDone());
        assertEquals(SaveCause.RESTORE, write.snapshot.meta().cause());
        assertNotEquals(source.meta().id(), write.snapshot.meta().id());
        assertSame(source.content(), write.snapshot.content());
        write.complete();
        assertInstanceOf(SnapshotRestoreResult.RestoredOffline.class, restored.get(2, TimeUnit.SECONDS));
        assertFalse(this.locked.get());
        assertFalse(this.service.restoringOffline(this.uuid));
        assertEquals("live", this.value.get());
    }

    @Test
    void apiRejectsInactiveLocalSessionBeforeRouting() {
        this.online.set(false);
        this.plugin.sessionManager().find(this.uuid).transition(SessionState.SAVING);
        assertSame(SnapshotCaptureResult.OFFLINE, this.api.save(this.uuid).join());
        assertSame(SnapshotRestoreResult.OFFLINE, this.api.restore(this.uuid, UUID.randomUUID()).join());
        assertEquals(0, this.lockAttempts);
    }

    @Test
    void apiOfflineRestoreRechecksLockAfterRouting() {
        this.offlineApiTarget();
        this.locked.set(true);
        Snapshot source = this.source(this.uuid);
        assertSame(SnapshotRestoreResult.OFFLINE, this.api.restore(this.uuid, source.meta().id()).join());
        assertEquals(1, this.lockAttempts);
        assertTrue(this.writes.isEmpty());
        assertFalse(this.service.restoringOffline(this.uuid));
    }

    @Test
    void apiUsesUuidRosterAndDoesNotFallBackAfterRemoteTimeout() throws Exception {
        this.offlineApiTarget();
        this.remotePresence();
        Snapshot source = this.source(this.uuid);
        List<Object> sent = new ArrayList<>();
        CompletableFuture<SnapshotRestoreResponseMessage> response = new CompletableFuture<>();
        this.remoteResponses(response, sent);
        CompletableFuture<SnapshotRestoreResult> restored = this.api.restore(this.uuid, source.meta().id());
        assertEquals(1, sent.size());
        assertInstanceOf(SnapshotRestoreRequestMessage.class, sent.getFirst());
        assertFalse(restored.isDone());
        response.completeExceptionally(new TimeoutException("response lost"));
        assertSame(SnapshotRestoreResult.UNAVAILABLE, restored.join());
        assertEquals(1, sent.size());
        assertEquals(0, this.lockAttempts);
        assertTrue(this.writes.isEmpty());
    }

    @Test
    void apiFallsBackToLockHolderForRemoteSaveAndPreservesCause() throws Exception {
        this.offlineApiTarget();
        this.lockOwner = "remote:" + UUID.randomUUID();
        List<Object> sent = new ArrayList<>();
        CompletableFuture<SnapshotCaptureResponseMessage> response = new CompletableFuture<>();
        this.remoteResponses(response, sent);
        CompletableFuture<SnapshotCaptureResult> saved = this.api.save(this.uuid);
        assertEquals(1, sent.size());
        SnapshotCaptureRequestMessage request = assertInstanceOf(SnapshotCaptureRequestMessage.class, sent.getFirst());
        Field cause = SnapshotCaptureRequestMessage.class.getDeclaredField("cause");
        cause.setAccessible(true);
        assertSame(SaveCause.API, cause.get(request));
        UUID savedId = UUID.randomUUID();
        response.complete(new SnapshotCaptureResponseMessage(new SnapshotCaptureResult.Captured(savedId)));
        assertEquals(new SnapshotCaptureResult.Captured(savedId), saved.join());
        assertEquals(0, this.lockAttempts);
    }

    @Test
    void apiLockHolderRestorePreservesAcknowledgedResult() {
        this.offlineApiTarget();
        this.lockOwner = "remote:" + UUID.randomUUID();
        List<Object> sent = new ArrayList<>();
        CompletableFuture<SnapshotRestoreResponseMessage> response = new CompletableFuture<>();
        this.remoteResponses(response, sent);
        CompletableFuture<SnapshotRestoreResult> restored = this.api.restore(this.uuid, UUID.randomUUID());
        SnapshotRestoreResult result = new SnapshotRestoreResult.Cancelled(SnapshotRestoreResult.Stage.SAVE, List.of(this.key));
        response.complete(new SnapshotRestoreResponseMessage(result));
        assertEquals(result, restored.join());
        assertEquals(1, sent.size());
        assertEquals(0, this.lockAttempts);
    }

    @Test
    void apiLocalLockAndLookupFailureDoNotBecomeOfflineOperations() {
        this.offlineApiTarget();
        this.lockOwner = "local:" + UUID.randomUUID();
        assertSame(SnapshotCaptureResult.OFFLINE, this.api.save(this.uuid).join());
        assertSame(SnapshotRestoreResult.OFFLINE, this.api.restore(this.uuid, UUID.randomUUID()).join());
        this.lookupFailure = new IllegalStateException("redis unavailable");
        assertSame(this.lookupFailure, assertThrows(CompletionException.class, () -> this.api.save(this.uuid).join()).getCause());
        assertSame(this.lookupFailure, assertThrows(CompletionException.class, () -> this.api.restore(this.uuid, UUID.randomUUID()).join()).getCause());
        assertEquals(0, this.lockAttempts);
    }

    @Test
    void remoteReceiverReportsOfflineWhenSessionHasGone() {
        this.offlineApiTarget();
        assertSame(SnapshotCaptureResult.OFFLINE, this.remote.receiveCapture(this.uuid, SaveCause.API).join());
        assertSame(SnapshotRestoreResult.OFFLINE, this.remote.receiveRestore(this.uuid, UUID.randomUUID()).join());
        assertEquals(0, this.lockAttempts);
    }

    @ParameterizedTest
    @EnumSource(value = SaveCause.class, names = {"API", "COMMAND"})
    void decodedRemoteCaptureRequestSavesItsOriginalCause(SaveCause cause) throws Exception {
        SnapshotCaptureRequestMessage request = new SnapshotCaptureRequestMessage(this.uuid, cause);
        request.setMessageId(12);
        request.setSourceServer("remote");
        request.setTargetServer("local");
        SnapshotCaptureRequestMessage.receiver(this.remote);
        ByteBuf buffer = Unpooled.buffer();
        try {
            SnapshotCaptureRequestMessage.CODEC.encode(buffer, request);
            CompletableFuture<SnapshotCaptureResponseMessage> response = SnapshotCaptureRequestMessage.CODEC.decode(buffer).handleRequest();
            Write write = this.nextWrite();
            assertEquals(cause, write.snapshot.meta().cause());
            assertFalse(response.isDone());
            write.complete();
            assertInstanceOf(SnapshotCaptureResult.Captured.class, response.get(2, TimeUnit.SECONDS).result());
        } finally {
            buffer.release();
        }
    }

    @Test
    void readyEventRunsInsideJoinAfterApplyAndCanSaveImmediately() throws Exception {
        Snapshot source = this.source(this.uuid);
        this.prepareJoin(source, false);
        AtomicBoolean joining = new AtomicBoolean(true);
        AtomicReference<CompletableFuture<SnapshotCaptureResult>> saved = new AtomicReference<>();
        this.onReady = event -> {
            assertTrue(joining.get());
            assertEquals("history", this.value.get());
            assertEquals(SessionState.ACTIVE, this.plugin.sessionManager().find(this.uuid).state());
            assertSame(source, event.snapshot());
            saved.set(this.api.save(this.uuid));
        };
        this.join();
        joining.set(false);
        assertEquals(1, this.readyEvents);
        Write write = this.nextWrite();
        assertEquals(SaveCause.API, write.snapshot.meta().cause());
        write.complete();
        assertInstanceOf(SnapshotCaptureResult.Captured.class, saved.get().get(2, TimeUnit.SECONDS));
        this.join();
        assertEquals(1, this.readyEvents);
    }

    @Test
    void readyEventIncludesNewPlayersWithoutHistory() throws Exception {
        this.prepareJoin(null, false);
        this.onReady = event -> {
            assertNull(event.snapshot());
            assertTrue(event.skipped().isEmpty());
        };
        this.join();
        assertEquals(1, this.readyEvents);
        assertEquals(0, this.kicks);
    }

    @Test
    void readyEventWaitsForNativeJoinHandoff() throws Exception {
        this.prepareJoin(this.source(this.uuid), true);
        this.onReady = event -> assertEquals("native handoff", this.value.get());
        this.join();
        assertEquals(1, this.readyEvents);
    }

    @Test
    void readyEventReportsNonCriticalSkippedTypes() throws Exception {
        this.critical = false;
        this.prepareJoin(this.source(this.uuid), false);
        this.applyFailure = new IllegalStateException("optional apply failed");
        this.onReady = event -> {
            assertEquals(List.of(this.key), event.skipped());
            assertThrows(UnsupportedOperationException.class, event.skipped()::clear);
        };
        this.join();
        assertEquals(1, this.readyEvents);
        assertEquals(0, this.kicks);
    }

    @Test
    void failedApplicationDoesNotPublishReadyEvent() throws Exception {
        this.prepareJoin(this.source(this.uuid), false);
        this.applyFailure = new IllegalStateException("critical apply failed");
        assertInstanceOf(SnapshotApplyResult.Failed.class, this.activate(this.plugin.sessionManager().find(this.uuid)));
        assertEquals(0, this.readyEvents);
    }

    @Test
    void abortedSessionDoesNotPublishReadyEvent() throws Exception {
        this.prepareJoin(null, false);
        PlayerSession session = this.plugin.sessionManager().find(this.uuid);
        this.plugin.sessionManager().abort(session);
        assertSame(SnapshotApplyResult.REJECTED, this.activate(session));
        assertEquals(0, this.readyEvents);
    }

    private void offlineApiTarget() {
        this.online.set(false);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "sessionManager", new SessionManager(this.plugin));
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "playerDirectory", new PlayerDirectory(this.plugin));
    }

    private void remotePresence() throws Exception {
        Method presence = PlayerDirectory.class.getDeclaredMethod("acceptPresence", PlayerPresenceMessage.class);
        presence.setAccessible(true);
        presence.invoke(this.plugin.playerDirectory(), new PlayerPresenceMessage("remote", this.uuid, "Steve", true));
    }

    @SuppressWarnings("unchecked")
    private void remoteResponses(CompletableFuture<?> response, List<Object> sent) {
        MessageBroker<ByteBuf> broker = proxy(MessageBroker.class, (instance, method, args) -> {
            assertEquals("publishTwoWay", method.getName());
            assertEquals("remote", args[1]);
            sent.add(args[0]);
            return response;
        });
        NmsPlayerFixture.set(MessageBrokerManager.class, this.plugin.messageBrokerManager(), "broker", broker);
    }

    private void prepareJoin(Snapshot snapshot, boolean nativeApplied) throws Exception {
        SessionManager sessions = this.plugin.sessionManager();
        NmsPlayerFixture.set(SessionManager.class, sessions, "sessions", new ConcurrentHashMap<>());
        PlayerSession session = sessions.tryOpen(this.uuid, "Steve", ConnectionFixture.create());
        SnapshotLoadResult.Ready loaded = null;
        if (snapshot != null) {
            PlayerDataPipeline.DecodeResult decoded = this.preparation.submit(() -> this.plugin.playerDataPipeline().decode(snapshot)).get(2, TimeUnit.SECONDS);
            SnapshotApplyContext context = assertInstanceOf(PlayerDataPipeline.DecodeResult.Ready.class, decoded).context();
            if (nativeApplied) {
                Method applied = SnapshotApplyContext.class.getDeclaredMethod("appliedNative", int.class, Consumer.class);
                applied.setAccessible(true);
                applied.invoke(context, this.plugin.dataRegistry().slot(this.key), (Consumer<Player>) player -> this.value.set("native handoff"));
            }
            loaded = new SnapshotLoadResult.Ready(snapshot, context, 0);
        }
        Class<?> preloadType = Class.forName("net.momirealms.sparrow.sync.session.PlayerDataPreload");
        var readyConstructor = Class.forName(preloadType.getName() + "$Ready").getDeclaredConstructor(Optional.class);
        readyConstructor.setAccessible(true);
        Method publish = PlayerSession.class.getDeclaredMethod("publishLoginData", preloadType, SnapshotLoadResult.Ready.class, long.class, long.class);
        publish.setAccessible(true);
        publish.invoke(session, readyConstructor.newInstance(Optional.empty()), loaded, 0L, 0L);
        session.loadPlayerData(() -> { throw new AssertionError("preloaded data expected"); });
        NmsPlayerFixture.set(PlayerDirectory.class, this.plugin.playerDirectory(), "closed", true);
    }

    private void join() {
        new SessionListener(this.plugin, this.plugin.sessionManager()).onJoin(new PlayerJoinEvent(this.player, Component.empty()));
    }

    private SnapshotApplyResult activate(PlayerSession session) throws Exception {
        Method activate = SessionManager.class.getDeclaredMethod("activate", PlayerSession.class, Player.class);
        activate.setAccessible(true);
        return (SnapshotApplyResult) activate.invoke(this.plugin.sessionManager(), session, this.player);
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
        this.skipOnlineData();
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
        this.skipOnlineData();
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
        this.skipOnlineData();
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.sourceWithHealth(0));
        this.teleport.complete(false);
        assertEquals(SnapshotRestoreResult.Stage.APPLY, assertInstanceOf(SnapshotRestoreResult.Failed.class, result.get(2, TimeUnit.SECONDS)).stage());
        assertFalse(this.dead.get());
        assertTrue(this.writes.isEmpty());
    }

    @Test
    void removingHealthFromEventKeepsDeadPlayerDead() throws Exception {
        this.skipOnlineData(LocationDataType.LOCATION);
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
        this.skipOnlineData(LocationDataType.LOCATION);
        this.dead.set(true);
        this.onRespawn = () -> this.online.set(false);
        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(this.sourceWithHealth(18));
        assertEquals(SnapshotRestoreResult.Stage.APPLY, assertInstanceOf(SnapshotRestoreResult.Failed.class, result.get(2, TimeUnit.SECONDS)).stage());
        assertEquals(List.of("respawn"), this.actions);
        assertTrue(this.writes.isEmpty());
    }

    @Test
    void onlineRestoreSkipsConfiguredCustomDataAndKeepsStoredSnapshot() throws Exception {
        this.skipOnlineData(this.key);
        this.onPreApply = event -> assertFalse(event.decoded().containsKey(this.key));
        Snapshot source = this.source(this.uuid);

        CompletableFuture<SnapshotRestoreResult> result = this.restoreAndRun(source);

        assertEquals("live", this.value.get());
        assertTrue(this.actions.isEmpty());
        this.nextWrite().complete();
        assertInstanceOf(SnapshotRestoreResult.Restored.class, result.get(2, TimeUnit.SECONDS));
        assertSame(source, this.stored.get(source.meta().id()));
    }

    private void skipOnlineData(DataKey... keys) throws Exception {
        Field config = PluginConfig.class.getDeclaredField("config");
        config.setAccessible(true);
        Field synchronization = PluginConfig.ConfigDefinition.class.getDeclaredField("synchronization");
        synchronization.setAccessible(true);
        NmsPlayerFixture.set(PluginConfig.SynchronizationOptions.class, synchronization.get(config.get(null)), "skipOnlineRestoreData", List.of(keys));
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
            return SnapshotCommandFlowTest.this.critical;
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
        public String decode(@NotNull Tag data) {
            assertNotSame(SnapshotCommandFlowTest.this.entityThread, Thread.currentThread());
            if (SnapshotCommandFlowTest.this.decodeFailure != null) throw SnapshotCommandFlowTest.this.decodeFailure;
            return data.getAsString();
        }
        @Override
        public void apply(@NotNull Player player, @NotNull String value) {
            assertSame(SnapshotCommandFlowTest.this.entityThread, Thread.currentThread());
            SnapshotCommandFlowTest.this.actions.add("data");
            SnapshotCommandFlowTest.this.value.set(value);
            if (SnapshotCommandFlowTest.this.applyFailure != null) throw SnapshotCommandFlowTest.this.applyFailure;
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
        public HealthDataType.Health decode(@NotNull Tag data) {
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
        public LocationDataType.PlayerLocation decode(@NotNull Tag value) { return new LocationDataType.PlayerLocation("world", 10, 64, 20, 0, 0); }
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
