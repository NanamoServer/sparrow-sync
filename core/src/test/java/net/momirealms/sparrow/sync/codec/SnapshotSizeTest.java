package net.momirealms.sparrow.sync.codec;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotStash;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.storage.mongo.MongoStorageProvider;
import net.momirealms.sparrow.sync.storage.mysql.MysqlStorageProvider;
import net.momirealms.sparrow.sync.storage.postgresql.PostgresStorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotSizeTest {
    private static final int LARGE_PAYLOAD_SIZE = 17 * 1024 * 1024;
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(CompressorRegistry.class)
    void snapshotLargerThanSixteenMiBRoundTrips(CompressorRegistry compressor) throws IOException {
        BinarySnapshotCodec codec = new BinarySnapshotCodec(compressor);
        Snapshot snapshot = oversized();
        byte[] encoded = codec.encode(snapshot);
        if (compressor == CompressorRegistry.NONE) {
            assertTrue(encoded.length > LARGE_PAYLOAD_SIZE);
        } else {
            assertTrue(encoded.length < 15 * 1024 * 1024);
        }
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(encoded)).snapshot());
    }

    @Test
    void everyBackendRejectsOversizedSaveAndImportBeforeAccessingDatabase() {
        RecordingLogger recorded = new RecordingLogger();
        SyncLogger logger = new SyncLogger(recorded);
        PlayerSerialExecutor serial = new PlayerSerialExecutor(logger, 1);
        BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.NONE);
        // 不初始化连接池, 任何误入数据库的分支都会使本测试失败.
        List<StorageProvider> providers = List.of(
                new MysqlStorageProvider(new PluginConfig.MysqlOptions(), codec, serial, Runnable::run, logger),
                new PostgresStorageProvider(new PluginConfig.PostgresOptions(), codec, serial, Runnable::run, logger),
                new MongoStorageProvider(new PluginConfig.MongoOptions(), new DocumentSnapshotCodec(codec), serial, Runnable::run, logger));
        try {
            Snapshot snapshot = oversized();
            for (StorageProvider provider : providers) {
                assertEquals(SaveResult.REJECTED_OVERSIZED, provider.saveSnapshotOutcome(snapshot).join().result());
                StorageProvider.SaveOutcome imported = provider.importSnapshot(snapshot).join();
                assertEquals(SaveResult.REJECTED_OVERSIZED, imported.result());
                assertInstanceOf(IOException.class, imported.failure());
            }
            assertTrue(recorded.failures.isEmpty());
        } finally {
            assertEquals(0, serial.shutdown(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(CompressorRegistry.class)
    void largeStashRemainsReadableBinaryForBothFailureCategories(CompressorRegistry compressor) throws IOException {
        BinarySnapshotCodec codec = new BinarySnapshotCodec(compressor);
        RecordingLogger logger = new RecordingLogger();
        Snapshot snapshot = oversized();
        SnapshotStash stash = new SnapshotStash(this.directory, codec, new SyncLogger(logger));
        stash.stash(snapshot, "Steve", SaveResult.REJECTED_OVERSIZED);
        SnapshotFiles files = new SnapshotFiles(this.directory, codec);
        List<SnapshotFiles.ExceptionEntry> entries = files.listExceptions(snapshot.meta().player(), "oversized", 0, 10).content();
        assertEquals(1, entries.size());
        SnapshotFiles.ExceptionEntry entry = entries.getFirst();
        assertTrue(entry.path().endsWith(".snapshot"));
        assertEquals(snapshot.meta(), entry.header().meta());
        assertEquals("Steve", entry.header().playerName());
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, files.readException(entry.path())).snapshot());
        assertTrue(files.pendingEntries().isEmpty());
        stash.stash(snapshot, "Steve", SaveResult.RETRY_LATER);
        Path pending = files.pendingEntries().stream().filter(path -> path.toString().endsWith(".snapshot")).findFirst().orElseThrow();
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, files.readPending(pending)).snapshot());
        assertTrue(logger.failures.isEmpty());
        try (var paths = Files.walk(this.directory)) {
            assertFalse(paths.anyMatch(path -> path.toString().endsWith(".tmp") || path.toString().endsWith(".json")));
        }
    }

    private static Snapshot oversized() {
        return new Snapshot(SnapshotFixtures.meta(), Map.of(SnapshotFixtures.UNKNOWN_DOC, NBT.createByteArray(new byte[LARGE_PAYLOAD_SIZE])));
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<Throwable> failures = new ArrayList<>();

        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void warn(String message, Throwable failure) { this.failures.add(failure); }

        @Override
        public void error(String message) {}

        @Override
        public void error(String message, Throwable failure) { this.failures.add(failure); }
    }
}
