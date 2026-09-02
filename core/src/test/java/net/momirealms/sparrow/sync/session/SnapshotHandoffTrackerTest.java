package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotHandoffTrackerTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000017");

    @Test
    void shutdownWaitsForEveryAcceptedSaveToReachItsFirstStorageSubmission() {
        SnapshotHandoffTracker tracker = new SnapshotHandoffTracker();
        tracker.accept();
        tracker.accept();

        tracker.handedOff();

        assertFalse(tracker.sealAndAwait(1, TimeUnit.MILLISECONDS));

        tracker.handedOff();

        assertTrue(tracker.sealAndAwait(1, TimeUnit.SECONDS));
    }

    @Test
    void shutdownSealRejectsLaterSaveRequests() {
        SnapshotHandoffTracker tracker = new SnapshotHandoffTracker();

        assertTrue(tracker.sealAndAwait(1, TimeUnit.SECONDS));

        assertThrows(RejectedExecutionException.class, tracker::accept);
    }

    @Test
    void storageTaskSubmittedByCaptureRunsBeforeExecutorShutdown() throws InterruptedException {
        SnapshotHandoffTracker tracker = new SnapshotHandoffTracker();
        PlayerSerialExecutor executor = new PlayerSerialExecutor(new QuietLogger(), 1);
        CountDownLatch captureStarted = new CountDownLatch(1);
        CountDownLatch continueCapture = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch storageRan = new CountDownLatch(1);
        tracker.accept();

        executor.submit(PLAYER, () -> {
            captureStarted.countDown();
            await(continueCapture);
            executor.submit(PLAYER, storageRan::countDown);
            tracker.handedOff();
        });

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> barrier = CompletableFuture.supplyAsync(() -> {
            waiterStarted.countDown();
            return tracker.sealAndAwait(5, TimeUnit.SECONDS);
        });
        assertTrue(waiterStarted.await(5, TimeUnit.SECONDS));
        assertFalse(barrier.isDone());

        continueCapture.countDown();

        assertTrue(barrier.join());
        assertEquals(0, executor.shutdown(5, TimeUnit.SECONDS));
        assertTrue(storageRan.await(1, TimeUnit.SECONDS));
    }

    @Test
    void concurrentHandoffsDrainTheAcceptedBatch() {
        SnapshotHandoffTracker tracker = new SnapshotHandoffTracker();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        int saves = 1_000;
        for (int i = 0; i < saves; i++) {
            tracker.accept();
        }
        try {
            for (int i = 0; i < saves; i++) {
                executor.execute(tracker::handedOff);
            }

            assertTrue(tracker.sealAndAwait(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static final class QuietLogger implements PluginLogger {

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void warn(String message, Throwable throwable) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }
    }
}
