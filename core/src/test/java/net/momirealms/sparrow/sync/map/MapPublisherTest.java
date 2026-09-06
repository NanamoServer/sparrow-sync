package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.sync.map.data.StoredMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static net.momirealms.sparrow.sync.map.MapFlowTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class MapPublisherTest {
    @Test
    void sealingWaitsForFullPublicationAndClosingStopsQueuedWrites() {
        Storage storage = new Storage();
        Tasks worker = new Tasks();
        CompletableFuture<Void> firstWrite = new CompletableFuture<>();
        Shared shared = new Shared() {
            @Override
            @NotNull
            public CompletableFuture<Void> publish(@NotNull StoredMap map) {
                super.publish(map);
                return firstWrite;
            }
        };
        MapPublisher publisher = new MapPublisher(storage, shared, worker);
        publisher.publish(SOURCE, map(1).data());
        worker.runAll();
        assertFalse(publisher.sealAndAwait(0, TimeUnit.NANOSECONDS));
        assertTrue(publisher.publish(SOURCE, map(1).data()).isCompletedExceptionally());
        firstWrite.complete(null);
        assertTrue(publisher.sealAndAwait(1, TimeUnit.SECONDS));
        publisher.close();

        MapPublisher closed = new MapPublisher(storage, shared, worker);
        CompletableFuture<StoredMap> queued = closed.publish(SOURCE, map(2).data());
        closed.close();
        worker.runAll();
        assertTrue(queued.isCompletedExceptionally());
        assertEquals(map(1), storage.current);
    }
    @Test
    void serializesCompletePublicationWithoutBlockingSubmissionOrAllocatingAgain() {
        Storage storage = new Storage();
        Tasks worker = new Tasks();
        CompletableFuture<Void> firstRedisWrite = new CompletableFuture<>();
        Shared shared = new Shared() {
            @Override
            @NotNull
            public CompletableFuture<Void> publish(@NotNull StoredMap map) {
                super.publish(map);
                assertEquals(map, storage.current);
                return this.writes.size() == 1 ? firstRedisWrite : CompletableFuture.completedFuture(null);
            }
        };
        MapPublisher publisher = new MapPublisher(storage, shared, worker);
        CompletableFuture<StoredMap> first = publisher.publish(SOURCE, map(1).data());
        worker.runAll();
        CompletableFuture<StoredMap> second = publisher.publish(SOURCE, map(2).data());
        worker.runAll();
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertEquals(map(1), storage.current);
        firstRedisWrite.complete(null);
        worker.runAll();
        assertEquals(IDENTITY, first.join().identity());
        assertEquals(map(2), second.join());
        assertEquals(1, storage.registrations);
        assertEquals(List.of(1, 2), shared.writes);
    }

    @Test
    void unchangedContentOnlyRenewsExistingCacheAndSourceRebuildsMissingCache() {
        Storage storage = new Storage();
        Shared shared = new Shared();
        Tasks worker = new Tasks();
        MapPublisher publisher = new MapPublisher(storage, shared, worker);
        publisher.publish(SOURCE, map(3).data());
        worker.runAll();
        publisher.publish(SOURCE, map(3).data());
        worker.runAll();
        assertEquals(1, shared.writes.size());
        assertEquals(1, shared.touches);
        assertTrue(storage.writes.isEmpty());
        shared.contents.clear();
        publisher.publish(SOURCE, map(3).data());
        worker.runAll();
        assertEquals(2, shared.writes.size());
        assertEquals(map(3), shared.contents.get(-1));
    }

    @Test
    void failedRedisCommitDoesNotMakeOldLocalContentLookLikeCurrentDatabaseContent() {
        Storage storage = new Storage();
        Tasks worker = new Tasks();
        CompletableFuture<Void> failedRedis = new CompletableFuture<>();
        Shared shared = new Shared() {
            @Override
            @NotNull
            public CompletableFuture<Void> publish(@NotNull StoredMap map) {
                return map.equals(map(2)) ? failedRedis : super.publish(map);
            }
        };
        MapPublisher publisher = new MapPublisher(storage, shared, worker);
        publisher.publish(SOURCE, map(1).data());
        worker.runAll();
        CompletableFuture<StoredMap> failed = publisher.publish(SOURCE, map(2).data());
        worker.runAll();
        CompletableFuture<StoredMap> newer = publisher.publish(SOURCE, map(1).data());
        failedRedis.completeExceptionally(new IllegalStateException("Redis offline"));
        worker.runAll();
        assertTrue(failed.isCompletedExceptionally());
        assertEquals(map(1), newer.join());
        assertEquals(List.of(2, 1), storage.writes);
        assertEquals(storage.current, shared.contents.get(-1));
    }

    @Test
    void failedPublicationStopsUntilAnotherSubmission() {
        Storage storage = new Storage();
        int[] attempts = {0};
        IllegalStateException failure = new IllegalStateException("Redis offline");
        Shared shared = new Shared() {
            @Override
            @NotNull
            public CompletableFuture<Void> publish(@NotNull StoredMap map) {
                return ++attempts[0] == 1 ? CompletableFuture.failedFuture(failure) : super.publish(map);
            }
        };
        Tasks worker = new Tasks();
        MapPublisher publisher = new MapPublisher(storage, shared, worker);
        CompletableFuture<StoredMap> result = publisher.publish(SOURCE, map(4).data());
        worker.runAll();
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(1, attempts[0]);
        assertEquals(map(4), storage.current);
        assertTrue(shared.contents.isEmpty());

        CompletableFuture<StoredMap> next = publisher.publish(SOURCE, map(5).data());
        worker.runAll();
        assertEquals(map(5), next.join());
        assertEquals(2, attempts[0]);
        assertEquals(storage.current, shared.contents.get(-1));
    }

    @Test
    void immediatelyCompletedPublicationsReleaseTheirPendingEntries() {
        Storage storage = new Storage();
        Shared shared = new Shared();
        MapPublisher publisher = new MapPublisher(storage, shared, Runnable::run);
        assertEquals(map(1), publisher.publish(SOURCE, map(1).data()).join());
        assertEquals(map(2), publisher.publish(SOURCE, map(2).data()).join());
        assertEquals(List.of(1, 2), shared.writes);
        assertTrue(publisher.sealAndAwait(0, TimeUnit.NANOSECONDS));
        publisher.close();
    }

    @Test
    void concurrentSamplesPublishInSubmissionOrder() throws Exception {
        Storage storage = new Storage();
        Tasks worker = new Tasks();
        MapPublisher publisher = new MapPublisher(storage, new Shared(), worker);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<CompletableFuture<StoredMap>> slow = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                throw new AssertionError(exception);
            }
            return publisher.publish(SOURCE, map(1).data());
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        CompletableFuture<StoredMap> quick = publisher.publish(SOURCE, map(2).data());
        release.countDown();
        CompletableFuture<StoredMap> late = slow.get(2, TimeUnit.SECONDS);
        worker.runAll();
        assertEquals(map(2), quick.join());
        assertEquals(map(1), late.join());
        assertEquals(map(1), storage.current);
    }
}
