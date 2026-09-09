package net.momirealms.sparrow.sync.snapshot.local;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.map.MapStorage;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionHeader;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StoredUser;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        List<Path> files = listFiles(dataFolder.resolve("snapshot/pending"));
        assertEquals(1, files.size());
        DecodedSnapshot decoded = codec.decode(Files.readAllBytes(files.get(0)));
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot();
        assertEquals(snapshot.meta(), restored.meta());
        assertEquals(snapshot.data(), restored.data());
        assertEquals(new ExceptionHeader(snapshot.meta(), "Steve"), ExceptionHeader.read(files.getFirst()));
    }

    @Test
    void rejectionsAreStashedIntoTheirExceptionDirectories() throws IOException {
        stash.stash(snapshotAt(1_756_300_000_000L), "Steve", StorageProvider.SaveResult.REJECTED_OVERSIZED);
        stash.stash(snapshotAt(1_756_300_000_001L), "Steve", StorageProvider.SaveResult.REJECTED_MALFORMED);

        assertEquals(1, listFiles(dataFolder.resolve("snapshot/exception").resolve("oversized")).size());
        assertEquals(1, listFiles(dataFolder.resolve("snapshot/exception").resolve("malformed")).size());
        assertTrue(listFiles(dataFolder.resolve("snapshot/pending")).isEmpty());
    }

    @Test
    void fileNameCarriesSanitizedNameUuidCauseAndTime() throws IOException {
        stash.stash(snapshotAt(1_756_300_000_000L), "Bad/Na:me*", StorageProvider.SaveResult.RETRY_LATER);

        String name = listFiles(dataFolder.resolve("snapshot/pending")).get(0).getFileName().toString();
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
        assertTrue(listFiles(dataFolder.resolve("snapshot/pending")).isEmpty());
        try (Stream<Path> remaining = Files.list(dataFolder.resolve("snapshot/pending"))) {
            assertEquals(0, remaining.count());
        }
    }

    @Test
    void duplicateOnRestoreStillDeletesTheFile() throws IOException {
        // 上次写入数据库后未收到回执, 再次插入得到幂等结果后同样删除本地快照文件
        stash.stash(snapshotAt(1_756_300_000_000L), "Steve", StorageProvider.SaveResult.RETRY_LATER);
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.DUPLICATE);

        stash.restorePending(storage);

        assertTrue(listFiles(dataFolder.resolve("snapshot/pending")).isEmpty());
    }

    @Test
    void restoreStopsWhenStorageIsStillUnavailable() throws IOException {
        stash.stash(snapshotAt(1_756_300_000_000L), "Steve", StorageProvider.SaveResult.RETRY_LATER);
        stash.stash(snapshotAt(1_756_300_000_001L), "Steve", StorageProvider.SaveResult.RETRY_LATER);
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.RETRY_LATER);

        stash.restorePending(storage);

        // 第一份就撞上数据库不可用, 本轮收工, 两份文件原样留给下次启动
        assertEquals(1, storage.savedIds().size());
        assertEquals(2, listFiles(dataFolder.resolve("snapshot/pending")).size());
    }

    @Test
    void rejectedRestoreMovesFileToExceptionDirectory() throws IOException {
        Snapshot snapshot = snapshotAt(1_756_300_000_000L);
        stash.stash(snapshot, "Steve", StorageProvider.SaveResult.RETRY_LATER);
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.REJECTED_OVERSIZED);

        stash.restorePending(storage);

        assertTrue(listFiles(dataFolder.resolve("snapshot/pending")).isEmpty());
        assertEquals(1, listFiles(dataFolder.resolve("snapshot/exception").resolve("oversized")).size());
        Path body = listFiles(dataFolder.resolve("snapshot/exception").resolve("oversized")).getFirst();
        assertEquals(new ExceptionHeader(snapshot.meta(), "Steve"), ExceptionHeader.read(body));
        try (Stream<Path> remaining = Files.list(dataFolder.resolve("snapshot/pending"))) {
            assertEquals(0, remaining.count());
        }
    }

    /**
     * 通过共享文件对象把失败留存、启动拒绝、异常快照查询、快照数据读取及双侧删除连成一次流程.
     *
     * @throws IOException 本地文件操作失败
     */
    @Test
    void sharedFilesExposeRejectedReplayForInspectionAndDeletion() throws IOException {
        SnapshotFiles files = new SnapshotFiles(this.dataFolder, this.codec);
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", new SyncLogger(new QuietLogger()));
        SnapshotStash stash = new SnapshotStash(plugin);
        stash.onLoad(files);
        Snapshot snapshot = snapshotAt(1_756_300_000_000L);

        stash.stash(snapshot, "Steve", StorageProvider.SaveResult.RETRY_LATER);
        assertEquals(2, files.pendingEntries().size());
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.REJECTED_OVERSIZED);
        stash.restorePending(storage);

        assertEquals(List.of(snapshot.meta().id()), storage.savedIds());
        assertTrue(files.pendingEntries().isEmpty());
        SnapshotFiles.ExceptionPage page = files.listExceptions(PLAYER, "oversized", 0, 5);
        assertEquals(1, page.total());
        SnapshotFiles.ExceptionEntry entry = page.content().getFirst();
        assertEquals(new ExceptionHeader(snapshot.meta(), "Steve"), entry.header());
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, files.readException(entry.path())).snapshot());
        assertTrue(files.deleteException(entry.path()));
        assertTrue(files.listExceptions(null, null, 0, 5).content().isEmpty());
        assertTrue(listFiles(files.exceptions().resolve("oversized")).isEmpty());
    }

    @Test
    void corruptedFileIsMovedAsideWithoutTouchingStorage() throws IOException {
        Path pending = dataFolder.resolve("snapshot/pending");
        Files.createDirectories(pending);
        Files.write(pending.resolve("Steve-corrupted.snapshot"), new byte[]{1, 2, 3, 4});
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.SAVED);

        stash.restorePending(storage);

        assertTrue(storage.savedIds().isEmpty());
        assertTrue(listFiles(pending).isEmpty());
        assertEquals(1, listFiles(dataFolder.resolve("snapshot/exception").resolve("corrupted")).size());
        assertEquals(new ExceptionHeader(null, null), ExceptionHeader.read(listFiles(dataFolder.resolve("snapshot/exception/corrupted")).getFirst()));
    }

    @Test
    void corruptPendingBodyKeepsItsConfirmedHeaderWhenMoved() throws IOException {
        Snapshot snapshot = snapshotAt(1_756_300_000_000L);
        this.stash.stash(snapshot, "Steve", StorageProvider.SaveResult.RETRY_LATER);
        Path body = listFiles(this.dataFolder.resolve("snapshot/pending")).getFirst();
        Files.writeString(body, "damaged");
        RecordingStorage storage = new RecordingStorage(StorageProvider.SaveResult.SAVED);
        this.stash.restorePending(storage);
        Path moved = listFiles(this.dataFolder.resolve("snapshot/exception/corrupted")).getFirst();
        assertEquals(new ExceptionHeader(snapshot.meta(), "Steve"), ExceptionHeader.read(moved));
        assertTrue(storage.savedIds().isEmpty());
        try (Stream<Path> remaining = Files.list(this.dataFolder.resolve("snapshot/pending"))) {
            assertEquals(0, remaining.count());
        }
    }

    @Test
    void headerWriteFailureRetainsTheCompleteBody() throws IOException {
        Snapshot snapshot = snapshotAt(1_756_300_000_000L);
        Path body = this.dataFolder.resolve("snapshot/exception/malformed/failure.snapshot");
        Files.createDirectories(body.getParent());
        Files.write(body, this.codec.encode(snapshot));
        Files.createDirectory(ExceptionHeader.path(body));
        Files.writeString(ExceptionHeader.path(body).resolve("blocker"), "block header publication");
        assertThrows(IOException.class, () -> new ExceptionHeader(snapshot.meta(), "Steve").write(body));
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(Files.readAllBytes(body))).snapshot());
        try (Stream<Path> remaining = Files.list(body.getParent())) {
            assertTrue(remaining.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void leftoverTmpFilesAreCleanedUpOnRestore() throws IOException {
        Path pending = dataFolder.resolve("snapshot/pending");
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
            return entries.filter(path -> !path.getFileName().toString().endsWith(ExceptionHeader.SUFFIX)).toList();
        }
    }

    // 记录保存请求并返回完整结果, 结果按脚本出队, 队列耗尽后重复最后一个.
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
        public CompletableFuture<SaveOutcome> saveSnapshotOutcome(Snapshot snapshot) {
            this.savedIds.add(snapshot.meta().id());
            SaveResult next = this.script.poll();
            return CompletableFuture.completedFuture(new SaveOutcome(next != null ? next : this.fallback, null));
        }

        @Override
        public CompletableFuture<List<Snapshot>> scanSnapshots(long before, UUID after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<List<StoredUser>> scanUsers(UUID after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> importUser(StoredUser user) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<SaveOutcome> importSnapshot(Snapshot snapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void initialize() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        @NotNull
        public MapStorage maps() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull CompletableFuture<Optional<Snapshot>> latestSnapshot(UUID player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull CompletableFuture<Optional<Snapshot>> snapshot(UUID snapshotId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull CompletableFuture<List<SnapshotMeta>> listSnapshots(SnapshotQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Long> countSnapshots(SnapshotQuery query) {
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
