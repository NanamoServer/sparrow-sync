package net.momirealms.sparrow.sync.snapshot;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.StringTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataStoragePatch;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerJsonStorage;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SessionPrepareResult;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.NoopSnapshotCache;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(PluginConfigExtension.class)
class NativeSessionReleaseTest {
    private static final DataKey KEY = DataKey.of("test", "native_file");

    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);
    private final AtomicInteger releases = new AtomicInteger();
    private final AtomicInteger nativeCalls = new AtomicInteger();
    private CompletableFuture<Optional<Snapshot>> latest;
    private boolean nativeFailure;
    private SessionManager sessions;
    private PlayerSerialExecutor serialExecutor;

    @BeforeEach
    void setup() {
        this.latest = CompletableFuture.completedFuture(Optional.of(this.snapshot("old")));
        SyncLogger logger = new SyncLogger(proxy(PluginLogger.class, (instance, method, args) -> null));
        this.serialExecutor = new PlayerSerialExecutor(logger, 1);
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", logger);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "playerExecutor", this.serialExecutor);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "scheduler", proxy(SchedulerAdapter.class, (instance, method, args) -> {
            assertEquals("async", method.getName());
            return this.executor;
        }));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "snapshotCache", new NoopSnapshotCache());
        NmsPlayerFixture.set(SparrowSync.class, plugin, "storageProvider", proxy(StorageProvider.class, (instance, method, args) -> {
            assertEquals("latestSnapshot", method.getName());
            return this.latest;
        }));
        DataRegistry registry = new DataRegistry();
        registry.register(new NativeFileType());
        registry.freeze();
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataRegistry", registry);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "mapSyncService", new MapSyncService(plugin));
        PlayerDataPipeline pipeline = new PlayerDataPipeline(plugin);
        pipeline.onLoad();
        NmsPlayerFixture.set(SparrowSync.class, plugin, "playerDataPipeline", pipeline);
        SnapshotService service = new SnapshotService(plugin);
        NmsPlayerFixture.set(SnapshotService.class, service, "applier", new SnapshotApplier(plugin));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "snapshotService", service);
        RedisAsyncCommands<?, ?> commands = proxy(RedisAsyncCommands.class, (instance, method, args) -> {
            assertEquals("eval", method.getName());
            this.releases.incrementAndGet();
            AsyncCommand<byte[], byte[], Long> result = new AsyncCommand<>(new Command<>(CommandType.EVAL, null));
            result.complete(1L);
            return result;
        });
        RedisConnector redis = NmsPlayerFixture.allocate(RedisConnector.class);
        NmsPlayerFixture.set(RedisConnector.class, redis, "connection", proxy(StatefulRedisConnection.class, (instance, method, args) -> {
            assertEquals("async", method.getName());
            return commands;
        }));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "sessionLock", new SessionLock(redis, "test"));
        this.sessions = new SessionManager(plugin);
        this.sessions.onLoad();
        NmsPlayerFixture.set(SessionManager.class, this.sessions, "playerDataStorage", NmsPlayerFixture.allocate(LocalData.class));
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        this.proceed.countDown();
        this.executor.shutdownNow();
        assertTrue(this.executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, this.serialExecutor.shutdown(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void closingDuringNativeWriteReleasesImmediatelyAndNextLoginWritesAfterIt(boolean finalClose, boolean fails) throws Exception {
        this.nativeFailure = fails;
        PlayerSession first = this.open();
        CompletableFuture<SessionPrepareResult> preparing = this.sessions.prepare(first);
        assertTrue(this.entered.await(5, TimeUnit.SECONDS));
        CompletableFuture.runAsync(() -> this.close(first, finalClose), this.executor).get(5, TimeUnit.SECONDS);
        assertEquals(SessionState.CLOSED, first.state());
        first.released().get(5, TimeUnit.SECONDS);
        assertEquals(1, this.releases.get());
        assertNull(this.sessions.find(this.player));
        assertFalse(this.sessions.abort(first));
        this.latest = CompletableFuture.completedFuture(Optional.of(this.snapshot("new")));
        PlayerSession second = this.open();
        CompletableFuture<SessionPrepareResult> nextPreparing = this.sessions.prepare(second);
        this.awaitQueued(1);
        assertFalse(nextPreparing.isDone());
        assertEquals(1, this.nativeCalls.get());
        assertFalse(Files.exists(this.directory.resolve(this.player + ".json")));
        this.proceed.countDown();
        assertSame(SessionPrepareResult.REJECTED, preparing.get(5, TimeUnit.SECONDS));
        assertSame(SessionPrepareResult.READY, nextPreparing.get(5, TimeUnit.SECONDS));
        assertEquals(2, this.nativeCalls.get());
        assertEquals("new", Files.readString(this.directory.resolve(this.player + ".json")));
        assertTrue(this.sessions.abort(second));
        second.released().get(5, TimeUnit.SECONDS);
        assertEquals(2, this.releases.get());
    }

    @Test
    void abortBeforeQueuedNativeStartsSkipsItsWrite() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CompletableFuture<Void> unblock = new CompletableFuture<>();
        this.serialExecutor.submit(this.player, () -> {
            blocked.countDown();
            unblock.join();
        });
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            PlayerSession first = this.open();
            CompletableFuture<SessionPrepareResult> preparing = this.sessions.prepare(first);
            this.awaitQueued(1);
            assertTrue(this.sessions.abort(first));
            first.released().get(5, TimeUnit.SECONDS);
            assertEquals(1, this.releases.get());
            this.latest = CompletableFuture.completedFuture(Optional.of(this.snapshot("new")));
            PlayerSession second = this.open();
            CompletableFuture<SessionPrepareResult> nextPreparing = this.sessions.prepare(second);
            this.awaitQueued(2);
            this.proceed.countDown();
            unblock.complete(null);
            assertSame(SessionPrepareResult.REJECTED, preparing.get(5, TimeUnit.SECONDS));
            assertSame(SessionPrepareResult.READY, nextPreparing.get(5, TimeUnit.SECONDS));
            assertEquals(1, this.nativeCalls.get());
            assertEquals("new", Files.readString(this.directory.resolve(this.player + ".json")));
            assertSame(second, this.sessions.find(this.player));
            assertTrue(this.sessions.abort(second));
        } finally {
            unblock.complete(null);
        }
    }

    @Test
    void abortWhileReadingReleasesImmediatelyAndLateResultCannotWrite() throws Exception {
        CompletableFuture<Optional<Snapshot>> oldRead = new CompletableFuture<>();
        this.latest = oldRead;
        PlayerSession first = this.open();
        CompletableFuture<SessionPrepareResult> preparing = this.sessions.prepare(first);
        assertTrue(this.sessions.abort(first));
        first.released().get(5, TimeUnit.SECONDS);
        assertEquals(1, this.releases.get());
        this.proceed.countDown();
        this.latest = CompletableFuture.completedFuture(Optional.of(this.snapshot("new")));
        PlayerSession second = this.open();
        assertSame(SessionPrepareResult.READY, this.sessions.prepare(second).get(5, TimeUnit.SECONDS));
        oldRead.complete(Optional.of(this.snapshot("old")));
        assertSame(SessionPrepareResult.REJECTED, preparing.get(5, TimeUnit.SECONDS));
        assertEquals(1, this.nativeCalls.get());
        assertEquals("new", Files.readString(this.directory.resolve(this.player + ".json")));
        assertSame(second, this.sessions.find(this.player));
        assertTrue(this.sessions.abort(second));
    }

    private PlayerSession open() {
        PlayerSession session = this.sessions.tryOpen(this.player, "Steve", ConnectionFixture.create());
        assertNotNull(session);
        NmsPlayerFixture.set(PlayerSession.class, session, "lockToken", "test:" + UUID.randomUUID());
        return session;
    }

    private void awaitQueued(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.serialExecutor.pendingTasks() < count && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(count, this.serialExecutor.pendingTasks());
    }

    private void close(PlayerSession session, boolean finalClose) {
        if (!finalClose) {
            assertTrue(this.sessions.abort(session));
            return;
        }
        try {
            Method close = SessionManager.class.getDeclaredMethod("closeWithFinalSave", PlayerSession.class, Supplier.class);
            close.setAccessible(true);
            assertEquals("ABORTED", close.invoke(this.sessions, session, (Supplier<?>) () -> {
                throw new AssertionError("preparing sessions must not save");
            }).toString());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private Snapshot snapshot(String value) {
        return new Snapshot(new SnapshotMeta(UUID.randomUUID(), this.player, 1, SaveCause.DISCONNECT, false, "test", 0), Map.of(KEY, NBT.createString(value)));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private final class NativeFileType implements NativePlayerDataType<Tag> {
        @Override
        @NotNull
        public DataKey key() { return KEY; }
        @Override
        @NotNull
        public Tag capture(@NotNull Player player, @NotNull CaptureMode mode) { throw new AssertionError(); }
        @Override
        @NotNull
        public Tag encode(@NotNull Tag value) { return value; }
        @Override
        @NotNull
        public Tag decode(@NotNull Tag value) { return value; }
        @Override
        public void apply(@NotNull Player player, @NotNull Tag value) { throw new AssertionError(); }
        @Override
        public boolean shouldApply(@NotNull PlayerSession session) { return true; }

        @Override
        @NotNull
        public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull net.momirealms.sparrow.nbt.CompoundTag playerData, @NotNull Tag value) throws IOException {
            NativeSessionReleaseTest.this.nativeCalls.incrementAndGet();
            NativeSessionReleaseTest.this.entered.countDown();
            try {
                if (!NativeSessionReleaseTest.this.proceed.await(5, TimeUnit.SECONDS)) throw new IOException("native test write timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
            if (NativeSessionReleaseTest.this.nativeFailure && ((StringTag) value).getAsString().equals("old")) {
                throw new IOException("native test write failed");
            }
            try {
                Method materialize = PlayerJsonStorage.class.getDeclaredMethod("materialize", Path.class, byte[].class);
                materialize.setAccessible(true);
                Path target = NativeSessionReleaseTest.this.directory.resolve(session.uuid() + ".json");
                assertEquals(true, materialize.invoke(null, target, ((StringTag) value).getAsString().getBytes(StandardCharsets.UTF_8)));
            } catch (ReflectiveOperationException failure) {
                throw new IOException(failure);
            }
            return NativeApplyResult.APPLIED_EXTERNAL;
        }
    }

    private static final class LocalData extends PlayerDataStoragePatch {
        private LocalData() { super(null, null, null, Map.of()); }

        @Override
        @NotNull
        public Optional<CompoundTag> loadOriginal(@NotNull UUID player, @NotNull String playerName) {
            return Optional.of(new CompoundTag());
        }
    }
}
