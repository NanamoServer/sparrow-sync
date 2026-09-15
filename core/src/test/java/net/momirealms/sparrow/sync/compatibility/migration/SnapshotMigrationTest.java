package net.momirealms.sparrow.sync.compatibility.migration;

import net.momirealms.sparrow.sync.test.NoopSnapshotCache;
import net.momirealms.sparrow.sync.test.SnapshotFileTestLogger;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotDump;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StoredUser;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.bson.Document;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotMigrationTest {
    @TempDir Path directory;
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.NONE);
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private final Map<UUID, StoredUser> users = new HashMap<>();
    private int writes;
    private Function<Snapshot, CompletableFuture<StorageProvider.SaveOutcome>> save = this::store;

    @Test
    void generatesCompleteStandardZipBeforeWritingAndReplaysWithoutSource() throws Exception {
        Snapshot unrelated = SnapshotFixtures.snapshot();
        this.snapshots.put(unrelated.meta().id(), unrelated);
        SnapshotMigration.Result result = this.migrate(sink -> {
            for (int i = 1; i <= 12; i++) {
                sink.accept(data(i));
                assertEquals(0, this.writes);
                assertFalse(Files.exists(this.files().dump().resolve("migration.zip")));
            }
        });
        assertNull(result.failure());
        assertNotNull(result.imported());
        assertNull(result.imported().failure());
        assertEquals(12, result.converted());
        assertEquals(12, result.users());
        assertEquals(0, result.failed());
        try (ZipFile zip = new ZipFile(result.file().toFile())) {
            assertEquals(List.of("snapshots.bin", "users.bin"), zip.stream().map(entry -> entry.getName()).toList());
        }
        Snapshot first = this.snapshots.values().stream().filter(snapshot -> snapshot.meta().player().equals(id(1))).findFirst().orElseThrow();
        assertEquals(SaveCause.MIGRATION, first.meta().cause());
        assertEquals(1001, first.meta().timestamp());
        assertEquals(VersionHelper.WORLD_VERSION, first.meta().mcDataVersion());
        assertEquals("migration-server", first.meta().server());
        assertFalse(first.meta().pinned());
        assertEquals(SnapshotFixtures.snapshot().allData(), first.allData());
        assertEquals(new StoredUser(id(1), "Player1", 501), this.users.get(id(1)));
        Map<UUID, Snapshot> once = Map.copyOf(this.snapshots);
        this.snapshots.put(first.meta().id(), new Snapshot(first.meta(), Map.of()));
        assertNull(this.importer().importFile("migration.zip").failure());
        assertEquals(once, this.snapshots);
        assertSame(unrelated, this.snapshots.get(unrelated.meta().id()));
        this.assertOnlyZip();
    }

    @Test
    void unknownLastSeenSurvivesZipAndRepeatedImport() {
        StoredUser user = new StoredUser(id(1), "Player1", 0);
        SnapshotMigration.Result result = this.migrate(sink -> sink.accept(new MigrationSource.PlayerData(id(1), user, null, SnapshotFixtures.snapshot().allData())));
        assertNull(result.failure());
        assertNull(result.imported().failure());
        assertEquals(1, result.users());
        assertEquals(user, this.users.get(id(1)));
        assertNull(this.importer().importFile("migration.zip").failure());
        assertEquals(Map.of(id(1), user), this.users);
        assertEquals(1, this.snapshots.size());
    }

    @Test
    void missingSourceTimeUsesBatchTimeWithoutInventingUserMapping() {
        SnapshotMigration.Result result = this.migrate(sink -> sink.accept(new MigrationSource.PlayerData(id(1), null, null, SnapshotFixtures.snapshot().allData())));
        assertNull(result.failure());
        assertEquals(0, result.users());
        assertTrue(this.users.isEmpty());
        assertEquals(12345, this.snapshots.values().iterator().next().meta().timestamp());
    }

    @Test
    void badPlayerCreatesVisibleHeadAndRawAttachmentThenContinues() throws Exception {
        byte[] raw = {0, 1, 2, 3};
        SnapshotMigration.Result result = this.migrate(sink -> {
            sink.accept(data(1));
            sink.reject(id(2), "BadPlayer", "inventory", new IOException("source item is broken"), raw);
            sink.accept(data(3));
        });
        assertNull(result.failure());
        assertNull(result.imported().failure());
        assertEquals(2, result.converted());
        assertEquals(1, result.failed());
        assertEquals(2, this.snapshots.size());
        assertFalse(this.users.containsKey(id(2)));
        SnapshotFiles files = this.files();
        SnapshotFiles.ExceptionPage page = files.listExceptions(id(2), "migration", 0, 10);
        assertEquals(1, page.total());
        SnapshotFiles.ExceptionEntry entry = page.content().getFirst();
        assertEquals(SnapshotFiles.HeadStatus.AVAILABLE, entry.headStatus());
        assertFalse(entry.bodyPresent());
        assertEquals("BadPlayer", entry.header().playerName());
        assertEquals(SaveCause.MIGRATION, entry.header().meta().cause());
        Path body = files.exceptionFile(entry.path());
        assertFalse(Files.exists(body));
        assertArrayEquals(raw, Files.readAllBytes(body.resolveSibling(body.getFileName() + ".source")));
        String reason = Files.readString(body.resolveSibling(body.getFileName() + ".error.txt"));
        assertEquals("husksync", Document.parse(reason.lines().findFirst().orElseThrow()).getString("source"));
        assertEquals("inventory", Document.parse(reason.lines().findFirst().orElseThrow()).getString("stage"));
        assertTrue(reason.contains("source item is broken"));
        assertNull(this.importer().importFile("migration.zip").failure());
        assertEquals(2, this.snapshots.size());
        assertTrue(files.deleteException(entry.path()));
        try (var paths = Files.list(body.getParent())) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void unavailableRawDataRemainsDiagnosticOnly() throws Exception {
        SnapshotMigration.Result result = this.migrate(sink -> sink.reject(id(1), null, "decode", new IllegalArgumentException("invalid"), null));
        assertNull(result.failure());
        assertEquals(1, result.failed());
        assertEquals(0, result.imported().snapshots());
        SnapshotFiles.ExceptionEntry entry = this.files().listExceptions(id(1), null, 0, 10).content().getFirst();
        Path body = this.files().exceptionFile(entry.path());
        assertFalse(Files.exists(body));
        assertFalse(Files.exists(body.resolveSibling(body.getFileName() + ".source")));
        assertFalse(Document.parse(Files.readAllLines(body.resolveSibling(body.getFileName() + ".error.txt")).getFirst()).getBoolean("rawAttached"));
    }

    @Test
    void sourceFailureKeepsPreviousZipAndNeverTouchesTarget() throws Exception {
        Files.createDirectories(this.files().dump());
        Path previous = this.files().dump().resolve("migration.zip");
        Files.writeString(previous, "previous complete ZIP");
        IOException failure = new IOException("source database offline");
        SnapshotMigration.Result result = this.migrate(sink -> {
            sink.accept(data(1));
            throw failure;
        });
        assertSame(failure, result.failure());
        assertNull(result.imported());
        assertEquals(0, this.writes);
        assertEquals("previous complete ZIP", Files.readString(previous));
        this.assertOnlyZip();
    }

    @Test
    void archiveFailureAbortsGenerationAndIsNotCountedAsSkipped() throws Exception {
        Files.createDirectories(this.files().exceptions());
        Files.writeString(this.files().exceptions().resolve("migration"), "occupied");
        SnapshotMigration.Result result = this.migrate(sink -> {
            sink.accept(data(1));
            sink.reject(id(2), null, "inventory", new IOException("bad item"), null);
        });
        assertInstanceOf(IOException.class, result.failure());
        assertNull(result.imported());
        assertEquals(0, result.failed());
        assertEquals(0, this.writes);
        try (var paths = Files.list(this.files().dump())) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void publicationFailureNeverStartsImportOrLeavesTemporaryFiles() throws Exception {
        Path target = this.files().dump().resolve("migration.zip");
        Files.createDirectories(target);
        Files.writeString(target.resolve("keep"), "existing");
        SnapshotMigration.Result result = this.migrate(sink -> sink.accept(data(1)));
        assertNotNull(result.failure());
        assertNull(result.imported());
        assertEquals(0, this.writes);
        assertEquals("existing", Files.readString(target.resolve("keep")));
        this.assertOnlyZip();
    }

    @Test
    void databaseFailureRetainsCompleteZipAndReplayConverges() throws Exception {
        this.save = snapshot -> snapshot.meta().player().equals(id(2))
                ? CompletableFuture.completedFuture(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.RETRY_LATER, new IOException("offline"))) : this.store(snapshot);
        SnapshotMigration.Result result = this.migrate(sink -> {
            for (int i = 1; i <= 3; i++) sink.accept(data(i));
        });
        assertNull(result.failure());
        assertNotNull(result.imported().failure());
        assertEquals(3, result.converted());
        assertEquals(1, result.imported().snapshots());
        assertEquals(1, this.snapshots.size());
        UUID alreadyWritten = this.snapshots.keySet().iterator().next();
        byte[] zip = Files.readAllBytes(result.file());
        this.save = this::store;
        assertNull(this.importer().importFile("migration.zip").failure());
        assertEquals(3, this.snapshots.size());
        assertTrue(this.snapshots.containsKey(alreadyWritten));
        assertEquals(3, this.users.size());
        assertNull(this.importer().importFile("migration.zip").failure());
        assertEquals(3, this.snapshots.size());
        assertArrayEquals(zip, Files.readAllBytes(result.file()));
        this.assertOnlyZip();
    }

    @Test
    void committedWriteWithLostResponseIsOverwrittenOnReplay() throws Exception {
        Snapshot unrelated = SnapshotFixtures.snapshot();
        this.snapshots.put(unrelated.meta().id(), unrelated);
        this.save = snapshot -> {
            this.store(snapshot);
            return CompletableFuture.failedFuture(new IOException("connection lost after commit"));
        };
        SnapshotMigration.Result result = this.migrate(sink -> {
            for (int i = 1; i <= 3; i++) sink.accept(data(i));
        });
        assertNull(result.failure());
        assertNotNull(result.imported().failure());
        assertEquals(0, result.imported().snapshots());
        assertEquals(2, this.snapshots.size());
        Snapshot committed = this.snapshots.values().stream().filter(snapshot -> !snapshot.equals(unrelated)).findFirst().orElseThrow();
        this.save = this::store;
        assertNull(this.importer().importFile("migration.zip").failure());
        Map<UUID, Snapshot> recovered = Map.copyOf(this.snapshots);
        assertEquals(4, recovered.size());
        assertEquals(committed, recovered.get(committed.meta().id()));
        assertSame(unrelated, recovered.get(unrelated.meta().id()));
        assertNull(this.importer().importFile("migration.zip").failure());
        assertEquals(recovered, this.snapshots);
        for (Snapshot snapshot : this.snapshots.values()) assertEquals(SnapshotFixtures.snapshot().allData(), snapshot.allData());
    }

    @Test
    void publishedZipCanRecoverBeforeFirstImportAndReportsStagesOnWorker() throws Exception {
        Thread worker = Thread.currentThread();
        ArrayList<SnapshotMigration.Result> stages = new ArrayList<>();
        SnapshotMigration.Result result = this.migrate(sink -> sink.accept(data(1)), progress -> {
            assertSame(worker, Thread.currentThread());
            stages.add(progress);
            assertEquals(0, this.writes);
            if (progress.imported() != null) throw new IllegalStateException("stopped before first database write");
        });
        assertEquals(2, stages.size());
        assertNull(stages.getFirst().imported());
        assertEquals(0, stages.getFirst().converted());
        assertNotNull(stages.getLast().imported());
        assertEquals(1, stages.getLast().converted());
        assertEquals(0, stages.getLast().imported().snapshots());
        assertNull(result.failure());
        assertNotNull(result.imported().failure());
        assertTrue(Files.isRegularFile(result.file()));
        assertEquals(0, this.writes);
        assertNull(this.importer().importFile("migration.zip").failure());
        Map<UUID, Snapshot> recovered = Map.copyOf(this.snapshots);
        assertEquals(1, recovered.size());
        assertNull(this.importer().importFile("migration.zip").failure());
        assertEquals(recovered, this.snapshots);
    }

    @Test
    void regeneratingSourceCreatesNewSnapshotIdentities() {
        assertNull(this.migrate(sink -> sink.accept(data(1))).failure());
        UUID first = this.snapshots.keySet().iterator().next();
        assertNull(this.migrate(sink -> sink.accept(data(1))).failure());
        assertEquals(2, this.snapshots.size());
        assertTrue(this.snapshots.containsKey(first));
        assertNull(this.importer().importFile("migration.zip").failure());
        assertEquals(2, this.snapshots.size());
    }

    @Test
    void sourceLinkageFailureStopsAndCleansGeneration() throws Exception {
        NoSuchMethodError failure = new NoSuchMethodError("source API changed");
        SnapshotMigration.Result result = this.migrate(sink -> {
            sink.accept(data(1));
            throw failure;
        });
        assertSame(failure, result.failure());
        assertNull(result.imported());
        assertEquals(0, this.writes);
        try (var paths = Files.list(this.files().dump())) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void interruptionRestoresInterruptFlagAndCleansGeneration() throws Exception {
        try {
            SnapshotMigration.Result result = this.migrate(sink -> { throw new InterruptedException("cancelled"); });
            assertInstanceOf(InterruptedException.class, result.failure());
            assertTrue(Thread.currentThread().isInterrupted());
            assertNull(result.imported());
            assertEquals(0, this.writes);
            try (var paths = Files.list(this.files().dump())) {
                assertEquals(0, paths.count());
            }
        } finally {
            Thread.interrupted();
        }
    }

    private SnapshotMigration.Result migrate(Reader reader) {
        return this.migrate(reader, progress -> {});
    }

    private SnapshotMigration.Result migrate(Reader reader, Consumer<SnapshotMigration.Result> listener) {
        MigrationSource source = new MigrationSource() {
            @Override
            public @NonNull String id() { return "husksync"; }
            @Override
            public void read(@NonNull Sink sink) throws Exception { reader.read(sink); }
        };
        return new SnapshotMigration(this.files(), this.codec, this.importer(), "migration-server").migrate("migration.zip", source, 12345, listener);
    }

    private SnapshotFiles files() {
        return new SnapshotFiles(this.directory, this.codec, new SnapshotFileTestLogger());
    }

    private SnapshotDump importer() {
        StorageProvider storage = (StorageProvider) Proxy.newProxyInstance(StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (proxy, method, args) -> {
            this.writes++;
            return switch (method.getName()) {
                case "importSnapshot" -> this.save.apply((Snapshot) args[0]);
                case "importUser" -> {
                    StoredUser user = (StoredUser) args[0];
                    this.users.put(user.player(), user);
                    yield CompletableFuture.completedFuture(null);
                }
                default -> throw new AssertionError(method.getName());
            };
        });
        return new SnapshotDump(storage, this.files(), this.codec, map -> { throw new AssertionError("migration has no map records"); }, new NoopSnapshotCache());
    }

    private CompletableFuture<StorageProvider.SaveOutcome> store(Snapshot snapshot) {
        this.snapshots.put(snapshot.meta().id(), snapshot);
        return CompletableFuture.completedFuture(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.SAVED, null));
    }

    private void assertOnlyZip() throws IOException {
        try (var paths = Files.list(this.files().dump())) {
            assertEquals(List.of(this.files().dump().resolve("migration.zip")), paths.toList());
        }
    }

    private static MigrationSource.PlayerData data(int value) {
        return new MigrationSource.PlayerData(id(value), new StoredUser(id(value), "Player" + value, 500 + value), 1000L + value, SnapshotFixtures.snapshot().allData());
    }

    private static UUID id(int value) { return new UUID(0, value); }

    private interface Reader {
        void read(MigrationSource.Sink sink) throws Exception;
    }
}
