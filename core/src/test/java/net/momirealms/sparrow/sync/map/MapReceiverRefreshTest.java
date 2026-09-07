package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.sync.map.data.StoredMap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static net.momirealms.sparrow.sync.map.MapFlowTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class MapReceiverRefreshTest {
    private final Storage storage = new Storage();
    private final Shared shared = new Shared();
    private final Tasks nativeThread = new Tasks();
    private final NativeMaps nativeState = new NativeMaps();
    private final List<StoredMap> updated = this.nativeState.updates;
    private final List<String> warnings = new ArrayList<>();
    private final MapReceiver receiver = this.nativeState.receiver(this.storage, this.shared, "B-world", Runnable::run, Runnable::run, logger(this.warnings));

    @Test
    void firstObservationReturnsWhileAnotherThreadHoldsTheReceiveLock() throws Exception {
        Tasks worker = new Tasks();
        AtomicInteger scheduled = new AtomicInteger();
        Executor dispatch = task -> {
            scheduled.incrementAndGet();
            worker.execute(task);
        };
        MapReceiver receiver = this.nativeState.receiver(this.storage, this.shared, "B-world", dispatch, this.nativeThread, logger(this.warnings));
        Field field = MapReceiver.class.getDeclaredField("flights");
        field.setAccessible(true);
        this.storage.current = map(2);
        synchronized (field.get(receiver)) {
            CompletableFuture.runAsync(() -> {
                receiver.observe(1);
                receiver.observe(-1);
                receiver.observe(-1);
            }).get(2, TimeUnit.SECONDS);
            assertEquals(1, scheduled.get());
            assertEquals(0, this.storage.reads);
            assertTrue(this.updated.isEmpty());
        }
        worker.runAll();
        this.nativeThread.runAll();
        worker.runAll();
        assertEquals(1, this.storage.reads);
        assertEquals(List.of(map(2)), this.updated);
        int before = scheduled.get();
        receiver.observe(-1);
        assertEquals(before, scheduled.get());
        receiver.close();
    }

    @Test
    void closeDiscardsObservationThatHasNotReachedTheWorker() {
        Tasks worker = new Tasks();
        MapReceiver receiver = this.nativeState.receiver(this.storage, this.shared, "B-world", worker, this.nativeThread, logger(this.warnings));
        this.storage.current = map(2);
        receiver.observe(-1);
        receiver.close();
        worker.runAll();
        this.nativeThread.runAll();
        assertEquals(0, this.storage.reads);
        assertEquals(0, this.shared.reads);
        assertTrue(this.updated.isEmpty());
        assertTrue(this.warnings.isEmpty());
    }

    @Test
    void observesPersistedReplicaOnceAndWaitsForAnUpdateEvent() {
        this.storage.current = map(2);
        this.shared.contents.put(-1, map(1));
        MapReceiver receiver = this.receiver;
        receiver.observe(1);
        receiver.observe(-1);
        receiver.observe(-1);
        assertEquals(1, this.storage.reads);
        assertEquals(List.of(map(2)), this.updated);
        assertEquals(0, this.shared.reads);
        this.storage.current = map(3);
        receiver.observe(-1);
        assertEquals(1, this.storage.reads);
        assertEquals(List.of(map(2)), this.updated);
        this.shared.contents.put(-1, map(3));
        receiver.refresh(-1);
        assertEquals(List.of(map(2), map(3)), this.updated);
        assertTrue(this.shared.writes.isEmpty());
        assertEquals(0, this.storage.registrations);
        assertTrue(this.storage.writes.isEmpty());
    }

    @Test
    void notificationRefreshesKnownReplicaFromSharedCache() {
        this.shared.contents.put(-1, map(1));
        MapReceiver receiver = this.receiver;
        assertEquals(-1, receiver.receive(IDENTITY).join());
        assertEquals(List.of(map(1)), this.updated);
        this.shared.contents.put(-1, map(2));
        receiver.refresh(-1);
        assertEquals(List.of(map(1), map(2)), this.updated);
        assertEquals(map(2), this.shared.contents.get(-1));
        assertEquals(0, this.storage.reads);
    }

    @Test
    void notificationsDuringUpdateShareOneRefetch() {
        Tasks worker = new Tasks();
        MapReceiver receiver = this.nativeState.receiver(this.storage, this.shared, "B-world", worker, this.nativeThread, logger(this.warnings));
        this.storage.current = map(9);
        this.shared.contents.put(-1, map(1));
        CompletableFuture<Integer> waiting = receiver.receive(IDENTITY);
        worker.runAll();
        // 旧画面已准备, 连续通知先合并到同一更新任务.
        this.shared.contents.put(-1, map(2));
        receiver.refresh(-1);
        receiver.refresh(-1);
        this.nativeThread.runAll();
        assertTrue(this.updated.isEmpty());
        worker.runAll();
        this.nativeThread.runAll();
        worker.runAll();
        this.nativeThread.runAll();
        assertEquals(List.of(map(2)), this.updated);
        assertEquals(2, this.shared.reads);
        assertEquals(0, this.storage.reads);
        assertEquals(-1, waiting.join());
        assertTrue(this.warnings.isEmpty());
    }

    @Test
    void firstObservationForcesDatabaseReadInsideAnExistingReceive() {
        Tasks worker = new Tasks();
        MapReceiver receiver = this.nativeState.receiver(this.storage, this.shared, "B-world", worker, this.nativeThread, logger(this.warnings));
        this.storage.current = map(2);
        this.shared.contents.put(-1, map(1));
        CompletableFuture<Integer> waiting = receiver.receive(IDENTITY);
        worker.runAll();
        // 首次观察在旧缓存更新前完成, 已有等待者随后取得数据库中的画面.
        receiver.observe(-1);
        worker.runAll();
        assertSame(waiting, receiver.receive(IDENTITY));
        this.nativeThread.runAll();
        assertTrue(this.updated.isEmpty());
        worker.runAll();
        this.nativeThread.runAll();
        worker.runAll();
        assertEquals(-1, waiting.join());
        assertEquals(List.of(map(2)), this.updated);
        assertEquals(1, this.shared.reads);
        assertEquals(1, this.storage.reads);
        assertTrue(this.warnings.isEmpty());
    }

    @Test
    void failedRefreshRetainsPixelsUntilAnotherUpdateEvent() {
        this.storage.current = map(1);
        MapReceiver receiver = this.receiver;
        receiver.observe(-1);
        this.nativeThread.runAll();
        this.storage.current = null;
        receiver.refresh(-1);
        receiver.refresh(-1);
        assertEquals(List.of(map(1)), this.updated);
        assertEquals(1, this.warnings.size());
        this.storage.current = map(4);
        receiver.observe(-1);
        assertEquals(List.of(map(1)), this.updated);
        this.shared.contents.put(-1, map(4));
        receiver.refresh(-1);
        assertEquals(List.of(map(1), map(4)), this.updated);
        assertEquals(1, this.warnings.size());
    }

    @Test
    void closingStopsCallbacks() {
        this.storage.current = map(1);
        MapReceiver receiver = this.receiver;
        receiver.observe(-1);
        this.nativeThread.runAll();
        receiver.observe(-2);
        receiver.close();
        this.storage.current = map(2);
        receiver.refresh(-1);
        receiver.observe(-2);
        this.nativeThread.runAll();
        assertEquals(List.of(map(1)), this.updated);
    }

    @Test
    void missingGlobalMapIsQueriedOnceAndWaitsForAnotherEvent() {
        MapReceiver receiver = this.receiver;
        receiver.observe(-8);
        receiver.observe(-8);
        this.nativeThread.runAll();
        assertEquals(1, this.storage.reads);
        assertEquals(0, this.shared.reads);
        assertEquals(1, this.warnings.size());
    }

    @Test
    void rejectedReadTaskCanBeTriggeredByTheNextNotification() {
        this.storage.current = map(1);
        AtomicInteger submissions = new AtomicInteger();
        Executor worker = task -> {
            if (submissions.incrementAndGet() == 1) {
                throw new RejectedExecutionException("worker rejected task");
            }
            this.nativeThread.execute(task);
        };
        MapReceiver receiver = this.nativeState.receiver(this.storage, this.shared, "B-world", worker, Runnable::run, logger(this.warnings));

        receiver.observe(-1);
        assertEquals(1, submissions.get());
        assertEquals(0, this.storage.reads);
        assertTrue(this.updated.isEmpty());
        assertEquals(1, this.warnings.size());
        receiver.observe(-1);
        assertEquals(1, submissions.get());
        receiver.refresh(-1);
        assertEquals(2, submissions.get());
        this.nativeThread.runAll();
        assertEquals(List.of(map(1)), this.updated);
    }
}
