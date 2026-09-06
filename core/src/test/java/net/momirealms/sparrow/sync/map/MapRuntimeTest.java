package net.momirealms.sparrow.sync.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static net.momirealms.sparrow.sync.map.MapFlowTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class MapRuntimeTest {
    private final Storage storage = new Storage();
    private final Shared shared = new Shared();
    private final AtomicLong clock = new AtomicLong();
    private final List<StoredMap> installed = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final MapReceiver receiver = new MapReceiver(() -> CompletableFuture.completedFuture(this.storage), this.shared, value -> () -> {
        this.installed.add(value);
        return -1;
    }, Runnable::run, Runnable::run, logger(this.warnings));

    @Test
    void observesPersistedReplicaOnceAndRevalidatesQuietMapsAgainstDatabase() {
        this.storage.current = map(2);
        this.shared.contents.put(-1, map(1));
        AtomicInteger lookups = new AtomicInteger();
        MapRuntime runtime = new MapRuntime(this.receiver, id -> {
            lookups.incrementAndGet();
            return CompletableFuture.completedFuture(IDENTITY);
        }, this.clock::get, logger(this.warnings));
        runtime.observe(1);
        runtime.observe(-1);
        runtime.observe(-1);
        assertEquals(1, lookups.get());
        assertEquals(List.of(map(2)), this.installed);
        assertEquals(0, this.shared.reads);
        this.storage.current = map(3);
        runtime.tick();
        assertEquals(1, this.storage.reads);
        this.clock.addAndGet(TimeUnit.MINUTES.toNanos(4));
        runtime.installed(IDENTITY);
        this.clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
        runtime.tick();
        assertEquals(List.of(map(2), map(3)), this.installed);
        assertTrue(this.shared.writes.isEmpty());
        assertEquals(0, this.storage.registrations);
        assertTrue(this.storage.writes.isEmpty());
    }

    @Test
    void notificationRefreshesKnownReplicaAndReconnectBypassesStaleRedis() {
        this.shared.contents.put(-1, map(1));
        MapRuntime runtime = new MapRuntime(this.receiver, id -> CompletableFuture.completedFuture(null), this.clock::get, logger(this.warnings));
        runtime.installed(IDENTITY);
        runtime.invalidate(-1);
        assertEquals(List.of(map(1)), this.installed);
        this.shared.contents.put(-1, map(2));
        runtime.invalidate(-1);
        assertEquals(List.of(map(1), map(2)), this.installed);
        this.storage.current = map(3);
        runtime.revalidate();
        assertEquals(List.of(map(1), map(2), map(3)), this.installed);
        assertEquals(map(2), this.shared.contents.get(-1));
        assertEquals(1, this.storage.reads);
    }

    @Test
    void backgroundFailureRetainsPixelsAndRetriesWithoutLogFlood() {
        this.storage.current = map(1);
        MapRuntime runtime = new MapRuntime(this.receiver, id -> CompletableFuture.completedFuture(IDENTITY), this.clock::get, logger(this.warnings));
        runtime.observe(-1);
        this.storage.current = null;
        runtime.revalidate();
        runtime.revalidate();
        assertEquals(List.of(map(1)), this.installed);
        assertEquals(1, this.warnings.size());
        this.storage.current = map(4);
        this.clock.addAndGet(TimeUnit.SECONDS.toNanos(29));
        runtime.tick();
        assertEquals(List.of(map(1)), this.installed);
        this.clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        runtime.tick();
        assertEquals(List.of(map(1), map(4)), this.installed);
        assertEquals(1, this.warnings.size());
    }

    @Test
    void closingStopsCallbacksAndRetainsIdentitiesForReenable() {
        this.storage.current = map(1);
        MapRuntime runtime = new MapRuntime(this.receiver, id -> CompletableFuture.completedFuture(IDENTITY), this.clock::get, logger(this.warnings));
        runtime.observe(-1);
        runtime.close();
        this.receiver.close();
        this.storage.current = map(2);
        runtime.invalidate(-1);
        runtime.revalidate();
        this.clock.addAndGet(TimeUnit.HOURS.toNanos(1));
        runtime.tick();
        runtime.observe(-2);
        assertEquals(List.of(map(1)), this.installed);
        assertEquals(List.of(IDENTITY), runtime.identities());
        MapReceiver resumedReceiver = new MapReceiver(() -> CompletableFuture.completedFuture(this.storage), this.shared, value -> () -> {
            this.installed.add(value);
            return -1;
        }, Runnable::run, Runnable::run, logger(this.warnings));
        MapRuntime resumed = new MapRuntime(resumedReceiver, id -> { throw new AssertionError("known map identity was lost"); }, this.clock::get, logger(this.warnings));
        for (MapIdentity identity : runtime.identities()) {
            resumed.installed(identity);
        }
        resumed.revalidate();
        assertEquals(List.of(map(1), map(2)), this.installed);
    }

    @Test
    void unrelatedNegativeMapsNeverReachStorage() {
        AtomicInteger lookups = new AtomicInteger();
        MapRuntime runtime = new MapRuntime(this.receiver, id -> {
            lookups.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, this.clock::get, logger(this.warnings));
        runtime.observe(-8);
        runtime.observe(-8);
        this.clock.addAndGet(TimeUnit.HOURS.toNanos(1));
        runtime.tick();
        runtime.revalidate();
        assertEquals(1, lookups.get());
        assertTrue(runtime.identities().isEmpty());
        assertEquals(0, this.storage.reads);
        assertEquals(0, this.shared.reads);
        assertTrue(this.warnings.isEmpty());
    }
}
