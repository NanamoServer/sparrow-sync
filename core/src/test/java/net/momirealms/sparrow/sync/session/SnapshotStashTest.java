package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotStashTest {
    private static final UUID PLAYER = UUID.fromString("7f2b3c1d-0a9e-4b8c-9d6f-112233445566");
    private static final DataKey HEALTH = DataKey.of("sparrow", "health");

    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE);

    @TempDir
    Path dataFolder;
    private SnapshotStash stash;

    @BeforeEach
    void setUp() {
        stash = new SnapshotStash(dataFolder, codec, new SyncLogger(new QuietLogger()));
    }

    @Test
    void retriableFailureIsStashedIntoPendingAndSurvivesRoundTrip() throws IOException {
        Snapshot snapshot = snapshotAt(1_756_300_000_000L);

        stash.stash(snapshot, "Steve", StorageProvider.SaveResult.RETRY_LATER);

        List<Path> files = listFiles(dataFolder.resolve("pending"));
        assertEquals(1, files.size());
        DecodedSnapshot decoded = codec.decode(Files.readAllBytes(files.get(0)));
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot();
        assertEquals(snapshot.meta(), restored.meta());
        assertEquals(snapshot.data(), restored.data());
    }

    @Test
    void rejectionsAreStashedIntoTheirExceptionDirectories() throws IOException {
        stash.stash(snapshotAt(1_756_300_000_000L), "Steve", StorageProvider.SaveResult.REJECTED_OVERSIZED);
        stash.stash(snapshotAt(1_756_300_000_001L), "Steve", StorageProvider.SaveResult.REJECTED_MALFORMED);

        assertEquals(1, listFiles(dataFolder.resolve("exception").resolve("oversized")).size());
        assertEquals(1, listFiles(dataFolder.resolve("exception").resolve("malformed")).size());
        assertTrue(listFiles(dataFolder.resolve("pending")).isEmpty());
    }

    @Test
    void fileNameCarriesSanitizedNameUuidCauseAndTime() throws IOException {
        stash.stash(snapshotAt(1_756_300_000_000L), "Bad/Na:me*", StorageProvider.SaveResult.RETRY_LATER);

        String name = listFiles(dataFolder.resolve("pending")).get(0).getFileName().toString();
        assertTrue(name.startsWith("Bad_Na_me_-" + PLAYER + "-DISCONNECT-"), "unexpected file name: " + name);
        assertTrue(name.endsWith(".snapshot"));
    }

    @Test
    void restoredSnapshotIsDeletedAfterSuccessfulSave() throws IOException {
        Snapshot snapshot = snapshotAt(1_756_300_000_000L);
        stash.stash(snapshot, "Steve", StorageProvider.SaveResult.RETRY_LATER);
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.SAVED_OUT_OF_ORDER);

        stash.restorePending(storage);

        assertEquals(List.of(snapshot.meta().id()), storage.savedIds());
        assertTrue(listFiles(dataFolder.resolve("pending")).isEmpty());
    }

    @Test
    void duplicateOnRestoreStillDeletesTheFile() throws IOException {
        // 上次其实写进去了只是没等到回执, 幂等重放后文件同样功成身退
        stash.stash(snapshotAt(1_756_300_000_000L), "Steve", StorageProvider.SaveResult.RETRY_LATER);
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.DUPLICATE);

        stash.restorePending(storage);

        assertTrue(listFiles(dataFolder.resolve("pending")).isEmpty());
    }

    @Test
    void restoreStopsWhenStorageIsStillUnavailable() throws IOException {
        stash.stash(snapshotAt(1_756_300_000_000L), "Steve", StorageProvider.SaveResult.RETRY_LATER);
        stash.stash(snapshotAt(1_756_300_000_001L), "Steve", StorageProvider.SaveResult.RETRY_LATER);
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.RETRY_LATER);

        stash.restorePending(storage);

        // 第一份就撞上数据库不可用, 本轮收工, 两份文件原样留给下次启动
        assertEquals(1, storage.savedIds().size());
        assertEquals(2, listFiles(dataFolder.resolve("pending")).size());
    }

    @Test
    void rejectedRestoreMovesFileToExceptionDirectory() throws IOException {
        stash.stash(snapshotAt(1_756_300_000_000L), "Steve", StorageProvider.SaveResult.RETRY_LATER);
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.REJECTED_OVERSIZED);

        stash.restorePending(storage);

        assertTrue(listFiles(dataFolder.resolve("pending")).isEmpty());
        assertEquals(1, listFiles(dataFolder.resolve("exception").resolve("oversized")).size());
    }

    @Test
    void corruptedFileIsMovedAsideWithoutTouchingStorage() throws IOException {
        Path pending = dataFolder.resolve("pending");
        Files.createDirectories(pending);
        Files.write(pending.resolve("Steve-corrupted.snapshot"), new byte[]{1, 2, 3, 4});
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.SAVED);

        stash.restorePending(storage);

        assertTrue(storage.savedIds().isEmpty());
        assertTrue(listFiles(pending).isEmpty());
        assertEquals(1, listFiles(dataFolder.resolve("exception").resolve("corrupted")).size());
    }

    @Test
    void leftoverTmpFilesAreCleanedUpOnRestore() throws IOException {
        Path pending = dataFolder.resolve("pending");
        Files.createDirectories(pending);
        Files.write(pending.resolve("Steve-half-written.snapshot.tmp"), new byte[]{1, 2, 3});
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.SAVED);

        stash.restorePending(storage);

        assertTrue(storage.savedIds().isEmpty());
        assertTrue(listFiles(pending).isEmpty());
    }

    @Test
    void samePlayerSnapshotsRestoreInCaptureOrder() throws IOException {
        Snapshot earlier = snapshotAt(1_756_300_000_000L);
        Snapshot later = snapshotAt(1_756_300_999_000L);
        // 故意倒序落盘, 插回顺序应由文件名里的逻辑时间戳决定
        stash.stash(later, "Steve", StorageProvider.SaveResult.RETRY_LATER);
        stash.stash(earlier, "Steve", StorageProvider.SaveResult.RETRY_LATER);
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.SAVED_OUT_OF_ORDER);

        stash.restorePending(storage);

        assertEquals(List.of(earlier.meta().id(), later.meta().id()), storage.savedIds());
    }

    private static Snapshot snapshotAt(long timestamp) {
        SnapshotMeta meta = SnapshotMeta.builder()
                .player(PLAYER)
                .timestamp(timestamp)
                .cause(SaveCause.DISCONNECT)
                .server("lobby-1")
                .mcDataVersion(4189)
                .build();
        CompoundTag health = NBT.createCompound();
        health.putDouble("value", 19.5);
        return new Snapshot(meta, Map.of(HEALTH, health));
    }

    private static List<Path> listFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.toList();
        }
    }

    // 只记录 saveSnapshot 的桩存储, 结果按脚本出队, 队列耗尽后重复最后一个
    private static final class RecordingStorage implements StorageProvider {
        private final Queue<SaveResult> script = new ArrayDeque<>();
        private final List<UUID> savedIds = new ArrayList<>();
        private final SaveResult fallback;

        private RecordingStorage(SaveResult... results) {
            for (SaveResult result : results) this.script.add(result);
            this.fallback = results[results.length - 1];
        }

        List<UUID> savedIds() {
            return this.savedIds;
        }

        @Override
        public CompletableFuture<SaveResult> saveSnapshot(Snapshot snapshot) {
            this.savedIds.add(snapshot.meta().id());
            SaveResult next = this.script.poll();
            return CompletableFuture.completedFuture(next != null ? next : this.fallback);
        }

        @Override
        public void initialize() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public CompletableFuture<Optional<Snapshot>> latestSnapshot(UUID player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Optional<Snapshot>> snapshot(UUID snapshotId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<List<SnapshotMeta>> listSnapshots(SnapshotQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Integer> rotate(UUID player, int maxUnpinned) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Boolean> setPinned(UUID snapshotId, boolean pinned) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Boolean> deleteSnapshot(UUID snapshotId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> ensureUser(UUID player, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Optional<UUID>> lookupUser(String name) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class QuietLogger implements PluginLogger {
        @Override
        public void info(String s) {
        }

        @Override
        public void warn(String s) {
        }

        @Override
        public void warn(String s, Throwable t) {
        }

        @Override
        public void error(String s) {
        }

        @Override
        public void error(String s, Throwable t) {
        }
    }
}
