package net.momirealms.sparrow.sync.player;

import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void delayedTaskDoesNotHoldUpOtherPlayers() throws InterruptedException {
        executor = new PlayerSerialExecutor(logger, 1);
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);

        executor.submitDelayed(ALICE, () -> {
            order.add("delayed");
            done.countDown();
        }, 300, TimeUnit.MILLISECONDS);
        executor.submit(BOB, () -> {
            order.add("immediate");
            done.countDown();
        });

        assertTrue(done.await(3, TimeUnit.SECONDS), "both tasks should have run");
        assertEquals(List.of("immediate", "delayed"), order);
    }

    @Test
    void delayedTaskHoldsBackLaterTasksOfTheSamePlayer() throws InterruptedException {
        executor = new PlayerSerialExecutor(logger, 1);
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);

        executor.submitDelayed(ALICE, () -> {
            order.add("first");
            done.countDown();
        }, 200, TimeUnit.MILLISECONDS);
        executor.submit(ALICE, () -> {
            order.add("second");
            done.countDown();
        });

        assertTrue(done.await(3, TimeUnit.SECONDS), "both tasks should have run");
        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void delayedTaskWaitsWithoutBurningTheWorker() throws InterruptedException {
        executor = new PlayerSerialExecutor(logger, 1);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        executor.submitDelayed(ALICE, () -> {
            runs.incrementAndGet();
            done.countDown();
        }, 400, TimeUnit.MILLISECONDS);

        assertEquals(0, runs.get());
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(1, runs.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5})
    void tasksOfSamePlayerRunInSubmissionOrder(int workerCount) throws InterruptedException {
        this.executor = new PlayerSerialExecutor(this.logger, workerCount);
        int taskCount = 1000;
        List<Integer> aliceOrder = new ArrayList<>(taskCount);
        List<Integer> bobOrder = new ArrayList<>(taskCount);
        CountDownLatch done = new CountDownLatch(taskCount * 2);

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
        for (int i = 0; i < taskCount; i++) {
            assertEquals(i, aliceOrder.get(i));
            assertEquals(i, bobOrder.get(i));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5})
    void samePlayerAlwaysRunsOnSameWorkerThread(int workerCount) throws InterruptedException {
        this.executor = new PlayerSerialExecutor(this.logger, workerCount);
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
        CompletableFuture<Void> finishCurrentTask = new CompletableFuture<>();
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        AtomicInteger executed = new AtomicInteger();

        executor.submit(ALICE, () -> {
            workerThread.set(Thread.currentThread());
            blockerStarted.countDown();
            try {
                never.await();
            } catch (InterruptedException exception) {
                finishCurrentTask.join();
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 10; i++) {
            executor.submit(ALICE, executed::incrementAndGet);
        }
        try {
            int remaining = executor.shutdown(300, TimeUnit.MILLISECONDS);
            assertEquals(10, remaining);
            assertTrue(workerThread.get().isAlive());
        } finally {
            finishCurrentTask.complete(null);
        }
        workerThread.get().join(5000);
        assertFalse(workerThread.get().isAlive());
        assertEquals(0, executed.get());
        assertEquals(10, this.executor.pendingTasks());
    }

    @Test
    void idleShutdownReturnsPromptly() {
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
        assertThrows(RejectedExecutionException.class, () -> executor.submitDelayed(ALICE, () -> {
        }, 1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @CsvSource({"-1, 1", "0, 1", "1, 1", "3, 3", "4, 4", "5, 5", "63, 63", "64, 64", "65, 64"})
    void workerCountClampedToSupportedRange(int requested, int expected) {
        this.executor = new PlayerSerialExecutor(this.logger, requested);
        assertEquals(expected, this.executor.workerCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 6})
    void uuidHashesReachEveryWorkerIncludingNegativeHashes(int workerCount) throws InterruptedException {
        this.executor = new PlayerSerialExecutor(this.logger, workerCount);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        int[] hashes = {0, 1, 2, 3, 4, 5, -1, -2, Integer.MIN_VALUE, Integer.MAX_VALUE};
        CountDownLatch done = new CountDownLatch(hashes.length);
        for (int i = 0; i < hashes.length; i++) {
            UUID player = new UUID(0, Integer.toUnsignedLong(hashes[i]));
            this.executor.submit(player, () -> {
                threads.add(Thread.currentThread().getName());
                done.countDown();
            });
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(workerCount, threads.size());
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
