package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static net.momirealms.sparrow.sync.map.MapFlowTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class MapReceiverTest {
    private final NativeMaps nativeMaps = new NativeMaps();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void itemJoiningRuntimeReadIsCheckedBeforeNativeUpdate(boolean matches) {
        Storage storage = new Storage();
        storage.current = map(8);
        Tasks worker = new Tasks();
        Tasks nativeThread = new Tasks();
        MapReceiver receiver = this.nativeMaps.receiver(storage, new Shared(), "B-world", worker, nativeThread, logger(new ArrayList<>()));
        CompletableFuture<Integer> runtime = receiver.receive(-1);
        worker.runAll();
        // 物品在地图已准备、尚未写入世界时加入, 来源约束仍须生效.
        MapIdentity item = matches ? IDENTITY : new MapIdentity(new MapSource("wrong", 1), -1);
        assertSame(runtime, receiver.receive(item));
        nativeThread.runAll();
        assertEquals(1, storage.reads);
        if (matches) {
            assertEquals(-1, runtime.join());
            assertEquals(8, this.nativeMaps.replica.colors[0]);
        } else {
            assertTrue(runtime.isCompletedExceptionally());
            assertTrue(this.nativeMaps.updates.isEmpty());
        }
        receiver.close();
    }

    @Test
    void receivedReplicaTracksNotificationsUntilReceiverCloses() {
        Storage storage = new Storage();
        Shared shared = new Shared();
        shared.contents.put(-1, map(1));
        MapReceiver receiver = this.nativeMaps.receiver(storage, shared, "B-world", Runnable::run, Runnable::run, logger(new ArrayList<>()));
        assertEquals(-1, receiver.receive(IDENTITY).join());
        // 本地更新完成后即参与通知刷新, 展示观察尚未发生也能取得新画面.
        shared.contents.put(-1, map(2));
        receiver.refresh(-1);
        assertEquals(List.of(map(1), map(2)), this.nativeMaps.updates);
        receiver.close();
        shared.contents.put(-1, map(3));
        receiver.refresh(-1);
        assertEquals(2, this.nativeMaps.replica.colors[0]);
        assertEquals(0, storage.registrations);
        assertTrue(shared.writes.isEmpty());
    }

    @Test
    void closingCancelsPreparedUpdateAndRejectsNewReads() {
        Storage storage = new Storage();
        storage.current = map(1);
        Tasks nativeThread = new Tasks();
        List<StoredMap> updated = this.nativeMaps.updates;
        MapReceiver receiver = this.nativeMaps.receiver(storage, new Shared(), "B-world", Runnable::run, nativeThread, logger(new ArrayList<>()));
        CompletableFuture<Integer> waiting = receiver.receive(IDENTITY);
        receiver.close();
        nativeThread.runAll();
        assertTrue(waiting.isCompletedExceptionally());
        assertTrue(receiver.receive(IDENTITY).isCompletedExceptionally());
        assertTrue(updated.isEmpty());
        assertEquals(1, storage.reads);
    }

    @Test
    void timedOutReadCannotUpdateAfterAReplacementReadCompletes() {
        CompletableFuture<Optional<StoredMap>> old = new CompletableFuture<>();
        Shared shared = new Shared() {
            @Override
            @NotNull
            public CompletableFuture<Optional<StoredMap>> find(int id) {
                return ++this.reads == 1 ? old : CompletableFuture.completedFuture(Optional.of(map(2)));
            }
        };
        List<StoredMap> updated = this.nativeMaps.updates;
        MapReceiver receiver = this.nativeMaps.receiver(new Storage(), shared, "B-world", Runnable::run, Runnable::run, logger(new ArrayList<>()));
        CompletableFuture<Integer> timedOut = receiver.receive(IDENTITY);
        timedOut.completeExceptionally(new java.util.concurrent.TimeoutException());
        assertEquals(-1, receiver.receive(IDENTITY).join());
        old.complete(Optional.of(map(1)));
        assertEquals(List.of(map(2)), updated);
    }
    @Test
    void mergesConcurrentReadsAndWaitsForNativeUpdate() {
        Storage storage = new Storage();
        storage.current = map(8);
        Shared shared = new Shared();
        Tasks worker = new Tasks();
        Tasks nativeThread = new Tasks();
        List<StoredMap> updated = this.nativeMaps.updates;
        MapReceiver receiver = this.nativeMaps.receiver(storage, shared, "B-world", worker, nativeThread, logger(new ArrayList<>()));
        CompletableFuture<Integer> first = receiver.receive(IDENTITY);
        assertSame(first, receiver.receive(IDENTITY));
        worker.runAll();
        assertFalse(first.isDone());
        assertTrue(updated.isEmpty());
        nativeThread.runAll();
        assertEquals(-1, first.join());
        assertEquals(List.of(map(8)), updated);
        receiver.receive(IDENTITY);
        worker.runAll();
        nativeThread.runAll();
        assertEquals(1, storage.reads);
        assertEquals(1, shared.reads);
        assertTrue(shared.writes.isEmpty());
    }

    @Test
    void invalidationBeforeUpdateDiscardsOldPixelsAndRefetchesOnce() {
        Storage storage = new Storage();
        Shared shared = new Shared();
        shared.contents.put(-1, map(1));
        Tasks worker = new Tasks();
        Tasks nativeThread = new Tasks();
        List<StoredMap> updated = this.nativeMaps.updates;
        MapReceiver receiver = this.nativeMaps.receiver(storage, shared, "B-world", worker, nativeThread, logger(new ArrayList<>()));
        CompletableFuture<Integer> result = receiver.receive(IDENTITY);
        worker.runAll();
        shared.contents.put(-1, map(2));
        receiver.invalidate(-1);
        receiver.invalidate(-1);
        nativeThread.runAll();
        assertTrue(updated.isEmpty());
        assertFalse(result.isDone());
        worker.runAll();
        nativeThread.runAll();
        assertEquals(-1, result.join());
        assertEquals(List.of(map(2)), updated);
        assertEquals(2, shared.reads);
        assertEquals(0, storage.reads);
    }

    @Test
    void redisFailureFallsBackToDatabaseWithoutWritingSharedContent() {
        Storage storage = new Storage();
        storage.current = map(9);
        Shared shared = new Shared() {
            @Override
            @NotNull
            public CompletableFuture<Optional<StoredMap>> find(int id) {
                return CompletableFuture.failedFuture(new IllegalStateException("offline"));
            }
        };
        List<String> warnings = new ArrayList<>();
        MapReceiver receiver = this.nativeMaps.receiver(storage, shared, "B-world", Runnable::run, Runnable::run, logger(warnings));
        assertEquals(-1, receiver.receive(IDENTITY).join());
        assertEquals(1, storage.reads);
        assertTrue(shared.writes.isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void failedReadAndWrongOriginDoNotPoisonSubsequentRequest() {
        Storage storage = new Storage();
        Shared shared = new Shared();
        List<StoredMap> updated = this.nativeMaps.updates;
        MapReceiver receiver = this.nativeMaps.receiver(storage, shared, "B-world", Runnable::run, Runnable::run, logger(new ArrayList<>()));
        assertTrue(receiver.receive(IDENTITY).isCompletedExceptionally());
        storage.current = new StoredMap(new MapIdentity(new MapSource("other", 7), -1), map(2).data());
        assertTrue(receiver.receive(IDENTITY).isCompletedExceptionally());
        assertTrue(updated.isEmpty());
        storage.current = map(3);
        assertEquals(-1, receiver.receive(IDENTITY).join());
        assertEquals(List.of(map(3)), updated);
    }

    @Test
    void invalidatedFailureRetriesAndConflictingConcurrentOriginIsRejected() {
        CompletableFuture<Optional<StoredMap>> firstRead = new CompletableFuture<>();
        Shared shared = new Shared() {
            @Override
            @NotNull
            public CompletableFuture<Optional<StoredMap>> find(int id) {
                return ++this.reads == 1 ? firstRead : CompletableFuture.completedFuture(Optional.of(map(5)));
            }
        };
        Storage storage = new Storage();
        MapReceiver receiver = this.nativeMaps.receiver(storage, shared, "B-world", Runnable::run, Runnable::run, logger(new ArrayList<>()));
        CompletableFuture<Integer> result = receiver.receive(IDENTITY);
        assertTrue(receiver.receive(new MapIdentity(new MapSource("wrong", 1), -1)).isCompletedExceptionally());
        receiver.invalidate(-1);
        firstRead.complete(Optional.empty());
        assertEquals(-1, result.join());
        assertEquals(2, shared.reads);
    }
}
