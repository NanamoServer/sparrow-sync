package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFilesTest;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDeleteResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotExportResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotImportResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotPinResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotUnpinResult;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotManagementTest {
    @TempDir Path directory;
    private final Map<UUID, Snapshot> stored = new HashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    private SnapshotService service;
    private SparrowSync plugin;

    @BeforeAll
    static void initializeProxy() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @BeforeEach
    void setup() {
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        this.plugin = plugin;
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataFolderPath", this.directory);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "binaryCodec", new BinarySnapshotCodec(CompressorRegistry.NONE));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "scheduler", proxy(SchedulerAdapter.class, (instance, method, args) -> {
            assertEquals("async", method.getName());
            return (Executor) Runnable::run;
        }));
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(instance, method, args);
            return switch (method.getName()) {
                case "snapshot" -> CompletableFuture.completedFuture(Optional.ofNullable(this.stored.get((UUID) args[0])));
                case "setPinned" -> {
                    Snapshot old = this.stored.get((UUID) args[0]);
                    boolean pinned = (boolean) args[1];
                    boolean changed = old != null && old.meta().pinned() != pinned;
                    if (changed) this.stored.put(old.meta().id(), new Snapshot(old.meta().withPinned(pinned), old.allData()));
                    yield CompletableFuture.completedFuture(changed);
                }
                case "deleteSnapshot" -> CompletableFuture.completedFuture(this.stored.remove((UUID) args[0]) != null);
                case "importSnapshot" -> {
                    this.writes.incrementAndGet();
                    Snapshot snapshot = (Snapshot) args[0];
                    this.stored.put(snapshot.meta().id(), snapshot);
                    yield CompletableFuture.completedFuture(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.SAVED, null));
                }
                default -> throw new AssertionError("Unexpected storage operation: " + method.getName());
            };
        });
        NmsPlayerFixture.set(SparrowSync.class, plugin, "storageProvider", storage);
        this.service = new SnapshotService(plugin);
        this.service.onLoad();
    }

    @Test
    void pinAndUnpinAreIdempotentAndPinnedSnapshotsCanBeDeleted() {
        Snapshot snapshot = SnapshotFilesTest.snapshot(UUID.randomUUID());
        UUID player = snapshot.meta().player();
        UUID id = snapshot.meta().id();
        this.stored.put(id, snapshot);
        assertSame(SnapshotPinResult.UNCHANGED, this.service.pin(id).join());
        assertSame(SnapshotUnpinResult.UNPINNED, this.service.unpin(id).join());
        assertSame(SnapshotUnpinResult.UNCHANGED, this.service.unpin(id).join());
        assertSame(SnapshotPinResult.PINNED, this.service.pin(id).join());
        assertSame(SnapshotDeleteResult.DELETED, this.service.delete(id).join());
        assertSame(SnapshotDeleteResult.NOT_FOUND, this.service.delete(id).join());
    }

    @Test
    void managementTargetsSnapshotIdAndMissingSnapshotIsReported() {
        UUID id = UUID.randomUUID();
        assertSame(SnapshotPinResult.NOT_FOUND, this.service.pin(id).join());
        assertSame(SnapshotUnpinResult.NOT_FOUND, this.service.unpin(id).join());
        assertSame(SnapshotDeleteResult.NOT_FOUND, this.service.delete(id).join());
        assertSame(SnapshotExportResult.NOT_FOUND, this.service.export(id, SnapshotFiles.Format.BINARY).join());
        Snapshot snapshot = SnapshotFilesTest.snapshot(UUID.randomUUID());
        this.stored.put(snapshot.meta().id(), snapshot);
        SnapshotExportResult.Exported exported = assertInstanceOf(SnapshotExportResult.Exported.class, this.service.export(snapshot.meta().id(), SnapshotFiles.Format.BINARY).join());
        assertEquals(snapshot.meta().id(), exported.snapshotId());
        assertTrue(Files.isRegularFile(this.directory.resolve(exported.path())));
    }

    @Test
    void importPreservesIdentityDoesNotRotateAndOverwritesExistingContent() throws Exception {
        Snapshot snapshot = SnapshotFilesTest.snapshot(UUID.randomUUID());
        String output = this.service.files().export(snapshot, SnapshotFiles.Format.BINARY);
        String relative = output.substring("snapshot/output/".length());
        assertInstanceOf(SnapshotImportResult.Imported.class, this.service.importFile(relative).join());
        assertEquals(snapshot, this.stored.get(snapshot.meta().id()));
        assertInstanceOf(SnapshotImportResult.Imported.class, this.service.importFile(relative).join());
        this.stored.put(snapshot.meta().id(), new Snapshot(snapshot.meta().withPinned(false), snapshot.allData()));
        assertInstanceOf(SnapshotImportResult.Imported.class, this.service.importFile(relative).join());
        assertEquals(3, this.writes.get());
        assertEquals(snapshot, this.stored.get(snapshot.meta().id()));
        Files.write(this.directory.resolve(output), new byte[]{1, 2, 3});
        assertSame(SnapshotImportResult.INVALID_FILE, this.service.importFile(relative).join());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
