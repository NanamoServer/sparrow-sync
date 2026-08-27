package net.momirealms.sparrow.sync.executor;

import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSerialExecutorTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final QuietLogger logger = new QuietLogger();
    private PlayerSerialExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdown(2, TimeUnit.SECONDS);
    }

    @Test
    void tasksOfSamePlayerRunInSubmissionOrder() throws InterruptedException {
        executor = new PlayerSerialExecutor(logger, 4);
        int taskCount = 1000;
        List<Integer> aliceOrder = new ArrayList<>(taskCount);
        List<Integer> bobOrder = new ArrayList<>(taskCount);
        CountDownLatch done = new CountDownLatch(taskCount * 2);

        // 交叉提交两位玩家各 1000 个任务
        for (int i = 0; i < taskCount; i++) {
            int sequence = i;
            executor.submit(ALICE, () -> {
                aliceOrder.add(sequence);
                done.countDown();
            });
            executor.submit(BOB, () -> {
                bobOrder.add(sequence);
                done.countDown();
            });
        }

        assertTrue(done.await(10, TimeUnit.SECONDS));
        // 各自严格按提交序执行且一个不少 (同桶单线程写入, list 无需同步)
        for (int i = 0; i < taskCount; i++) {
            assertEquals(i, aliceOrder.get(i));
            assertEquals(i, bobOrder.get(i));
        }
    }

    @Test
    void samePlayerAlwaysRunsOnSameWorkerThread() throws InterruptedException {
        executor = new PlayerSerialExecutor(logger, 4);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(100);

        for (int i = 0; i < 100; i++) {
            executor.submit(ALICE, () -> {
                threads.add(Thread.currentThread().getName());
                done.countDown();
            });
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, threads.size());
    }

    @Test
    void submitFirstRunsBeforeQueuedTasks() throws InterruptedException {
        executor = new PlayerSerialExecutor(logger, 1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<String> order = new CopyOnWriteArrayList<>();

        // 占住 worker, 让后续两个任务都停在队列里
        executor.submit(ALICE, () -> {
            blockerStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
        executor.submit(ALICE, () -> {
            order.add("tail");
            done.countDown();
        });
        executor.submitFirst(ALICE, () -> {
            order.add("head");
            done.countDown();
        });
        release.countDown();

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("head", "tail"), order);
    }

    @Test
    void failingTaskDoesNotKillWorker() throws InterruptedException {
        executor = new PlayerSerialExecutor(logger, 1);
        CountDownLatch done = new CountDownLatch(1);

        executor.submit(ALICE, () -> {
            throw new IllegalStateException("boom");
        });
        executor.submit(ALICE, done::countDown);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, executor.failureCount());
        assertEquals(1, logger.warnings.get());
    }

    @Test
    void shutdownDrainsRemainingTasks() {
        executor = new PlayerSerialExecutor(logger, 2);
        AtomicInteger executed = new AtomicInteger();

        for (int i = 0; i < 50; i++) {
            executor.submit(ALICE, executed::incrementAndGet);
            executor.submit(BOB, executed::incrementAndGet);
        }
        int remaining = executor.shutdown(5, TimeUnit.SECONDS);

        assertEquals(0, remaining);
        assertEquals(100, executed.get());
    }

    @Test
    void shutdownTimeoutReportsRemainingTasks() throws InterruptedException {
        executor = new PlayerSerialExecutor(logger, 1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);

        // 永久阻塞任务卡住 worker, 身后积压 10 个任务
        executor.submit(ALICE, () -> {
            blockerStarted.countDown();
            try {
                never.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 10; i++) {
            executor.submit(ALICE, () -> {
            });
        }
        int remaining = executor.shutdown(300, TimeUnit.MILLISECONDS);

        assertEquals(10, remaining);
    }

    @Test
    void idleShutdownReturnsPromptly() {
        // 空闲 worker 靠退出哨兵立刻收工, 不用等满一个轮询周期
        executor = new PlayerSerialExecutor(logger, 16);
        long start = System.nanoTime();

        int remaining = executor.shutdown(5, TimeUnit.SECONDS);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(0, remaining);
        assertTrue(elapsedMillis < 500, "idle shutdown took " + elapsedMillis + "ms");
    }

    @Test
    void submissionAfterShutdownIsRejected() {
        executor = new PlayerSerialExecutor(logger, 1);
        executor.shutdown(1, TimeUnit.SECONDS);

        assertThrows(RejectedExecutionException.class, () -> executor.submit(ALICE, () -> {
        }));
        assertThrows(RejectedExecutionException.class, () -> executor.submitFirst(ALICE, () -> {
        }));
    }

    @Test
    void workerCountNormalizedToPowerOfTwo() {
        executor = new PlayerSerialExecutor(logger, 3);
        assertEquals(4, executor.workerCount());
        executor.shutdown(1, TimeUnit.SECONDS);

        executor = new PlayerSerialExecutor(logger, 4);
        assertEquals(4, executor.workerCount());
        executor.shutdown(1, TimeUnit.SECONDS);

        executor = new PlayerSerialExecutor(logger, 0);
        assertEquals(1, executor.workerCount());
    }

    private static final class QuietLogger implements PluginLogger {
        final AtomicInteger warnings = new AtomicInteger();

        @Override
        public void info(String s) {
        }

        @Override
        public void warn(String s) {
            this.warnings.incrementAndGet();
        }

        @Override
        public void warn(String s, Throwable t) {
            this.warnings.incrementAndGet();
        }

        @Override
        public void error(String s) {
        }

        @Override
        public void error(String s, Throwable t) {
        }
    }
}
