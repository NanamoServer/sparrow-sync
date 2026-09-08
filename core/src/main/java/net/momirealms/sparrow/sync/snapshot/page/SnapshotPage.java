package net.momirealms.sparrow.sync.snapshot.page;

import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@ApiStatus.Internal
public record SnapshotPage(int index, int size, long total, @NotNull List<SnapshotMeta> content) {
    public SnapshotPage {
        content = List.copyOf(content);
    }

    public int count() {
        return count(this.total, this.size);
    }

    public boolean hasPrevious() {
        return this.index > 0;
    }

    public boolean hasNext() {
        return this.index < this.count() - 1;
    }

    static int count(long total, int size) {
        return total == 0 ? 1 : Math.toIntExact((total - 1) / size + 1);
    }
}
