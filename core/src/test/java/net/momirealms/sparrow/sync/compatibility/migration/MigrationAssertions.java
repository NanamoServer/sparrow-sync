package net.momirealms.sparrow.sync.compatibility.migration;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotDump;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.storage.StorageProvider;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

public final class MigrationAssertions {
    private MigrationAssertions() {
    }

    public static Snapshot assertZipRoundTrip(Path directory, MigrationSource source, Map<DataKey, Tag> expected) throws Exception {
        BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.NONE);
        SnapshotFiles files = new SnapshotFiles(directory, codec);
        var imported = new ArrayList<Snapshot>();
        StorageProvider storage = (StorageProvider) Proxy.newProxyInstance(StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (proxy, method, args) -> switch (method.getName()) {
            case "importSnapshot" -> {
                imported.add((Snapshot) args[0]);
                yield CompletableFuture.completedFuture(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.SAVED, null));
            }
            case "importUser" -> CompletableFuture.completedFuture(null);
            default -> throw new AssertionError(method);
        });
        SnapshotDump dump = new SnapshotDump(storage, files, codec, map -> { throw new AssertionError("unexpected map"); });
        var result = new SnapshotMigration(files, codec, dump, "migration-server").migrate("migration.zip", source, 12345);
        assertNull(result.failure());
        assertEquals(1, result.converted());
        assertEquals(0, result.failed());
        assertNotNull(result.imported());
        assertNull(result.imported().failure());
        try (ZipFile zip = new ZipFile(result.file().toFile())) {
            assertNotNull(zip.getEntry("snapshots.bin"));
        }
        assertEquals(1, imported.size());
        Snapshot snapshot = imported.getFirst();
        assertEquals(expected, snapshot.data());
        imported.clear();
        assertNull(dump.importFile("migration.zip").failure());
        assertEquals(snapshot, imported.getFirst());
        return snapshot;
    }
}
