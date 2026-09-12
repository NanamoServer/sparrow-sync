package net.momirealms.sparrow.sync.gui.page;

import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public final class SnapshotPageSession implements AutoCloseable {
    private final SnapshotPagination pagination;
    private final SnapshotQuery query;
    private final Map<Integer, CompletableFuture<SnapshotPage>> pages = new HashMap<>();
    private final MutableSignal<State> state = Signal.of(new State(0, null, null, false));
    private long request; // 只有最后一次翻页可以更新当前显示
    private int requestedIndex;
    private boolean closed;

    SnapshotPageSession(SnapshotPagination pagination, SnapshotQuery query) {
        this.pagination = pagination;
        this.query = query;
        this.load(0);
    }

    @NotNull
    public Signal<State> state() {
        return this.state;
    }

    // 复用已访问页及其在途请求, Future 完成后通过 Signal 发布当前页.
    @NotNull
    public synchronized CompletableFuture<SnapshotPage> load(int index) {
        if (this.closed) {
            throw new IllegalStateException("snapshot page session is closed");
        }
        int target = Math.max(0, index);
        this.requestedIndex = target;
        long currentRequest = ++this.request;
        this.state.set(new State(target, null, null, true));
        CompletableFuture<SnapshotPage> future = this.pages.computeIfAbsent(target,
                page -> this.pagination.load(this.query, page, SnapshotPagination.UI_PAGE_SIZE));
        future.whenComplete((page, failure) -> {
            synchronized (this) {
                if (failure != null) {
                    this.pages.remove(target, future);
                }
                if (this.closed || currentRequest != this.request) return;
                if (failure == null) {
                    this.requestedIndex = page.index();
                }
                this.state.set(new State(this.requestedIndex, page, failure, false));
            }
        });
        return future;
    }

    @NotNull
    public synchronized CompletableFuture<SnapshotPage> advance(int step) {
        return this.load((int) Math.clamp((long) this.requestedIndex + step, 0, Integer.MAX_VALUE));
    }

    @NotNull
    public synchronized CompletableFuture<SnapshotPage> refresh() {
        this.pages.clear();
        return this.load(this.requestedIndex);
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        ++this.request;
        this.pages.clear();
        this.state.set(new State(this.requestedIndex, null, null, false));
    }

    public record State(int requestedIndex, @Nullable SnapshotPage page, @Nullable Throwable failure, boolean loading) {
    }
}
