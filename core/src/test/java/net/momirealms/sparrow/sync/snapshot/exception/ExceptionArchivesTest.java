package net.momirealms.sparrow.sync.snapshot.exception;

import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionArchivesTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();

    @BeforeAll
    static void initialize() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @Test
    void listsOnlyHeadersAndFiltersPlayerCategoryWithStablePagination() throws Exception {
        SnapshotFiles files = this.files();
        ExceptionArchives archives = new ExceptionArchives(files, Runnable::run);
        for (int i = 0; i < 12; i++) {
            this.write("malformed/" + i + ".snapshot", this.player, i);
        }
        this.write("oversized/other-category.snapshot", this.player, 100);
        this.write("malformed/other-player.snapshot", UUID.randomUUID(), 200);
        var page = archives.load(this.player, "malformed", 1, 5).join();
        assertEquals(12, page.total());
        assertEquals(3, page.count());
        assertEquals(1, page.index());
        assertEquals(List.of(6L, 5L, 4L, 3L, 2L), page.content().stream().map(ExceptionArchives.Entry::timestamp).toList());
        assertTrue(page.content().stream().allMatch(entry -> entry.bodyPresent() && entry.informationAvailable()));
        assertEquals(2, archives.load(this.player, "malformed", Integer.MAX_VALUE, 5).join().content().size());
        assertEquals(0, archives.load(this.player, "malformed", -1, 5).join().index());
        assertEquals(14, archives.load(null, null, 0, 27).join().total());
    }

    @Test
    void legacyAndUnreadableHeadersRemainVisibleWithoutGuessedOwnership() throws Exception {
        SnapshotFiles files = this.files();
        ExceptionArchives archives = new ExceptionArchives(files, Runnable::run);
        Path old = files.exceptions().resolve("corrupted/Steve-" + this.player + ".snapshot");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "unreadable body");
        Path bad = this.write("corrupted/bad.json", this.player, 1);
        Files.writeString(ExceptionHeader.path(bad), "bad head");
        var entries = archives.load(null, "corrupted", 0, 5).join().content();
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(entry -> entry.bodyPresent() && !entry.informationAvailable()));
        assertTrue(entries.stream().anyMatch(entry -> entry.headStatus() == ExceptionArchives.HeadStatus.MISSING));
        assertTrue(entries.stream().anyMatch(entry -> entry.headStatus() == ExceptionArchives.HeadStatus.UNREADABLE));
        assertEquals(0, archives.load(this.player, null, 0, 5).join().total());
    }

    @Test
    void orphanHeaderIsVisibleAndDeletionRemovesTheResidualFile() throws Exception {
        SnapshotFiles files = this.files();
        ExceptionArchives archives = new ExceptionArchives(files, Runnable::run);
        Path body = this.write("oversized/orphan.snapshot", this.player, 3);
        Files.delete(body);
        var entry = archives.load(this.player, null, 0, 5).join().content().getFirst();
        assertFalse(entry.bodyPresent());
        assertTrue(entry.informationAvailable());
        assertTrue(files.deleteException(entry.path()));
        assertFalse(Files.exists(ExceptionHeader.path(body)));
        assertEquals(0, archives.load(null, null, 0, 5).join().total());
    }

    @Test
    void deletionCleansBothFilesWithoutParsingEither() throws Exception {
        SnapshotFiles files = this.files();
        Path body = this.write("malformed/broken.snapshot", this.player, 1);
        Files.writeString(ExceptionHeader.path(body), "corrupt header");
        assertTrue(files.deleteException("malformed/broken.snapshot"));
        assertFalse(Files.exists(body));
        assertFalse(Files.exists(ExceptionHeader.path(body)));
        assertFalse(files.deleteException("malformed/broken.snapshot"));
        assertThrows(IOException.class, () -> files.deleteException("../../outside.snapshot"));
        assertThrows(IOException.class, () -> files.readException("../../outside.snapshot"));
    }

    @Test
    void movingOnlyTheBodyDoesNotTurnIndexDamageIntoBodyLoss() throws Exception {
        SnapshotFiles files = this.files();
        ExceptionArchives archives = new ExceptionArchives(files, Runnable::run);
        Path original = this.write("malformed/move.snapshot", this.player, 1);
        Path moved = files.exceptions().resolve("corrupted/move.snapshot");
        Files.createDirectories(moved.getParent());
        Files.move(original, moved);
        assertFalse(archives.entry("malformed/move.snapshot").bodyPresent());
        assertTrue(archives.entry("corrupted/move.snapshot").bodyPresent());
        assertEquals(ExceptionArchives.HeadStatus.MISSING, archives.entry("corrupted/move.snapshot").headStatus());
        Files.move(ExceptionHeader.path(original), ExceptionHeader.path(moved));
        assertEquals(1, archives.load(this.player, "corrupted", 0, 5).join().total());
        assertEquals(1, archives.load(null, null, 0, 5).join().total());
    }

    @Test
    void scansOnTheExecutorAndRefreshesAfterDeletion() throws Exception {
        SnapshotFiles files = this.files();
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        ExceptionArchives archives = new ExceptionArchives(files, jobs::addLast);
        var pending = archives.load(null, null, 20, 5);
        assertFalse(pending.isDone());
        this.write("corrupted/first.snapshot", this.player, 1);
        Files.writeString(files.exceptions().resolve("ignored.tmp"), "partial");
        jobs.removeFirst().run();
        assertEquals(1, pending.join().total());
        files.deleteException("corrupted/first.snapshot");
        var refreshed = archives.load(null, null, 20, 5);
        jobs.removeFirst().run();
        assertEquals(0, refreshed.join().total());
        assertEquals(1, refreshed.join().count());
        assertEquals(0, refreshed.join().index());
        assertThrows(IllegalArgumentException.class, () -> archives.load(null, null, 0, 0));
    }

    private SnapshotFiles files() {
        return new SnapshotFiles(this.directory, new BinarySnapshotCodec(CompressorRegistry.NONE));
    }

    private Path write(String path, UUID player, long timestamp) throws IOException {
        Path body = this.directory.resolve("exception").resolve(path);
        Files.createDirectories(body.getParent());
        Files.writeString(body, "deliberately invalid body");
        SnapshotMeta meta = new SnapshotMeta(UUID.randomUUID(), player, timestamp, SaveCause.COMMAND, true, "lobby", 0);
        new ExceptionHeader(meta, "Steve").write(body);
        return body;
    }
}
