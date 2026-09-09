package net.momirealms.sparrow.sync.snapshot.local;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionHeader;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/** 验证合并后的异常快照文件查询和门面异步调度, 异常快照数据损坏时仍可读取异常快照头文件. */
class SnapshotArchiveQueryTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();

    @BeforeAll
    static void initialize() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @Test
    void listsOnlyHeadersAndFiltersPlayerCategoryWithStablePagination() throws Exception {
        SnapshotFiles files = this.files();
        for (int i = 0; i < 12; i++) {
            this.write("malformed/" + i + ".snapshot", this.player, i);
        }
        this.write("oversized/other-category.snapshot", this.player, 100);
        this.write("malformed/other-player.snapshot", UUID.randomUUID(), 200);
        var page = files.listExceptions(this.player, "malformed", 1, 5);
        assertEquals(12, page.total());
        assertEquals(3, page.count());
        assertEquals(1, page.index());
        assertEquals(List.of(6L, 5L, 4L, 3L, 2L), page.content().stream().map(SnapshotFiles.ExceptionEntry::timestamp).toList());
        assertTrue(page.content().stream().allMatch(entry -> entry.bodyPresent() && entry.informationAvailable()));
        assertEquals(2, files.listExceptions(this.player, "malformed", Integer.MAX_VALUE, 5).content().size());
        assertEquals(0, files.listExceptions(this.player, "malformed", -1, 5).index());
        assertEquals(14, files.listExceptions(null, null, 0, 27).total());
    }

    @Test
    void legacyAndUnreadableHeadersRemainVisibleWithoutGuessedOwnership() throws Exception {
        SnapshotFiles files = this.files();
        Path old = files.exceptions().resolve("corrupted/Steve-" + this.player + ".snapshot");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "unreadable body");
        Path bad = this.write("corrupted/bad.json", this.player, 1);
        Files.writeString(ExceptionHeader.path(bad), "bad head");
        var entries = files.listExceptions(null, "corrupted", 0, 5).content();
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(entry -> entry.bodyPresent() && !entry.informationAvailable()));
        assertTrue(entries.stream().anyMatch(entry -> entry.headStatus() == SnapshotFiles.HeadStatus.MISSING));
        assertTrue(entries.stream().anyMatch(entry -> entry.headStatus() == SnapshotFiles.HeadStatus.UNREADABLE));
        assertEquals(0, files.listExceptions(this.player, null, 0, 5).total());
    }

    @Test
    void orphanHeaderIsVisibleAndDeletionRemovesTheResidualFile() throws Exception {
        SnapshotFiles files = this.files();
        Path body = this.write("oversized/orphan.snapshot", this.player, 3);
        Files.delete(body);
        var entry = files.listExceptions(this.player, null, 0, 5).content().getFirst();
        assertFalse(entry.bodyPresent());
        assertTrue(entry.informationAvailable());
        assertTrue(files.deleteException(entry.path()));
        assertFalse(Files.exists(ExceptionHeader.path(body)));
        assertEquals(0, files.listExceptions(null, null, 0, 5).total());
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
        Path original = this.write("malformed/move.snapshot", this.player, 1);
        Path moved = files.exceptions().resolve("corrupted/move.snapshot");
        Files.createDirectories(moved.getParent());
        Files.move(original, moved);
        assertFalse(files.exceptionEntry("malformed/move.snapshot").bodyPresent());
        assertTrue(files.exceptionEntry("corrupted/move.snapshot").bodyPresent());
        assertEquals(SnapshotFiles.HeadStatus.MISSING, files.exceptionEntry("corrupted/move.snapshot").headStatus());
        Files.move(ExceptionHeader.path(original), ExceptionHeader.path(moved));
        assertEquals(1, files.listExceptions(this.player, "corrupted", 0, 5).total());
        assertEquals(1, files.listExceptions(null, null, 0, 5).total());
    }

    @Test
    void scansOnTheExecutorAndRefreshesAfterDeletion() throws Exception {
        SnapshotFiles files = this.files();
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        SnapshotService service = this.service(files, jobs::addLast);
        var pending = service.listExceptions(null, null, 20, 5);
        assertFalse(pending.isDone());
        this.write("corrupted/first.snapshot", this.player, 1);
        Files.writeString(files.exceptions().resolve("ignored.tmp"), "partial");
        jobs.removeFirst().run();
        assertEquals(1, pending.join().total());
        files.deleteException("corrupted/first.snapshot");
        var refreshed = service.listExceptions(null, null, 20, 5);
        jobs.removeFirst().run();
        assertEquals(0, refreshed.join().total());
        assertEquals(1, refreshed.join().count());
        assertEquals(0, refreshed.join().index());
        assertThrows(IllegalArgumentException.class, () -> service.listExceptions(null, null, 0, 0));
    }

    /**
     * 将实际文件操作绑定到可控制的异步执行器, 观察目录查询的执行时机.
     *
     * @param files 本次测试的本地文件对象
     * @param executor 保存查询任务的执行器
     * @return 仅装配查询所需依赖的门面
     */
    private SnapshotService service(SnapshotFiles files, Executor executor) {
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        SchedulerAdapter<?> scheduler = (SchedulerAdapter<?>) Proxy.newProxyInstance(SchedulerAdapter.class.getClassLoader(), new Class<?>[]{SchedulerAdapter.class}, (proxy, method, args) -> {
            assertEquals("async", method.getName());
            return executor;
        });
        NmsPlayerFixture.set(SparrowSync.class, plugin, "scheduler", scheduler);
        SnapshotService service = new SnapshotService(plugin);
        NmsPlayerFixture.set(SnapshotService.class, service, "files", files);
        return service;
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
