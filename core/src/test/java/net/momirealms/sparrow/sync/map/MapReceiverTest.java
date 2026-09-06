package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static net.momirealms.sparrow.sync.map.MapFlowTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class MapReceiverTest {
    @Test
    void mergesConcurrentReadsAndWaitsForNativeInstallation() {
        Storage storage = new Storage();
        storage.current = map(8);
        Shared shared = new Shared();
        Tasks worker = new Tasks();
        Tasks nativeThread = new Tasks();
        List<StoredMap> installed = new ArrayList<>();
        MapReceiver receiver = new MapReceiver(() -> CompletableFuture.completedFuture(storage), shared, map -> () -> {
            installed.add(map);
            return -1;
        }, worker, nativeThread, logger(new ArrayList<>()));
        CompletableFuture<Integer> first = receiver.receive(IDENTITY);
        assertSame(first, receiver.receive(IDENTITY));
        worker.runAll();
        assertFalse(first.isDone());
        assertTrue(installed.isEmpty());
        nativeThread.runAll();
        assertEquals(-1, first.join());
        assertEquals(List.of(map(8)), installed);
        receiver.receive(IDENTITY);
        worker.runAll();
        nativeThread.runAll();
        assertEquals(1, storage.reads);
        assertEquals(1, shared.reads);
        assertTrue(shared.writes.isEmpty());
    }

    @Test
    void invalidationBeforeInstallationDiscardsOldPixelsAndRefetchesOnce() {
        Storage storage = new Storage();
        Shared shared = new Shared();
        shared.contents.put(-1, map(1));
        Tasks worker = new Tasks();
        Tasks nativeThread = new Tasks();
        List<StoredMap> installed = new ArrayList<>();
        MapReceiver receiver = new MapReceiver(() -> CompletableFuture.completedFuture(storage), shared, map -> () -> {
            installed.add(map);
            return -1;
        }, worker, nativeThread, logger(new ArrayList<>()));
        CompletableFuture<Integer> result = receiver.receive(IDENTITY);
        worker.runAll();
        shared.contents.put(-1, map(2));
        receiver.invalidate(-1);
        receiver.invalidate(-1);
        nativeThread.runAll();
        assertTrue(installed.isEmpty());
        assertFalse(result.isDone());
        worker.runAll();
        nativeThread.runAll();
        assertEquals(-1, result.join());
        assertEquals(List.of(map(2)), installed);
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
        MapReceiver receiver = new MapReceiver(() -> CompletableFuture.completedFuture(storage), shared, map -> () -> -1, Runnable::run, Runnable::run, logger(warnings));
        assertEquals(-1, receiver.receive(IDENTITY).join());
        assertEquals(1, storage.reads);
        assertTrue(shared.writes.isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void failedReadAndWrongOriginDoNotPoisonSubsequentRequest() {
        Storage storage = new Storage();
        Shared shared = new Shared();
        List<StoredMap> installed = new ArrayList<>();
        MapReceiver receiver = new MapReceiver(() -> CompletableFuture.completedFuture(storage), shared, map -> () -> {
            installed.add(map);
            return -1;
        }, Runnable::run, Runnable::run, logger(new ArrayList<>()));
        assertTrue(receiver.receive(IDENTITY).isCompletedExceptionally());
        storage.current = new StoredMap(new MapIdentity("cluster", new MapSource("other", 7), -1), map(2).data());
        assertTrue(receiver.receive(IDENTITY).isCompletedExceptionally());
        assertTrue(installed.isEmpty());
        storage.current = map(3);
        assertEquals(-1, receiver.receive(IDENTITY).join());
        assertEquals(List.of(map(3)), installed);
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
        MapReceiver receiver = new MapReceiver(() -> CompletableFuture.completedFuture(storage), shared, map -> () -> -1, Runnable::run, Runnable::run, logger(new ArrayList<>()));
        CompletableFuture<Integer> result = receiver.receive(IDENTITY);
        assertTrue(receiver.receive(new MapIdentity("cluster", new MapSource("wrong", 1), -1)).isCompletedExceptionally());
        receiver.invalidate(-1);
        firstRead.complete(Optional.empty());
        assertEquals(-1, result.join());
        assertEquals(2, shared.reads);
    }
}
