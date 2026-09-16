package net.momirealms.sparrow.sync.snapshot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.locale.TranslationManagerImpl;
import net.momirealms.sparrow.sync.map.MapPublisher;
import net.momirealms.sparrow.sync.map.MapStorage;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.map.cache.MapCache;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotStash;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveOutcome;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.NoopSnapshotCache;
import net.momirealms.sparrow.sync.test.MemorySnapshotCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotSaveLifecycleTest {
    private static final UUID PLAYER = new UUID(0, 23);
    private static final DataKey RETAINED = DataKey.of("external", "retained");
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE);
    private final LinkedBlockingQueue<Submission> submissions = new LinkedBlockingQueue<>();
    private final AtomicInteger rotations = new AtomicInteger();
    private final AtomicInteger stashReports = new AtomicInteger();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<String> shutdownLogs = new LinkedBlockingQueue<>();
    private Object previousTranslations;
    private volatile Runnable onWarning = () -> {};
    private Function<Snapshot, CompletableFuture<SaveOutcome>> onSave;
    private PluginConfig.ConfigDefinition config;
    private Object previousConfig;
    private SnapshotWriter writer;
    private final MemorySnapshotCache cache = new MemorySnapshotCache();
    private PlayerSerialExecutor executor;

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() throws Exception {
        this.config = new PluginConfig.ConfigDefinition();
        Field configField = PluginConfig.class.getDeclaredField("config");
        configField.setAccessible(true);
        this.previousConfig = configField.get(null);
        configField.set(null, this.config);
        Field translationField = TranslationManagerImpl.class.getDeclaredField("instance");
        translationField.setAccessible(true);
        this.previousTranslations = translationField.get(null);
        translationField.set(null, Proxy.newProxyInstance(TranslationManager.class.getClassLoader(), new Class<?>[]{TranslationManager.class},
                (proxy, method, args) -> args[0] + " " + String.join(",", (String[]) args[1])));
        PluginLogger console = (PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (proxy, method, args) -> {
            String message = (String) args[0];
            if (message.startsWith("log.sync.shutdown_")) this.shutdownLogs.add(message);
            if (method.getName().equals("warn") && (message.startsWith(LogConstants.STASH_PENDING + " ") || message.startsWith(LogConstants.STASH_EXCEPTION + " "))) {
                this.stashReports.incrementAndGet();
                this.onWarning.run();
            }
            if (args.length == 2 && args[1] instanceof Throwable failure) this.failures.add(failure);
            return null;
        });
        SyncLogger logger = new SyncLogger(console);
        this.executor = new PlayerSerialExecutor(console, 1);
        this.onSave = snapshot -> {
            CompletableFuture<SaveOutcome> outcome = new CompletableFuture<>();
            this.submissions.add(new Submission(snapshot, outcome));
            return outcome;
        };
        StorageProvider storage = (StorageProvider) Proxy.newProxyInstance(StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (proxy, method, args) -> switch (method.getName()) {
            case "saveSnapshotOutcome" -> this.onSave.apply((Snapshot) args[0]);
            case "rotate" -> {
                this.rotations.incrementAndGet();
                yield new CompletableFuture<Integer>();
            }
            default -> throw new AssertionError(method.getName());
        });
        this.writer = new SnapshotWriter(logger, storage, new SnapshotStash(this.directory, this.codec, logger, new NoopSnapshotCache()), this.executor, this.cache);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.executor.shutdown(2, TimeUnit.SECONDS);
        Field configField = PluginConfig.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(null, this.previousConfig);
        Field translationField = TranslationManagerImpl.class.getDeclaredField("instance");
        translationField.setAccessible(true);
        translationField.set(null, this.previousTranslations);
    }

    @Test
    void progressExtendsSavingBeyondTheOriginalTimeout() throws Exception {
        SaveRequest first = this.accept(false);
        SaveRequest second = this.accept(false);
        SaveRequest third = this.accept(false);
        long start = System.nanoTime();
        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> this.writer.sealAndAwaitSaves(500, TimeUnit.MILLISECONDS));
        assertEquals(LogConstants.SYNC_SHUTDOWN_PROGRESS + " 0,3", this.nextShutdownLog());
        try (var clock = Executors.newSingleThreadScheduledExecutor()) {
            clock.schedule(() -> first.fail(new IllegalStateException("finished")), 200, TimeUnit.MILLISECONDS);
            clock.schedule(() -> second.fail(new IllegalStateException("finished")), 650, TimeUnit.MILLISECONDS);
            clock.schedule(() -> third.fail(new IllegalStateException("finished")), 1050, TimeUnit.MILLISECONDS);
            assertTrue(waiting.get(3, TimeUnit.SECONDS));
        }
        assertTrue(System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(1000));
        assertTrue(this.shutdownLogs.contains(LogConstants.SYNC_SHUTDOWN_SUMMARY + " 0,0,3,0"));
    }

    @Test
    void stalledCountdownResetsAfterProgressAndThenExpires() throws Exception {
        SaveRequest first = this.accept(false);
        SaveRequest second = this.accept(false);
        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> this.writer.sealAndAwaitSaves(2, TimeUnit.SECONDS));
        assertEquals(LogConstants.SYNC_SHUTDOWN_PROGRESS + " 0,2", this.nextShutdownLog());
        assertEquals(LogConstants.SYNC_SHUTDOWN_STALLED + " 0,2,1,1", this.nextShutdownLog());
        first.completion().complete(SnapshotSaveResult.CANCELLED);
        assertEquals(LogConstants.SYNC_SHUTDOWN_PROGRESS + " 1,2", this.nextShutdownLog());
        assertEquals(LogConstants.SYNC_SHUTDOWN_STALLED + " 1,2,1,1", this.nextShutdownLog());
        assertFalse(waiting.get(3, TimeUnit.SECONDS));
        assertFalse(second.completion().isDone());
        assertTrue(this.shutdownLogs.contains(LogConstants.SYNC_SHUTDOWN_TIMEOUT + " 1,2"));
        assertTrue(this.shutdownLogs.contains(LogConstants.SYNC_SHUTDOWN_SUMMARY + " 0,1,0,1"));
    }

    @Test
    void allCompletedOutcomesWakeImmediatelyAndAreClassified() throws Exception {
        SaveRequest stored = this.accept(false);
        SaveRequest cancelled = this.accept(false);
        SaveRequest rejected = this.accept(false);
        SaveRequest exceptional = this.accept(false);
        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> this.writer.sealAndAwaitSaves(30, TimeUnit.SECONDS));
        assertEquals(LogConstants.SYNC_SHUTDOWN_PROGRESS + " 0,4", this.nextShutdownLog());
        stored.completion().complete(new SnapshotSaveResult.Settled(SaveResult.DUPLICATE, stored.meta().id()));
        cancelled.completion().complete(SnapshotSaveResult.CANCELLED);
        rejected.completion().complete(new SnapshotSaveResult.Settled(SaveResult.REJECTED_OVERSIZED, rejected.meta().id()));
        exceptional.fail(new IllegalStateException("failed"));
        assertTrue(waiting.get(500, TimeUnit.MILLISECONDS));
        assertEquals(LogConstants.SYNC_SHUTDOWN_SUMMARY + " 1,1,2,0", this.nextShutdownLog());
    }

    @Test
    void emptyBatchSkipsProgress() {
        assertTrue(this.writer.sealAndAwaitSaves(0, TimeUnit.SECONDS));
        assertTrue(this.shutdownLogs.isEmpty());
    }

    @Test
    void nonPositiveTimeoutDoesNotWait() {
        SaveRequest request = this.accept(false);
        assertFalse(this.writer.sealAndAwaitSaves(-1, TimeUnit.SECONDS));
        assertFalse(request.completion().isDone());
    }

    @Test
    void interruptedWaitPreservesRequestsAndInterruptFlag() throws Exception {
        SaveRequest request = this.accept(false);
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        Thread waiter = Thread.ofPlatform().start(() -> {
            boolean completed = this.writer.sealAndAwaitSaves(30, TimeUnit.SECONDS);
            outcome.complete(!completed && Thread.currentThread().isInterrupted());
        });
        try {
            assertEquals(LogConstants.SYNC_SHUTDOWN_PROGRESS + " 0,1", this.nextShutdownLog());
            waiter.interrupt();
            assertTrue(outcome.get(1, TimeUnit.SECONDS));
            assertFalse(request.completion().isDone());
            assertTrue(this.shutdownLogs.contains(LogConstants.SYNC_SHUTDOWN_INTERRUPTED + " "));
            assertTrue(this.shutdownLogs.contains(LogConstants.SYNC_SHUTDOWN_SUMMARY + " 0,0,0,1"));
        } finally {
            waiter.interrupt();
            waiter.join(1000);
        }
    }

    @Test
    void encodingAndRetriesDoNotResetTheIdleTimeout() throws Exception {
        this.maxRetries(-1);
        SaveRequest request = this.accept(true);
        this.onSave = snapshot -> {
            this.submissions.add(new Submission(snapshot, new CompletableFuture<>()));
            return CompletableFuture.completedFuture(new SaveOutcome(SaveResult.RETRY_LATER, null));
        };
        this.writer.write(request);
        assertFalse(this.writer.sealAndAwaitSaves(250, TimeUnit.MILLISECONDS));
        assertTrue(this.submissions.size() > 1);
        assertFalse(request.completion().isDone());
        this.writer.stashUnsettled();
        assertTrue(request.completion().isDone());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void shutdownTailSharesOneBudgetAndTimeoutAddsNoBudget(int mode) throws Exception {
        Field optionsField = PluginConfig.ConfigDefinition.class.getDeclaredField("synchronization");
        optionsField.setAccessible(true);
        Object options = optionsField.get(this.config);
        NmsPlayerFixture.set(options.getClass(), options, "shutdownTimeoutSeconds", 1);
        if (mode != 0) this.accept(true);
        BukkitProxy.init("1.21.8", List.of("paper"));
        Field serverField = MinecraftServer.class.getDeclaredField("SERVER");
        serverField.setAccessible(true);
        Object previousServer = serverField.get(null);
        MinecraftServer server = NmsPlayerFixture.allocate(DedicatedServer.class);
        NmsPlayerFixture.set(MinecraftServer.class, server, "running", false);
        serverField.set(null, server);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        this.executor.submit(PLAYER, () -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        Field loggerField = SnapshotWriter.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", loggerField.get(this.writer));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "playerExecutor", this.executor);
        SnapshotSaver saver = NmsPlayerFixture.allocate(SnapshotSaver.class);
        NmsPlayerFixture.set(SnapshotSaver.class, saver, "writer", this.writer);
        SnapshotService service = new SnapshotService(plugin);
        NmsPlayerFixture.set(SnapshotService.class, service, "saver", saver);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "snapshotService", service);
        MapStorage storage = (MapStorage) Proxy.newProxyInstance(MapStorage.class.getClassLoader(), new Class<?>[]{MapStorage.class},
                (proxy, method, args) -> { throw new AssertionError(method.getName()); });
        MapCache cache = (MapCache) Proxy.newProxyInstance(MapCache.class.getClassLoader(), new Class<?>[]{MapCache.class},
                (proxy, method, args) -> { throw new AssertionError(method.getName()); });
        MapPublisher publisher = new MapPublisher(storage, cache, "test", Runnable::run);
        Field pendingField = MapPublisher.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<MapSource, CompletableFuture<StoredMap>> pending = (Map<MapSource, CompletableFuture<StoredMap>>) pendingField.get(publisher);
        pending.put(new MapSource("test", 0), new CompletableFuture<>());
        MapSyncService maps = new MapSyncService(plugin);
        NmsPlayerFixture.set(MapSyncService.class, maps, "publisher", publisher);
        NmsPlayerFixture.set(MapSyncService.class, maps, "ownerId", "test");
        NmsPlayerFixture.set(SparrowSync.class, plugin, "mapSyncService", maps);
        try {
            long start = System.nanoTime();
            if (mode == 2) Thread.currentThread().interrupt();
            plugin.onPluginDisable();
            boolean wasInterrupted = Thread.interrupted();
            long elapsed = System.nanoTime() - start;
            assertTrue(interrupted.await(500, TimeUnit.MILLISECONDS));
            if (mode == 2) {
                assertTrue(wasInterrupted);
                assertTrue(elapsed < TimeUnit.MILLISECONDS.toNanos(500));
            } else {
                assertTrue(elapsed >= TimeUnit.MILLISECONDS.toNanos(900));
            }
            assertTrue(elapsed < TimeUnit.MILLISECONDS.toNanos(1800), "shutdown allocated another wait budget");
            assertTrue(this.shutdownLogs.contains(LogConstants.SYNC_SHUTDOWN_MAPS + " "));
            assertTrue(this.shutdownLogs.contains(LogConstants.SYNC_SHUTDOWN_EXECUTOR + " "));
            if (mode != 0) assertEquals(1, this.bodies().size());
        } finally {
            Thread.interrupted();
            serverField.set(null, previousServer);
        }
    }

    private String nextShutdownLog() throws InterruptedException {
        String message = this.shutdownLogs.poll(3, TimeUnit.SECONDS);
        assertNotNull(message, "missing shutdown log");
        return message;
    }

    @Test
    void shutdownWaitsPastFirstSubmissionAndIncludesEveryAcceptedSave() {
        SaveRequest first = this.accept(true);
        SaveRequest second = this.accept(true);
        this.writer.write(first);
        this.writer.write(second);
        this.nextSubmission().outcome().complete(new SaveOutcome(SaveResult.SAVED, null));
        assertFalse(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        assertFalse(second.completion().isDone());
        this.nextSubmission().outcome().complete(new SaveOutcome(SaveResult.SAVED, null));
        assertTrue(this.writer.sealAndAwaitSaves(1, TimeUnit.SECONDS));
        assertEquals(2, this.rotations.get());
    }

    @Test
    void failureDoesNotEndTheBatchWhileAnotherSaveIsPending() {
        SaveRequest failed = this.accept(false);
        SaveRequest pending = this.accept(false);
        failed.fail(new IllegalStateException("capture failed"));
        assertFalse(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        pending.fail(new IllegalStateException("encode failed"));
        assertTrue(this.writer.sealAndAwaitSaves(1, TimeUnit.SECONDS));
    }

    @Test
    void shutdownSealRejectsLaterSaveRequests() {
        this.accept(false);
        assertFalse(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        assertThrows(RejectedExecutionException.class, () -> this.accept(false));
    }

    @Test
    void storageTaskSubmittedByCaptureRunsBeforeExecutorShutdown() throws Exception {
        CountDownLatch captureStarted = new CountDownLatch(1);
        CountDownLatch continueCapture = new CountDownLatch(1);
        CountDownLatch storageRan = new CountDownLatch(1);
        SaveRequest request = this.accept(true);
        this.onSave = snapshot -> {
            CompletableFuture<SaveOutcome> outcome = new CompletableFuture<>();
            this.executor.submit(PLAYER, () -> {
                storageRan.countDown();
                outcome.complete(new SaveOutcome(SaveResult.SAVED, null));
            });
            return outcome;
        };
        this.executor.submit(PLAYER, () -> {
            captureStarted.countDown();
            await(continueCapture);
            this.writer.write(request);
        });
        assertTrue(captureStarted.await(2, TimeUnit.SECONDS));
        assertFalse(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        continueCapture.countDown();
        assertTrue(this.writer.sealAndAwaitSaves(2, TimeUnit.SECONDS));
        assertEquals(0, this.executor.shutdown(2, TimeUnit.SECONDS));
        assertEquals(0, storageRan.getCount());
    }

    @Test
    void concurrentCompletionsDrainTheAcceptedBatch() {
        List<SaveRequest> requests = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) requests.add(this.accept(false));
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < requests.size(); i++) {
                SaveRequest request = requests.get(i);
                pool.execute(() -> request.fail(new IllegalStateException("finished")));
            }
            assertTrue(this.writer.sealAndAwaitSaves(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentRegistrationCannotEscapeTheSealedBatch() throws Exception {
        List<SaveRequest> accepted = new CopyOnWriteArrayList<>();
        accepted.add(this.accept(false));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    try {
                        accepted.add(this.accept(false));
                    } catch (RejectedExecutionException exception) {
                        rejected.incrementAndGet();
                    }
                }, pool));
            }
            start.countDown();
            assertFalse(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
        }
        assertEquals(201, accepted.size() + rejected.get());
        for (int i = 0; i < accepted.size() - 1; i++) accepted.get(i).fail(new IllegalStateException("finished"));
        assertFalse(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        accepted.getLast().fail(new IllegalStateException("finished"));
        assertTrue(this.writer.sealAndAwaitSaves(1, TimeUnit.SECONDS));
    }

    @Test
    void retriesContinueWithinShutdownWaitAndKeepFirstWritePolicy() throws Exception {
        this.maxRetries(0);
        SaveRequest request = this.accept(true);
        this.maxRetries(1);
        this.writer.write(request);
        Submission first = this.nextSubmission();
        assertFalse(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        this.maxRetries(0);
        first.outcome().complete(new SaveOutcome(SaveResult.RETRY_LATER, null));
        Submission retry = this.nextSubmission();
        assertSame(first.snapshot(), retry.snapshot());
        retry.outcome().complete(new SaveOutcome(SaveResult.SAVED, null));
        assertTrue(this.writer.sealAndAwaitSaves(2, TimeUnit.SECONDS));
        assertEquals(SaveResult.SAVED, assertInstanceOf(SnapshotSaveResult.Settled.class, request.completion().join()).result());
    }

    @ParameterizedTest
    @EnumSource(SaveResult.class)
    void storageClassificationsKeepRotationAndStashDestinations(SaveResult result) throws Exception {
        this.maxRetries(0);
        SaveRequest request = this.accept(true);
        this.writer.write(request);
        this.nextSubmission().outcome().complete(new SaveOutcome(result, null));
        assertEquals(result, assertInstanceOf(SnapshotSaveResult.Settled.class, request.completion().get(2, TimeUnit.SECONDS)).result());
        assertEquals(result.stored() ? 1 : 0, this.rotations.get());
        assertEquals(result.stored() ? 0 : 1, this.bodies().size());
        if (!result.stored()) {
            String path = this.directory.relativize(this.bodies().getFirst()).toString().replace('\\', '/');
            assertTrue(path.startsWith(result.retriable() ? "snapshot/pending/" : "snapshot/exception/"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownStashesThePublishedBodyAndFreezesFurtherUpdates(boolean prepared) throws Exception {
        SaveRequest request = this.accept(true);
        Snapshot raw = request.snapshot();
        Snapshot updated = new Snapshot(request.meta(), Map.of(RETAINED, NBT.createString("prepared")));
        if (prepared) assertTrue(request.updateSnapshot(updated));
        this.writer.stashUnsettled();
        this.writer.stashUnsettled();
        assertTrue(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        assertFalse(request.updateSnapshot(updated));
        assertEquals("raw", raw.data(RETAINED).getAsString());
        assertEquals(1, this.bodies().size());
        Snapshot stashed = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(Files.readAllBytes(this.bodies().getFirst()))).snapshot();
        assertEquals(prepared ? updated : raw, stashed);
        assertEquals(1, this.stashReports.get());
    }

    @Test
    void unencodedRequestFailsWithTimeoutAndCannotResume() {
        SaveRequest request = this.accept(false);
        this.writer.stashUnsettled();
        ExecutionException failure = assertThrows(ExecutionException.class, () -> request.completion().get(1, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertTrue(this.failures.contains(failure.getCause()));
        assertFalse(request.updateSnapshot(new Snapshot(request.meta(), Map.of())));
        this.writer.write(request);
        assertTrue(this.submissions.isEmpty());
    }

    @Test
    void ordinaryWriteFailureDoesNotAutomaticallyStashTheEncodedBody() throws Exception {
        SaveRequest request = this.accept(true);
        this.writer.write(request);
        IllegalStateException failure = new IllegalStateException("database execution failed");
        this.nextSubmission().outcome().completeExceptionally(failure);
        assertSame(failure, assertThrows(ExecutionException.class, () -> request.completion().get(1, TimeUnit.SECONDS)).getCause());
        this.writer.stashUnsettled();
        assertTrue(this.bodies().isEmpty());
        assertEquals(List.of(failure), this.failures);
    }

    @ParameterizedTest
    @EnumSource(value = SaveResult.class, names = {"SAVED", "RETRY_LATER", "REJECTED_MALFORMED"})
    void finalFutureWaitsForStashAndLateDatabaseResultsCannotFinishAgain(SaveResult lateResult) throws Exception {
        SaveRequest request = this.accept(true);
        this.writer.write(request);
        Submission submission = this.nextSubmission();
        CountDownLatch stashing = new CountDownLatch(1);
        CountDownLatch finishStash = new CountDownLatch(1);
        this.onWarning = () -> {
            stashing.countDown();
            await(finishStash);
        };
        CompletableFuture<Void> sweep = CompletableFuture.runAsync(this.writer::stashUnsettled);
        try {
            assertTrue(stashing.await(2, TimeUnit.SECONDS));
            assertTrue(request.finishing());
            assertFalse(request.completion().isDone());
            assertFalse(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
            submission.outcome().complete(new SaveOutcome(lateResult, null));
            this.writer.stashUnsettled();
            assertFalse(request.completion().isDone());
        } finally {
            finishStash.countDown();
        }
        sweep.get(2, TimeUnit.SECONDS);
        assertEquals(SaveResult.RETRY_LATER, assertInstanceOf(SnapshotSaveResult.Settled.class, request.completion().join()).result());
        assertEquals(1, this.stashReports.get());
        assertEquals(1, this.bodies().size());
        assertTrue(this.submissions.isEmpty());
        assertEquals(0, this.rotations.get());
    }

    @Test
    void queuedRetryDoesNotRestartASettledRequest() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        this.executor.submit(PLAYER, () -> {
            blocked.countDown();
            await(release);
        });
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        SaveRequest request = this.accept(true);
        this.writer.write(request);
        this.nextSubmission().outcome().complete(new SaveOutcome(SaveResult.RETRY_LATER, null));
        this.writer.stashUnsettled();
        release.countDown();
        CountDownLatch drained = new CountDownLatch(1);
        this.executor.submit(PLAYER, drained::countDown);
        assertTrue(drained.await(2, TimeUnit.SECONDS));
        assertTrue(this.submissions.isEmpty());
        assertEquals(1, this.bodies().size());
    }

    @Test
    void failedStashIsReportedBeforeFinalCompletion() throws Exception {
        Files.createDirectories(this.directory.resolve("snapshot"));
        Files.writeString(this.directory.resolve("snapshot/pending"), "not a directory");
        SaveRequest request = this.accept(true);
        this.writer.stashUnsettled();
        assertEquals(SaveResult.RETRY_LATER, assertInstanceOf(SnapshotSaveResult.Settled.class, request.completion().join()).result());
        assertEquals(1, this.failures.size());
        assertTrue(this.bodies().isEmpty());
    }

    private SaveRequest accept(boolean encoded) {
        SnapshotMeta meta = new SnapshotMeta(UUID.randomUUID(), PLAYER, 1, SaveCause.COMMAND, false, "test", 0);
        SaveRequest request = new SaveRequest(meta, "TestPlayer", EagerSnapshotData.EMPTY, null);
        this.writer.register(request);
        if (encoded) request.updateSnapshot(new Snapshot(meta, Map.of(RETAINED, NBT.createString("raw"))));
        return request;
    }

    @ParameterizedTest
    @EnumSource(value = SaveCause.class, names = {"DISCONNECT", "SHUTDOWN"})
    void disabledCacheInvalidatesBeforeFinalSaveCompletion(SaveCause cause) {
        NmsPlayerFixture.set(PluginConfig.SnapshotCacheOptions.class, PluginConfig.synchronization$snapshotCache(), "enabled", false);
        SnapshotMeta meta = new SnapshotMeta(UUID.randomUUID(), PLAYER, 2, cause, false, "test", 0);
        Snapshot snapshot = new Snapshot(meta, Map.of(RETAINED, NBT.createString("latest")));
        Snapshot old = new Snapshot(new SnapshotMeta(UUID.randomUUID(), PLAYER, 1, cause, false, "previous", 0), Map.of());
        this.cache.publish(old, 15).join();
        this.cache.invalidation = new CompletableFuture<>();
        SaveRequest request = new SaveRequest(meta, "TestPlayer", EagerSnapshotData.EMPTY, null);
        request.updateSnapshot(snapshot);
        this.writer.register(request);
        this.writer.write(request);
        CompletableFuture<Void> completed = request.completion().thenAccept(result -> assertEquals(List.of(PLAYER), this.cache.invalidations));
        assertTrue(this.cache.invalidations.isEmpty());
        this.nextSubmission().outcome().complete(new SaveOutcome(SaveResult.SAVED, null));
        assertTrue(completed.isDone());
        completed.join();
        assertFalse(this.cache.invalidation.isDone());
        this.cache.invalidation.complete(null);
        Snapshot loaded = this.cache.consume(PLAYER).join().orElse(snapshot);
        assertSame(snapshot, loaded);
    }

    @ParameterizedTest
    @EnumSource(value = SaveResult.class, names = {"SAVED", "DUPLICATE"})
    void enabledCachePublishesBeforeFinalSaveCompletion(SaveResult result) {
        SnapshotMeta meta = new SnapshotMeta(UUID.randomUUID(), PLAYER, 2, SaveCause.DISCONNECT, false, "test", 0);
        Snapshot snapshot = new Snapshot(meta, Map.of(RETAINED, NBT.createString("latest")));
        SaveRequest request = new SaveRequest(meta, "TestPlayer", EagerSnapshotData.EMPTY, null);
        request.updateSnapshot(snapshot);
        this.writer.register(request);
        this.writer.write(request);
        CompletableFuture<Void> completed = request.completion().thenAccept(ignored -> assertSame(snapshot, this.cache.consume(PLAYER).join().orElseThrow()));
        this.nextSubmission().outcome().complete(new SaveOutcome(result, null));
        assertTrue(completed.isDone());
        completed.join();
        assertTrue(this.cache.invalidations.isEmpty());
    }

    private Submission nextSubmission() {
        try {
            Submission submission = this.submissions.poll(2, TimeUnit.SECONDS);
            assertNotNull(submission, "expected a storage submission");
            return submission;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private List<Path> bodies() throws IOException {
        try (var paths = Files.walk(this.directory)) {
            return paths.filter(path -> path.toString().endsWith(".snapshot")).toList();
        }
    }

    private void maxRetries(int retries) throws Exception {
        Field optionsField = PluginConfig.ConfigDefinition.class.getDeclaredField("synchronization");
        optionsField.setAccessible(true);
        Object options = optionsField.get(this.config);
        Field retriesField = options.getClass().getDeclaredField("maxSaveRetries");
        retriesField.setAccessible(true);
        retriesField.setInt(options, retries);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "test stage was not released");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record Submission(Snapshot snapshot, CompletableFuture<SaveOutcome> outcome) {
    }
}
