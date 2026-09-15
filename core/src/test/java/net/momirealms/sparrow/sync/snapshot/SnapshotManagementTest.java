package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockCodec;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFilesTest;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDeleteResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotExportResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotImportResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotPinResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotUnpinResult;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.MemorySnapshotCache;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotManagementTest {
    @TempDir Path directory;
    private final Map<UUID, Snapshot> stored = new HashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    private final MemorySnapshotCache cache = new MemorySnapshotCache();
    private StorageProvider.SaveResult importResult = StorageProvider.SaveResult.SAVED;
    private boolean rejectBodyReads;
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
        NmsPlayerFixture.set(SparrowSync.class, plugin, "snapshotCache", this.cache);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataFolderPath", this.directory);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "binaryCodec", new BinarySnapshotCodec(CompressorRegistry.NONE));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "scheduler", proxy(SchedulerAdapter.class, (instance, method, args) -> {
            assertEquals("async", method.getName());
            return (Executor) Runnable::run;
        }));
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(instance, method, args);
            return switch (method.getName()) {
                case "snapshot" -> {
                    assertFalse(this.rejectBodyReads, "management must not load a body to identify its player");
                    yield CompletableFuture.completedFuture(Optional.ofNullable(this.stored.get((UUID) args[0])));
                }
                case "snapshotMeta" -> CompletableFuture.completedFuture(Optional.ofNullable(this.stored.get((UUID) args[0])).map(Snapshot::meta));
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
                    if (this.importResult.stored()) {
                        this.stored.put(snapshot.meta().id(), snapshot);
                    }
                    yield CompletableFuture.completedFuture(new StorageProvider.SaveOutcome(this.importResult, null));
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

    @ParameterizedTest
    @CsvSource({"inventory,crc", "external,crc", "inventory,nbt", "external,nbt", "inventory,compression", "external,compression", "inventory,length", "external,length"})
    void damagedBlockImportKeepsExistingRecordAndCache(String type, String damage) throws Exception {
        Snapshot original = SnapshotFilesTest.snapshot(UUID.randomUUID());
        Tag value = original.data(DataKey.of("unknown", "payload"));
        DataKey inventory = DataKey.sparrow("inventory");
        DataKey external = DataKey.of("external", "payload");
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        data.put(inventory, value);
        data.put(external, value);
        Snapshot snapshot = new Snapshot(original.meta(), data);
        this.stored.put(snapshot.meta().id(), snapshot);
        this.cache.publish(snapshot, 15).join();
        String output = this.service.files().export(snapshot, SnapshotFiles.Format.BINARY);
        Path file = this.directory.resolve(output);
        byte[] encoded = Files.readAllBytes(file);
        BinarySnapshotCodec codec = this.plugin.binaryCodec();
        Snapshot decoded = assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(encoded)).snapshot();
        int block = (int) decoded.content().raw(type.equals("inventory") ? inventory : external).offset();
        byte[] damaged = encoded.clone();
        ByteBuffer header = ByteBuffer.wrap(damaged);
        int payload = block + BlockCodec.BLOCK_HEADER_LENGTH;
        int length = header.getInt(block + 1);
        assertEquals(CompressorRegistry.NONE.id(), damaged[block]);
        switch (damage) {
            case "crc" -> damaged[payload] ^= 1;
            case "nbt" -> {
                damaged[payload] = 0;
                CRC32 crc = new CRC32();
                crc.update(damaged, payload, length);
                header.putInt(block + 9, (int) crc.getValue());
            }
            case "compression" -> damaged[block] = (byte) 127;
            case "length" -> header.putInt(block + 5, header.getInt(block + 5) + 1);
            default -> throw new AssertionError(damage);
        }
        assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(damaged));
        Files.write(file, damaged);
        assertSame(SnapshotImportResult.INVALID_FILE, this.service.importFile(output.substring("snapshot/output/".length())).join());
        assertEquals(0, this.writes.get());
        assertSame(snapshot, this.stored.get(snapshot.meta().id()));
        assertTrue(this.cache.invalidations.isEmpty());
        assertSame(snapshot, this.cache.consume(snapshot.meta().player()).join().orElseThrow());
    }

    @Test
    void deletionInvalidatesCachedBodyBeforeReportingSuccess() {
        Snapshot snapshot = SnapshotFilesTest.snapshot(UUID.randomUUID());
        this.stored.put(snapshot.meta().id(), snapshot);
        this.cache.publish(snapshot, 15).join();
        this.cache.invalidation = new CompletableFuture<>();
        this.rejectBodyReads = true;
        CompletableFuture<SnapshotDeleteResult> result = this.service.delete(snapshot.meta().id());
        assertFalse(this.stored.containsKey(snapshot.meta().id()));
        assertEquals(List.of(snapshot.meta().player()), this.cache.invalidations);
        assertFalse(result.isDone());
        this.cache.invalidation.complete(null);
        assertSame(SnapshotDeleteResult.DELETED, result.join());
        assertTrue(this.cache.consume(snapshot.meta().player()).join().isEmpty());
    }

    @Test
    void missingDeletionLeavesExistingCacheIntact() {
        Snapshot snapshot = SnapshotFilesTest.snapshot(UUID.randomUUID());
        this.cache.publish(snapshot, 15).join();
        assertSame(SnapshotDeleteResult.NOT_FOUND, this.service.delete(UUID.randomUUID()).join());
        assertTrue(this.cache.invalidations.isEmpty());
        assertSame(snapshot, this.cache.consume(snapshot.meta().player()).join().orElseThrow());
    }

    @Test
    void importInvalidatesPreviousBodyBeforeReportingSuccess() throws Exception {
        Snapshot snapshot = SnapshotFilesTest.snapshot(UUID.randomUUID());
        Snapshot old = new Snapshot(snapshot.meta(), Map.of());
        this.stored.put(old.meta().id(), old);
        this.cache.publish(old, 15).join();
        this.cache.invalidation = new CompletableFuture<>();
        String output = this.service.files().export(snapshot, SnapshotFiles.Format.BINARY);
        CompletableFuture<SnapshotImportResult> result = this.service.importFile(output.substring("snapshot/output/".length()));
        assertEquals(snapshot, this.stored.get(snapshot.meta().id()));
        assertEquals(List.of(snapshot.meta().player()), this.cache.invalidations);
        assertFalse(result.isDone());
        this.cache.invalidation.complete(null);
        assertInstanceOf(SnapshotImportResult.Imported.class, result.join());
        assertTrue(this.cache.consume(snapshot.meta().player()).join().isEmpty());
    }

    @Test
    void rejectedImportKeepsExistingCache() throws Exception {
        Snapshot snapshot = SnapshotFilesTest.snapshot(UUID.randomUUID());
        this.stored.put(snapshot.meta().id(), snapshot);
        this.cache.publish(snapshot, 15).join();
        this.importResult = StorageProvider.SaveResult.REJECTED_OVERSIZED;
        String output = this.service.files().export(snapshot, SnapshotFiles.Format.BINARY);
        assertSame(SnapshotImportResult.FAILED, this.service.importFile(output.substring("snapshot/output/".length())).join());
        assertTrue(this.cache.invalidations.isEmpty());
        assertSame(snapshot, this.cache.consume(snapshot.meta().player()).join().orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
