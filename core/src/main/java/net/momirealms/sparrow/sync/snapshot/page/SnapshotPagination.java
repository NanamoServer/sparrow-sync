package net.momirealms.sparrow.sync.snapshot.page;

import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public final class SnapshotPagination {
    public static final int UI_PAGE_SIZE = 27;
    public static final int TEXT_PAGE_SIZE = 5;

    private final StorageProvider storage;

    public SnapshotPagination(@NotNull StorageProvider storage) {
        this.storage = storage;
    }

    // 页码从 0 开始, 文本命令将用户输入减 1 后传入. 每次调用重新读取总数与当前页头.
    @NotNull
    public CompletableFuture<SnapshotPage> load(@NotNull SnapshotQuery query, int index, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("snapshot page size must be positive: " + size);
        }
        return this.load(query, index, size, true);
    }

    // 先用总数夹住目标页, 再向数据库请求这一页, query 原有的 offset / limit 由页边界替换.
    private CompletableFuture<SnapshotPage> load(SnapshotQuery query, int index, int size, boolean retryEmpty) {
        return this.storage.countSnapshots(query).thenCompose(total -> {
            int actualIndex = Math.clamp(index, 0, SnapshotPage.count(total, size) - 1);
            if (total == 0) {
                return CompletableFuture.completedFuture(new SnapshotPage(0, size, 0, List.of()));
            }
            SnapshotQuery pageQuery = query.withLimit(size).withOffset(Math.multiplyExact(actualIndex, size));
            return this.storage.listSnapshots(pageQuery).thenCompose(content -> {
                // 计数之后发生删除时重查一次, 将已消失的尾页夹回新的末页. 持续并发变化交给下次刷新.
                if (content.isEmpty() && retryEmpty) {
                    return this.load(query, actualIndex, size, false);
                }
                return CompletableFuture.completedFuture(new SnapshotPage(actualIndex, size, total, content));
            });
        });
    }

    @NotNull
    public SnapshotPageSession openUi(@NotNull SnapshotQuery query) {
        return new SnapshotPageSession(this, query);
    }
}
