package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.map.MapStorage;
import net.momirealms.sparrow.sync.map.data.MapArchiveRecord;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionHeader;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StoredUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotDumpTest {
    @TempDir Path directory;
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.NONE);

    @Test
    void roundTripRetainsHistoryCutoffUsersMapsAndSilentlyOverwrites() throws Exception {
        Memory source = new Memory();
        for (int i = 1; i <= 102; i++) source.snapshots.put(id(i), snapshot(i));
        StoredUser user = new StoredUser(id(900), "Alice", 12345);
        source.users.put(user.player(), user);
        MapArchiveRecord map = new MapArchiveRecord(new MapIdentity(new MapSource("origin", 8), -7), 4189, 7654, new byte[]{9, 8, 7});
        source.maps.put(-7, map);
        source.sequence = 23;
        SnapshotFiles files = this.files();
        SnapshotDump.Result exported = this.dump(source).dump("all.zip", 101);
        assertNull(exported.failure());
        assertEquals(100, exported.snapshots());
        assertTrue(source.scans > 1);
        assertTrue(source.maxBatch <= 4);
        try (ZipFile zip = new ZipFile(exported.file().toFile())) {
            assertEquals(4, zip.size());
        }
        Memory target = new Memory();
        target.snapshots.put(id(1), new Snapshot(snapshot(1).meta().withPinned(false), Map.of()));
        List<MapArchiveRecord> refreshed = new ArrayList<>();
        SnapshotDump importer = new SnapshotDump(target.storage(), files, this.codec, record -> {
            assertSame(record, target.maps.get(record.identity().globalId()));
            refreshed.add(record);
            return CompletableFuture.completedFuture(null);
        });
        SnapshotDump.Result imported = importer.importFile("all.zip");
        assertNull(imported.failure());
        assertEquals(100, imported.snapshots());
        assertEquals(0, imported.failed());
        assertEquals(snapshot(1), target.snapshots.get(id(1)));
        assertEquals(snapshot(100), target.snapshots.get(id(100)));
        assertFalse(target.snapshots.containsKey(id(101)));
        assertEquals(user, target.users.get(user.player()));
        assertEquals(23, target.sequence);
        assertEquals(map.identity(), target.maps.get(-7).identity());
        assertEquals(map.updatedAt(), target.maps.get(-7).updatedAt());
        assertArrayEquals(map.data(), target.maps.get(-7).data());
        assertEquals(1, refreshed.size());
        assertNull(importer.importFile("all.zip").failure());
        assertEquals(100, target.snapshots.size());
        assertTrue(Files.exists(exported.file()));
    }

    @Test
    void exportReadFailureKeepsPreviousZipAndCleansTemporaryFile() throws Exception {
        Memory source = new Memory();
        for (int i = 1; i <= 10; i++) source.snapshots.put(id(i), snapshot(i));
        Files.createDirectories(this.files().dump());
        Path previous = this.files().dump().resolve("all.zip");
        Files.writeString(previous, "previous complete archive");
        source.failScan = 2;
        SnapshotDump.Result result = this.dump(source).dump("all.zip", 100);
        assertNotNull(result.failure());
        assertEquals(4, result.snapshots());
        assertEquals("previous complete archive", Files.readString(previous));
        assertEquals(10, source.snapshots.size());
        try (var paths = Files.list(this.files().dump())) {
            assertEquals(List.of(previous), paths.toList());
        }
    }

    @Test
    void publicationFailureDoesNotLeaveTemporaryArchive() throws Exception {
        Path target = this.files().dump().resolve("all.zip");
        Files.createDirectories(target);
        Files.writeString(target.resolve("occupied"), "keep");
        SnapshotDump.Result result = this.dump(new Memory()).dump("all.zip", 100);
        assertNotNull(result.failure());
        assertTrue(Files.exists(target.resolve("occupied")));
        try (var paths = Files.list(this.files().dump())) {
            assertEquals(List.of(target), paths.toList());
        }
    }

    @Test
    void twentyRejectedSnapshotsAreArchivedWhileEightyAreImported() throws Exception {
        Memory source = new Memory();
        for (int i = 1; i <= 100; i++) source.snapshots.put(id(i), snapshot(i));
        assertNull(this.dump(source).dump("all.zip", 101).failure());
        Memory target = new Memory();
        target.policy = snapshot -> snapshot.meta().id().getLeastSignificantBits() % 5 == 0
                ? CompletableFuture.completedFuture(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.REJECTED_OVERSIZED, new IOException("target size limit"))) : target.save(snapshot);
        SnapshotDump.Result result = this.dump(target).importFile("all.zip");
        assertNull(result.failure());
        assertEquals(80, result.snapshots());
        assertEquals(20, result.failed());
        assertEquals(80, target.snapshots.size());
        try (var paths = Files.list(this.files().exceptions().resolve("oversized"))) {
            List<Path> bodies = paths.filter(path -> path.toString().endsWith(".snapshot")).toList();
            assertEquals(20, bodies.size());
            for (Path body : bodies) {
                SnapshotMeta meta = ExceptionHeader.read(body).meta();
                assertNotNull(meta);
                assertArrayEquals(this.codec.encode(snapshot((int) meta.id().getLeastSignificantBits())), Files.readAllBytes(body));
                assertTrue(Files.readString(body.resolveSibling(body.getFileName() + ".error.txt")).contains("target size limit"));
            }
        }
    }

    @Test
    void malformedSnapshotIsArchivedAsOriginalBytesAndNextRecordStillImports() throws Exception {
        byte[] broken = {1, 2, 3};
        this.writeSnapshotZip("all.zip", broken, this.codec.encode(snapshot(2)));
        SnapshotDump.Result result = this.dump(new Memory()).importFile("all.zip");
        assertNull(result.failure());
        assertEquals(1, result.snapshots());
        assertEquals(1, result.failed());
        try (var paths = Files.list(this.files().exceptions().resolve("corrupted"))) {
            Path body = paths.filter(path -> path.toString().endsWith(".snapshot")).findFirst().orElseThrow();
            assertArrayEquals(broken, Files.readAllBytes(body));
            assertNull(ExceptionHeader.read(body).meta());
            assertTrue(this.files().deleteException("corrupted/" + body.getFileName()));
            assertFalse(Files.exists(body.resolveSibling(body.getFileName() + ".error.txt")));
        }
    }

    @Test
    void unavailableDatabaseStopsImportAndRetainsSuccessfulRecordsAndSourceZip() throws Exception {
        this.writeSnapshotZip("all.zip", this.codec.encode(snapshot(1)), this.codec.encode(snapshot(2)), this.codec.encode(snapshot(3)));
        byte[] original = Files.readAllBytes(this.files().dump().resolve("all.zip"));
        Memory target = new Memory();
        target.policy = snapshot -> snapshot.meta().id().equals(id(2))
                ? CompletableFuture.completedFuture(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.RETRY_LATER, new IOException("database offline"))) : target.save(snapshot);
        SnapshotDump.Result result = this.dump(target).importFile("all.zip");
        assertNotNull(result.failure());
        assertEquals(1, result.snapshots());
        assertEquals(0, result.failed());
        assertEquals(2, target.imports);
        assertEquals(Map.of(id(1), snapshot(1)), target.snapshots);
        assertFalse(Files.exists(this.files().exceptions()));
        assertArrayEquals(original, Files.readAllBytes(result.file()));
        target.policy = target::save;
        assertEquals(3, this.dump(target).importFile("all.zip").snapshots());
        assertEquals(3, target.snapshots.size());
    }

    @Test
    void exceptionDirectoryFailureStopsWithoutClaimingSnapshotWasArchived() throws Exception {
        this.writeSnapshotZip("all.zip", new byte[]{1}, this.codec.encode(snapshot(2)));
        Files.createDirectories(this.files().exceptions());
        Files.writeString(this.files().exceptions().resolve("corrupted"), "occupied");
        Memory target = new Memory();
        SnapshotDump.Result result = this.dump(target).importFile("all.zip");
        assertNotNull(result.failure());
        assertEquals(0, result.snapshots());
        assertEquals(0, result.failed());
        assertEquals(0, target.imports);
    }

    @Test
    void truncatedRecordStopsWithoutCreatingAnEmptySnapshot() throws Exception {
        Files.createDirectories(this.files().dump());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(this.files().dump().resolve("all.zip")))) {
            zip.putNextEntry(new ZipEntry("snapshots.bin"));
            DataOutputStream output = new DataOutputStream(zip);
            output.writeBoolean(true);
            output.writeInt(100);
            output.writeByte(1);
        }
        SnapshotDump.Result result = this.dump(new Memory()).importFile("all.zip");
        assertNotNull(result.failure());
        assertEquals(0, result.snapshots());
    }

    @Test
    void importWaitsForTheCurrentDatabaseWriteBeforeSubmittingTheNext() throws Exception {
        this.writeSnapshotZip("all.zip", this.codec.encode(snapshot(1)), this.codec.encode(snapshot(2)));
        Memory target = new Memory();
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<StorageProvider.SaveOutcome> first = new CompletableFuture<>();
        target.policy = snapshot -> {
            if (snapshot.meta().id().equals(id(1))) {
                entered.countDown();
                return first;
            }
            return target.save(snapshot);
        };
        try (var worker = Executors.newSingleThreadExecutor()) {
            var task = worker.submit(() -> this.dump(target).importFile("all.zip"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, target.imports);
            } finally {
                first.complete(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.SAVED, null));
            }
            assertNull(task.get(5, TimeUnit.SECONDS).failure());
            assertEquals(2, target.imports);
        }
    }

    @Test
    void unreadableZipIsReportedAsFailure() throws Exception {
        Files.createDirectories(this.files().dump());
        Files.writeString(this.files().dump().resolve("bad.zip"), "not a ZIP");
        assertNotNull(this.dump(new Memory()).importFile("bad.zip").failure());
    }

    @Test
    void dumpAndImportStayWithinTheirDirectory() {
        assertNotNull(this.dump(new Memory()).dump("../outside.zip", 100).failure());
        assertNotNull(this.dump(new Memory()).importFile("../output/file.snapshot").failure());
    }

    private SnapshotFiles files() {
        return new SnapshotFiles(this.directory, this.codec);
    }

    private SnapshotDump dump(Memory memory) {
        return new SnapshotDump(memory.storage(), this.files(), this.codec, map -> CompletableFuture.completedFuture(null));
    }

    private void writeSnapshotZip(String name, byte[]... records) throws IOException {
        Files.createDirectories(this.files().dump());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(this.files().dump().resolve(name)))) {
            zip.putNextEntry(new ZipEntry("snapshots.bin"));
            DataOutputStream output = new DataOutputStream(zip);
            for (int i = 0; i < records.length; i++) {
                output.writeBoolean(true);
                output.writeInt(records[i].length);
                output.write(records[i]);
            }
            output.writeBoolean(false);
        }
    }

    private static UUID id(int value) {
        return new UUID(0, value);
    }

    private static Snapshot snapshot(int value) {
        Snapshot original = SnapshotFixtures.snapshot();
        return new Snapshot(new SnapshotMeta(id(value), original.meta().player(), value, SaveCause.COMMAND, true, "source", original.meta().mcDataVersion()), original.allData());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class Memory {
        private final Map<UUID, Snapshot> snapshots = new HashMap<>();
        private final Map<UUID, StoredUser> users = new HashMap<>();
        private final Map<Integer, MapArchiveRecord> maps = new HashMap<>();
        private long sequence;
        private int scans;
        private int failScan;
        private int maxBatch;
        private volatile int imports;
        private Function<Snapshot, CompletableFuture<StorageProvider.SaveOutcome>> policy = this::save;

        private CompletableFuture<StorageProvider.SaveOutcome> save(Snapshot snapshot) {
            this.snapshots.put(snapshot.meta().id(), snapshot);
            return CompletableFuture.completedFuture(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.SAVED, null));
        }

        private StorageProvider storage() {
            MapStorage mapStorage = proxy(MapStorage.class, (instance, method, args) -> switch (method.getName()) {
                case "scan" -> CompletableFuture.completedFuture(this.maps.values().stream().filter(map -> map.identity().globalId() < (int) args[0])
                        .sorted(Comparator.comparingInt((MapArchiveRecord map) -> map.identity().globalId()).reversed()).limit((int) args[1]).toList());
                case "sequence" -> CompletableFuture.completedFuture(this.sequence);
                case "importSequence" -> {
                    this.sequence = Math.max(this.sequence, (long) args[0]);
                    yield CompletableFuture.completedFuture(null);
                }
                case "importMap" -> {
                    MapArchiveRecord map = (MapArchiveRecord) args[0];
                    this.maps.put(map.identity().globalId(), map);
                    this.sequence = Math.max(this.sequence, -(long) map.identity().globalId());
                    yield CompletableFuture.completedFuture(null);
                }
                default -> throw new AssertionError(method.getName());
            });
            return proxy(StorageProvider.class, (instance, method, args) -> switch (method.getName()) {
                case "maps" -> mapStorage;
                case "scanUsers" -> CompletableFuture.completedFuture(this.users.values().stream().filter(user -> args[0] == null || user.player().compareTo((UUID) args[0]) > 0)
                        .sorted(Comparator.comparing(StoredUser::player)).limit((int) args[1]).toList());
                case "scanSnapshots" -> {
                    this.scans++;
                    if (this.scans == this.failScan) yield CompletableFuture.failedFuture(new IOException("source unavailable"));
                    this.maxBatch = Math.max(this.maxBatch, (int) args[2]);
                    yield CompletableFuture.completedFuture(this.snapshots.values().stream().filter(snapshot -> snapshot.meta().timestamp() < (long) args[0]
                                    && (args[1] == null || snapshot.meta().id().compareTo((UUID) args[1]) > 0))
                            .sorted(Comparator.comparing(snapshot -> snapshot.meta().id())).limit((int) args[2]).toList());
                }
                case "importUser" -> {
                    StoredUser user = (StoredUser) args[0];
                    this.users.put(user.player(), user);
                    yield CompletableFuture.completedFuture(null);
                }
                case "importSnapshot" -> {
                    this.imports++;
                    yield this.policy.apply((Snapshot) args[0]);
                }
                default -> throw new AssertionError(method.getName());
            });
        }
    }
}
