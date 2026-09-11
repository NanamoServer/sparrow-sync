package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.MapPublisher;
import net.momirealms.sparrow.sync.map.MapStorage;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.map.cache.MapCache;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.NoopSnapshotCache;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.locale.TranslationManagerImpl;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotStash;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveOutcome;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
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

/**
 * 验证接收、最终等待与停服暂存跨越 Saver 和 Writer 的生命周期契约.
 * 数据库结果和任务先后通过 Future 与信号控制, 原交接测试的有效覆盖迁移到最终保存结果上.
 */
class SnapshotSaveLifecycleTest {
    private static final UUID PLAYER = new UUID(0, 23); // 各测试共用的玩家队列键
    private static final DataKey RETAINED = DataKey.of("external", "retained"); // 验证原正文和准备后正文的未知数据
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE); // 读取实际暂存产物
    private final LinkedBlockingQueue<Submission> submissions = new LinkedBlockingQueue<>(); // 测试逐次接管数据库结果
    private final AtomicInteger rotations = new AtomicInteger(); // 只计发起轮转, 轮转 Future 故意保持未完成
    private final AtomicInteger stashReports = new AtomicInteger(); // 每次真实暂存尝试的报告次数
    private final List<Throwable> failures = new CopyOnWriteArrayList<>(); // 控制台收到的异常原因
    private final LinkedBlockingQueue<String> shutdownLogs = new LinkedBlockingQueue<>();
    private Object previousTranslations;
    private volatile Runnable onWarning = () -> {}; // 在暂存报告时暂停收尾, 检查结果完成的边界
    private Function<Snapshot, CompletableFuture<SaveOutcome>> onSave; // 默认返回可控写入, 单个测试可换成真实队列投递
    private PluginConfig.ConfigDefinition config; // 当前测试固定的配置
    private Object previousConfig; // 测试结束时恢复静态配置
    private SnapshotWriter writer; // 被测在途集合和写入管理器
    private PlayerSerialExecutor executor; // 复用真实串行队列验证提交与重试

    @TempDir
    Path directory; // 仅属于当前用例的暂存目录

    /**
     * 装配真实 Writer、串行执行器和文件暂存, 存储边界由测试控制.
     *
     * @throws Exception 静态测试配置无法替换时
     */
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
        this.writer = new SnapshotWriter(logger, storage, new SnapshotStash(this.directory, this.codec, logger), this.executor, new NoopSnapshotCache());
    }

    /**
     * 排空测试任务并恢复共享配置, 未完成的伪数据库 Future 不持有外部连接.
     *
     * @throws Exception 静态配置无法恢复时
     */
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
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previousServer = serverField.get(null);
        serverField.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isStopping")) return true;
                    throw new AssertionError(method.getName());
                }));
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

    /** 首次提交以后仍等待最终回执, 汇总超时不会修改任何原始 Future. */
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

    /** 单份异常结束也必须继续等待整批中的其他请求. */
    @Test
    void failureDoesNotEndTheBatchWhileAnotherSaveIsPending() {
        SaveRequest failed = this.accept(false);
        SaveRequest pending = this.accept(false);
        failed.fail(new IllegalStateException("capture failed"));
        assertFalse(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        pending.fail(new IllegalStateException("encode failed"));
        assertTrue(this.writer.sealAndAwaitSaves(1, TimeUnit.SECONDS));
    }

    /** 封口永久拒绝后续登记, 即使第一轮等待因预算耗尽退出. */
    @Test
    void shutdownSealRejectsLaterSaveRequests() {
        this.accept(false);
        assertFalse(this.writer.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        assertThrows(RejectedExecutionException.class, () -> this.accept(false));
    }

    /**
     * 采集任务追加的存储子任务在停服哨兵之前运行, worker 不等待自己的队尾任务.
     *
     * @throws Exception 同步信号未按期限到达时
     */
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

    /** 已接收的千份保存从多个完成线程返回时, 等待仍能覆盖整个批次. */
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

    /**
     * 接收和封口同时发生时, 每份请求要么拒绝, 要么进入固定等待批次.
     *
     * @throws Exception 并发任务未按期限结束时
     */
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

    /**
     * 封口后的等待允许重试, 配置在首次写入固定, 请求排队时的旧配置不影响该时刻.
     *
     * @throws Exception 测试配置字段无法写入时
     */
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

    /**
     * 存储结果保持现有轮转和归档分类, 文件暂存结束后才完成回执.
     *
     * @param result 覆盖所有存储分类
     * @throws Exception 测试配置或暂存文件读取失败时
     */
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

    /**
     * 原正文与准备后正文在请求中交替发布时, 停服只选取收尾时已发布的完整内容.
     *
     * @param prepared 是否已经发布地图准备后的正文
     * @throws Exception 暂存结果无法读取时
     */
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

    /** 未编码请求在预算耗尽后明确失败, 不能补发正文或伪装成已暂存. */
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

    /**
     * 普通异步异常保留原失败并退出在途集合, 即使已经编码也不会在停服时补写.
     *
     * @throws Exception 暂存目录无法读取时
     */
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

    /**
     * 暂存已取得收尾权时, 迟到数据库结果不得反转结果、再重试或重复归档.
     *
     * @param lateResult 数据库在暂存期间返回的分类
     * @throws Exception 同步信号或文件读取失败时
     */
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

    /**
     * 重试已入队但尚未执行时, 停服收尾让该任务结束而不再访问存储.
     *
     * @throws Exception 测试队列标记未按期限完成时
     */
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

    /**
     * 文件系统拒绝暂存时仍如实报告失败, 结果分类保留 RETRY_LATER 的原契约.
     *
     * @throws Exception 测试目录无法准备时
     */
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

    /**
     * 建立并登记测试请求, 可选择从尚未编码或已发布原正文的阶段开始.
     *
     * @param encoded 是否立即发布完整原正文
     * @return Writer 已接收的请求
     */
    private SaveRequest accept(boolean encoded) {
        SnapshotMeta meta = new SnapshotMeta(UUID.randomUUID(), PLAYER, 1, SaveCause.COMMAND, false, "test", 0);
        SaveRequest request = new SaveRequest(meta, "TestPlayer", Map.of(), null);
        this.writer.register(request);
        if (encoded) request.updateSnapshot(new Snapshot(meta, Map.of(RETAINED, NBT.createString("raw"))));
        return request;
    }

    /**
     * 等待下一次实际存储调用, 信号超时直接让用例失败.
     *
     * @return 本次写入的正文和结果控制器
     */
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

    /**
     * 列举当前用例实际发布的正文文件.
     *
     * @return 正文路径, 不包含伴随头
     * @throws IOException 测试目录无法遍历时
     */
    private List<Path> bodies() throws IOException {
        try (var paths = Files.walk(this.directory)) {
            return paths.filter(path -> path.toString().endsWith(".snapshot")).toList();
        }
    }

    /**
     * 设置测试使用的最大重试次数, 可在登记和首次写入之间改变配置.
     *
     * @param retries 首发之后允许的重试次数
     * @throws Exception 配置字段无法访问时
     */
    private void maxRetries(int retries) throws Exception {
        Field optionsField = PluginConfig.ConfigDefinition.class.getDeclaredField("synchronization");
        optionsField.setAccessible(true);
        Object options = optionsField.get(this.config);
        Field retriesField = options.getClass().getDeclaredField("maxSaveRetries");
        retriesField.setAccessible(true);
        retriesField.setInt(options, retries);
    }

    /**
     * 由测试线程显式释放队列阶段, 中断转换为可定位的用例失败.
     *
     * @param latch 当前阶段的继续信号
     */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "test stage was not released");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    /**
     * 一次受控数据库写入, 对应真实 Provider 的异步结果边界.
     *
     * @param snapshot 本次收到的完整正文
     * @param outcome 由测试确认的数据库结果
     */
    private record Submission(Snapshot snapshot, CompletableFuture<SaveOutcome> outcome) {
    }
}
