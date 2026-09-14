package net.momirealms.sparrow.sync.api;

import net.momirealms.sparrow.sync.cluster.cache.SnapshotCache;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.SnapshotService;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDeleteResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotPinResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotUnpinResult;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(PluginConfigExtension.class)
class SparrowSyncAPITest {
    private final UUID playerId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final Map<UUID, Snapshot> stored = new HashMap<>();
    private final List<SnapshotMeta> page = new ArrayList<>();
    private final List<UUID> invalidated = new ArrayList<>();
    private SparrowSync plugin;
    private SparrowSyncAPI api;
    private Object previousInstance;
    private Server previousServer;
    private int storageCalls;
    private boolean storageClosed;
    private boolean deletedConcurrently;
    private RuntimeException storageFailure;
    private SnapshotQuery query;
    private CompletableFuture<Optional<Snapshot>> latest = CompletableFuture.completedFuture(Optional.empty());
    private CompletableFuture<Void> cacheInvalidation = CompletableFuture.completedFuture(null);

    @BeforeEach
    void setup() throws Exception {
        Field instance = SparrowSync.class.getDeclaredField("instance");
        instance.setAccessible(true);
        this.previousInstance = instance.get(null);
        this.previousServer = Bukkit.getServer();
        this.plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        this.api = new SparrowSyncAPI(this.plugin);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "api", this.api);
        NmsPlayerFixture.set(SparrowSync.class, null, "instance", this.plugin);
        StorageProvider storage = proxy(StorageProvider.class, (receiver, method, args) -> {
            if (method.getName().equals("shutdown")) {
                assertFalse(this.plugin.apiReady());
                this.assertUnavailable(this.api.snapshot(this.snapshotId));
                this.storageClosed = true;
                return null;
            }
            this.storageCalls++;
            if (this.storageFailure != null) return CompletableFuture.failedFuture(this.storageFailure);
            return switch (method.getName()) {
                case "latestSnapshot" -> {
                    assertEquals(this.playerId, args[0]);
                    yield this.latest;
                }
                case "snapshot" -> CompletableFuture.completedFuture(Optional.ofNullable(this.stored.get(args[0])));
                case "snapshotMeta" -> CompletableFuture.completedFuture(Optional.ofNullable(this.stored.get(args[0])).map(Snapshot::meta));
                case "listSnapshots" -> {
                    this.query = (SnapshotQuery) args[0];
                    yield CompletableFuture.completedFuture(this.page);
                }
                case "setPinned" -> {
                    Snapshot current = this.stored.get(args[0]);
                    boolean changed = current != null && current.meta().pinned() != (boolean) args[1];
                    if (changed) {
                        this.stored.put((UUID) args[0], new Snapshot(current.meta().withPinned((boolean) args[1]), current.content()));
                    }
                    yield CompletableFuture.completedFuture(changed);
                }
                case "deleteSnapshot" -> {
                    Snapshot removed = this.stored.remove(args[0]);
                    yield CompletableFuture.completedFuture(removed != null && !this.deletedConcurrently);
                }
                default -> throw new AssertionError("Unexpected storage call: " + method.getName());
            };
        });
        SnapshotCache cache = proxy(SnapshotCache.class, (receiver, method, args) -> {
            assertEquals("invalidate", method.getName());
            this.invalidated.add((UUID) args[0]);
            return this.cacheInvalidation;
        });
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "storageProvider", storage);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "snapshotCache", cache);
        SnapshotService service = new SnapshotService(this.plugin);
        NmsPlayerFixture.set(SnapshotService.class, service, "storage", storage);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "snapshotService", service);
        this.ready(true);
    }

    @AfterEach
    void cleanup() {
        NmsPlayerFixture.set(SparrowSync.class, null, "instance", this.previousInstance);
        NmsPlayerFixture.set(Bukkit.class, null, "server", this.previousServer);
    }

    @Test
    void entryRequiresPluginAndReturnsSameObjectDuringInitialization() {
        NmsPlayerFixture.set(SparrowSync.class, null, "instance", null);
        assertThrows(IllegalStateException.class, SparrowSync::api);
        NmsPlayerFixture.set(SparrowSync.class, null, "instance", this.plugin);
        this.ready(false);
        assertSame(this.api, SparrowSync.api());
        this.ready(true);
        assertSame(this.api, SparrowSync.api());
    }

    @Test
    void rejectsEveryOperationBeforeBusinessInitialization() {
        this.ready(false);
        this.assertAllUnavailable();
        assertEquals(0, this.storageCalls);
        assertTrue(this.invalidated.isEmpty());
    }

    @Test
    void disableRejectsCachedApiBeforeClosingStorage() {
        // 模拟业务装配尚未完成时的停服, 只保留已创建的存储与 API.
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "snapshotService", null);
        NmsPlayerFixture.set(Bukkit.class, null, "server", proxy(Server.class, (receiver, method, args) -> {
            assertEquals("isStopping", method.getName());
            return true;
        }));
        SparrowSyncAPI cached = SparrowSync.api();
        this.plugin.onPluginDisable();
        assertTrue(this.storageClosed);
        assertSame(cached, SparrowSync.api());
        this.assertAllUnavailable();
        assertEquals(0, this.storageCalls);
    }

    @Test
    void queriesPreserveLazyDataAndReturnOriginalSnapshot() {
        IllegalArgumentException corruptBlock = new IllegalArgumentException("corrupt block");
        SnapshotData data = proxy(SnapshotData.class, (receiver, method, args) -> { throw corruptBlock; });
        Snapshot snapshot = new Snapshot(this.meta(false), data);
        this.stored.put(this.snapshotId, snapshot);
        this.latest = CompletableFuture.completedFuture(Optional.of(snapshot));
        assertSame(snapshot, this.api.latestSnapshot(this.playerId).join().orElseThrow());
        assertSame(snapshot, this.api.snapshot(this.snapshotId).join().orElseThrow());
        assertSame(corruptBlock, assertThrows(IllegalArgumentException.class, () -> snapshot.data(DataKey.of("test", "value"))));
        assertTrue(this.invalidated.isEmpty());
    }

    @Test
    void missingQueriesReturnEmptyResults() {
        assertTrue(this.api.latestSnapshot(this.playerId).join().isEmpty());
        assertTrue(this.api.snapshot(this.snapshotId).join().isEmpty());
        assertTrue(this.api.snapshots(this.playerId, 0, 20).join().isEmpty());
    }

    @Test
    void historyUsesBoundedMetadataQueryAndReturnsImmutableCopy() {
        SnapshotMeta meta = this.meta(false);
        this.page.add(meta);
        List<SnapshotMeta> found = this.api.snapshots(this.playerId, 40, 20).join();
        assertEquals(SnapshotQuery.of(this.playerId).withOffset(40).withLimit(20), this.query);
        assertSame(meta, found.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> found.add(meta));
        this.page.clear();
        assertEquals(1, found.size());
        assertEquals(1, this.storageCalls);
    }

    @Test
    void invalidPaginationFailsBeforeAccessingStorage() {
        assertThrows(IllegalArgumentException.class, () -> this.api.snapshots(this.playerId, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> this.api.snapshots(this.playerId, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> this.api.snapshots(this.playerId, 0, -1));
        assertEquals(0, this.storageCalls);
    }

    @Test
    void everyOperationPreservesStorageFailures() {
        this.storageFailure = new IllegalStateException("database unavailable");
        List<CompletableFuture<?>> operations = List.of(
                this.api.latestSnapshot(this.playerId), this.api.snapshot(this.snapshotId),
                this.api.snapshots(this.playerId, 0, 20), this.api.pin(this.snapshotId),
                this.api.unpin(this.snapshotId), this.api.delete(this.snapshotId));
        for (int i = 0; i < operations.size(); i++) {
            CompletableFuture<?> operation = operations.get(i);
            assertSame(this.storageFailure, assertThrows(CompletionException.class, operation::join).getCause());
        }
    }

    @Test
    void pinAndUnpinDistinguishChangedUnchangedAndMissing() {
        this.stored.put(this.snapshotId, new Snapshot(this.meta(false), Map.of()));
        assertSame(SnapshotPinResult.PINNED, this.api.pin(this.snapshotId).join());
        assertSame(SnapshotPinResult.UNCHANGED, this.api.pin(this.snapshotId).join());
        assertTrue(this.stored.get(this.snapshotId).meta().pinned());
        assertSame(SnapshotUnpinResult.UNPINNED, this.api.unpin(this.snapshotId).join());
        assertSame(SnapshotUnpinResult.UNCHANGED, this.api.unpin(this.snapshotId).join());
        assertFalse(this.stored.get(this.snapshotId).meta().pinned());
        this.stored.clear();
        assertSame(SnapshotPinResult.NOT_FOUND, this.api.pin(this.snapshotId).join());
        assertSame(SnapshotUnpinResult.NOT_FOUND, this.api.unpin(this.snapshotId).join());
        assertTrue(this.invalidated.isEmpty());
    }

    @Test
    void deletingPinnedSnapshotWaitsForCacheInvalidation() {
        this.stored.put(this.snapshotId, new Snapshot(this.meta(true), Map.of()));
        this.cacheInvalidation = new CompletableFuture<>();
        CompletableFuture<SnapshotDeleteResult> deleted = this.api.delete(this.snapshotId);
        assertTrue(this.stored.isEmpty());
        assertEquals(List.of(this.playerId), this.invalidated);
        assertFalse(deleted.isDone());
        this.cacheInvalidation.complete(null);
        assertSame(SnapshotDeleteResult.DELETED, deleted.join());
    }

    @Test
    void deletingMissingSnapshotLeavesCacheUntouched() {
        assertSame(SnapshotDeleteResult.NOT_FOUND, this.api.delete(this.snapshotId).join());
        assertTrue(this.invalidated.isEmpty());
    }

    @Test
    void concurrentDeletionReportsMissing() {
        this.stored.put(this.snapshotId, new Snapshot(this.meta(false), Map.of()));
        this.deletedConcurrently = true;
        assertSame(SnapshotDeleteResult.NOT_FOUND, this.api.delete(this.snapshotId).join());
        assertTrue(this.invalidated.isEmpty());
    }

    @Test
    void cacheFailureIsVisibleAfterDatabaseDeletion() {
        this.stored.put(this.snapshotId, new Snapshot(this.meta(false), Map.of()));
        RuntimeException failure = new IllegalStateException("cache unavailable");
        this.cacheInvalidation = CompletableFuture.failedFuture(failure);
        CompletableFuture<SnapshotDeleteResult> deleted = this.api.delete(this.snapshotId);
        assertSame(failure, assertThrows(CompletionException.class, deleted::join).getCause());
        assertTrue(this.stored.isEmpty());
    }

    @Test
    void acceptedQueryCanFinishAfterNewOperationsAreRejected() {
        Snapshot snapshot = new Snapshot(this.meta(false), Map.of());
        this.latest = new CompletableFuture<>();
        CompletableFuture<Optional<Snapshot>> requested = this.api.latestSnapshot(this.playerId);
        assertFalse(requested.isDone());
        this.ready(false);
        this.latest.complete(Optional.of(snapshot));
        assertSame(snapshot, requested.join().orElseThrow());
        this.assertUnavailable(this.api.latestSnapshot(this.playerId));
    }

    private void ready(boolean ready) {
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "apiReady", ready);
    }

    private void assertAllUnavailable() {
        this.assertUnavailable(this.api.latestSnapshot(this.playerId));
        this.assertUnavailable(this.api.snapshot(this.snapshotId));
        this.assertUnavailable(this.api.snapshots(this.playerId, 0, 20));
        this.assertUnavailable(this.api.pin(this.snapshotId));
        this.assertUnavailable(this.api.unpin(this.snapshotId));
        this.assertUnavailable(this.api.delete(this.snapshotId));
    }

    private void assertUnavailable(CompletableFuture<?> result) {
        assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, result::join).getCause());
    }

    private SnapshotMeta meta(boolean pinned) {
        return new SnapshotMeta(this.snapshotId, this.playerId, 1, SaveCause.COMMAND, pinned, "test", 0);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
