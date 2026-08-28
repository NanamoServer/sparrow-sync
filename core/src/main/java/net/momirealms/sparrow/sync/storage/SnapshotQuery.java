package net.momirealms.sparrow.sync.storage;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 快照的查询条件.
 *
 * @param player  目标玩家, 唯一必填项
 * @param from    采集时刻下界, 含; {@link #UNBOUNDED_FROM} 表示不限
 * @param to      采集时刻上界, 含; {@link #UNBOUNDED_TO} 表示不限
 * @param pinned  固定状态筛选
 * @param limit   最多返回几条; {@link #NO_LIMIT} 表示不限
 */
public record SnapshotQuery(@NotNull UUID player, long from, long to, @NotNull PinFilter pinned, int limit) {
    public static final long UNBOUNDED_FROM = Long.MIN_VALUE;
    public static final long UNBOUNDED_TO = Long.MAX_VALUE;
    public static final int NO_LIMIT = 0;

    public SnapshotQuery {
        if (from > to) {
            throw new IllegalArgumentException("snapshot query lower bound " + from + " is above upper bound " + to);
        }
    }

    @NotNull
    public static SnapshotQuery of(@NotNull UUID player) {
        return new SnapshotQuery(player, UNBOUNDED_FROM, UNBOUNDED_TO, PinFilter.ANY, NO_LIMIT);
    }

    @NotNull
    public SnapshotQuery between(long from, long to) {
        return new SnapshotQuery(this.player, from, to, this.pinned, this.limit);
    }

    @NotNull
    public SnapshotQuery withPinned(@NotNull PinFilter pinned) {
        return new SnapshotQuery(this.player, this.from, this.to, pinned, this.limit);
    }

    @NotNull
    public SnapshotQuery withLimit(int limit) {
        return new SnapshotQuery(this.player, this.from, this.to, this.pinned, Math.max(limit, NO_LIMIT));
    }

    public enum PinFilter {
        ANY,
        PINNED,
        UNPINNED
    }
}
