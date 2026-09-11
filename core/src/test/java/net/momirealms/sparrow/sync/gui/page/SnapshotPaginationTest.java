package net.momirealms.sparrow.sync.gui.page;

import net.momirealms.sparrow.sync.gui.page.SnapshotPage;
import net.momirealms.sparrow.sync.gui.page.SnapshotPageSession;
import net.momirealms.sparrow.sync.gui.page.SnapshotPagination;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotPaginationTest {
    private final UUID player = UUID.randomUUID();
    private final SnapshotQuery query = SnapshotQuery.of(this.player);
    private final List<SnapshotMeta> stored = new ArrayList<>();
    private final List<SnapshotQuery> reads = new ArrayList<>();
    private int counts;
    private Function<SnapshotQuery, CompletableFuture<List<SnapshotMeta>>> reader = request -> CompletableFuture.completedFuture(this.slice(request));
    private final SnapshotPagination pagination = new SnapshotPagination((StorageProvider) Proxy.newProxyInstance(
            StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (instance, method, args) -> {
                return switch (method.getName()) {
                    case "countSnapshots" -> {
                        this.counts++;
                        yield CompletableFuture.completedFuture((long) this.stored.size());
                    }
                    case "listSnapshots" -> {
                        SnapshotQuery request = (SnapshotQuery) args[0];
                        this.reads.add(request);
                        yield this.reader.apply(request);
                    }
                    default -> throw new AssertionError("Unexpected storage operation: " + method.getName());
                };
            }));

    @Test
    void textQueriesOnlyTheRequestedPageAndDoesNotKeepACache() {
        this.populate(18);
        SnapshotPage page = this.pagination.load(this.query, 1, SnapshotPagination.TEXT_PAGE_SIZE).join();
        assertEquals(1, page.index());
        assertEquals(3, page.count());
        assertEquals(18, page.total());
        assertEquals(this.stored.subList(7, 14), page.content());
        assertEquals(List.of(this.query.withOffset(7).withLimit(7)), this.reads);
        assertTrue(page.hasPrevious());
        assertTrue(page.hasNext());
        assertThrows(UnsupportedOperationException.class, () -> page.content().clear());
        this.pagination.load(this.query, 1, SnapshotPagination.TEXT_PAGE_SIZE).join();
        assertEquals(2, this.counts);
        assertEquals(2, this.reads.size());
    }

    @Test
    void emptyHistoryHasOneEmptyPageAndNoPayloadQuery() {
        SnapshotPage page = this.pagination.load(this.query, Integer.MAX_VALUE, 5).join();
        assertEquals(0, page.index());
        assertEquals(1, page.count());
        assertEquals(0, page.total());
        assertTrue(page.content().isEmpty());
        assertFalse(page.hasPrevious());
        assertFalse(page.hasNext());
        assertTrue(this.reads.isEmpty());
    }

    @Test
    void invalidIndicesClampBeforeOffsetCalculationAndLastPageCanBeShort() {
        this.populate(12);
        SnapshotPage first = this.pagination.load(this.query, Integer.MIN_VALUE, 5).join();
        SnapshotPage last = this.pagination.load(this.query, Integer.MAX_VALUE, 5).join();
        assertEquals(0, first.index());
        assertEquals(2, last.index());
        assertEquals(this.stored.subList(10, 12), last.content());
        assertFalse(last.hasNext());
        assertThrows(IllegalArgumentException.class, () -> this.pagination.load(this.query, 0, 0));
    }

    @Test
    void pageBoundariesReplaceLimitAndOffsetWhileKeepingFilters() {
        this.populate(12);
        SnapshotQuery filtered = this.query.between(10, 20).withPinned(SnapshotQuery.PinFilter.PINNED).withLimit(1).withOffset(100);
        this.pagination.load(filtered, 1, 5).join();
        assertEquals(filtered.withLimit(5).withOffset(5), this.reads.getFirst());
    }

    @Test
    void deletionBetweenCountAndReadMovesToTheNewLastPage() {
        this.populate(6);
        this.reader = request -> {
            if (this.reads.size() == 1) {
                this.stored.removeLast();
            }
            return CompletableFuture.completedFuture(this.slice(request));
        };
        SnapshotPage page = this.pagination.load(this.query, 1, 5).join();
        assertEquals(0, page.index());
        assertEquals(5, page.total());
        assertEquals(this.stored, page.content());
        assertEquals(List.of(5, 0), this.reads.stream().map(SnapshotQuery::offset).toList());
    }

    @Test
    void continuousDeletesHaveABoundedRetry() {
        this.populate(6);
        this.reader = request -> CompletableFuture.completedFuture(List.of());
        SnapshotPage page = this.pagination.load(this.query, 1, 5).join();
        assertTrue(page.content().isEmpty());
        assertEquals(2, this.counts);
        assertEquals(2, this.reads.size());
    }

    @Test
    void insertsCanReorderThePageAndNextRequestRefreshesTheCount() {
        this.populate(5);
        SnapshotMeta inserted = this.meta(100);
        this.reader = request -> {
            if (this.reads.size() == 1) {
                this.stored.addFirst(inserted);
            }
            return CompletableFuture.completedFuture(this.slice(request));
        };
        SnapshotPage first = this.pagination.load(this.query, 0, 5).join();
        assertEquals(5, first.total());
        assertEquals(inserted, first.content().getFirst());
        assertEquals(5, first.content().size());
        assertEquals(6, this.pagination.load(this.query, 0, 5).join().total());
    }

    @Test
    void uiCachesVisitedPagesAndRefreshOnlyReloadsTheCurrentPage() {
        this.populate(60);
        try (SnapshotPageSession session = this.pagination.openUi(this.query)) {
            assertEquals(27, session.state().get().page().content().size());
            session.advance(1).join();
            session.load(0).join();
            assertEquals(List.of(0, 27), this.reads.stream().map(SnapshotQuery::offset).toList());
            session.refresh().join();
            assertEquals(List.of(0, 27, 0), this.reads.stream().map(SnapshotQuery::offset).toList());
            session.load(1).join();
            assertEquals(List.of(0, 27, 0, 27), this.reads.stream().map(SnapshotQuery::offset).toList());
        }
    }

    @Test
    void oldAsyncPagesCannotOverwriteNewerNavigationAndInFlightReadsAreShared() {
        this.populate(60);
        List<CompletableFuture<List<SnapshotMeta>>> pending = this.deferReads();
        try (SnapshotPageSession session = this.pagination.openUi(this.query)) {
            assertTrue(session.state().get().loading());
            CompletableFuture<SnapshotPage> second = session.load(1);
            assertSame(second, session.load(1));
            assertEquals(2, pending.size());
            pending.get(1).complete(List.copyOf(this.stored.subList(27, 54)));
            assertEquals(1, session.state().get().page().index());
            pending.get(0).complete(List.copyOf(this.stored.subList(0, 27)));
            assertEquals(1, session.state().get().page().index());
            assertFalse(session.state().get().loading());
        }
    }

    @Test
    void refreshRejectsAnOlderCompletionAndCloseReleasesTheDisplay() {
        this.populate(30);
        List<CompletableFuture<List<SnapshotMeta>>> pending = this.deferReads();
        SnapshotPageSession session = this.pagination.openUi(this.query);
        session.refresh();
        pending.get(0).complete(List.copyOf(this.stored.subList(0, 27)));
        assertTrue(session.state().get().loading());
        session.close();
        pending.get(1).complete(List.copyOf(this.stored.subList(0, 27)));
        assertNull(session.state().get().page());
        assertFalse(session.state().get().loading());
        assertThrows(IllegalStateException.class, () -> session.load(0));
    }

    @Test
    void uiRefreshClampsAnEntirelyDeletedLastPage() {
        this.populate(28);
        try (SnapshotPageSession session = this.pagination.openUi(this.query)) {
            session.load(1).join();
            this.stored.removeLast();
            SnapshotPage page = session.refresh().join();
            assertEquals(0, page.index());
            assertEquals(1, page.count());
            assertEquals(this.stored, session.state().get().page().content());
        }
    }

    @Test
    void storageFailureIsVisibleAndNextClickRetries() {
        this.populate(1);
        IllegalStateException failure = new IllegalStateException("database unavailable");
        this.reader = request -> CompletableFuture.failedFuture(failure);
        assertThrows(CompletionException.class, () -> this.pagination.load(this.query, 0, 5).join());
        try (SnapshotPageSession session = this.pagination.openUi(this.query)) {
            assertNotNull(session.state().get().failure());
            assertNull(session.state().get().page());
            assertFalse(session.state().get().loading());
            this.reader = request -> CompletableFuture.completedFuture(this.slice(request));
            session.load(0).join();
            assertNull(session.state().get().failure());
            assertEquals(1, session.state().get().page().content().size());
        }
    }

    private List<CompletableFuture<List<SnapshotMeta>>> deferReads() {
        List<CompletableFuture<List<SnapshotMeta>>> pending = new ArrayList<>();
        this.reader = request -> {
            CompletableFuture<List<SnapshotMeta>> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        };
        return pending;
    }

    private void populate(int count) {
        for (int i = count; i > 0; i--) {
            this.stored.add(this.meta(i));
        }
    }

    private SnapshotMeta meta(int timestamp) {
        return new SnapshotMeta(UUID.randomUUID(), this.player, timestamp, SaveCause.COMMAND, false, "lobby", 4440);
    }

    private List<SnapshotMeta> slice(SnapshotQuery request) {
        int start = Math.min(request.offset(), this.stored.size());
        return List.copyOf(this.stored.subList(start, Math.min(start + request.limit(), this.stored.size())));
    }
}
