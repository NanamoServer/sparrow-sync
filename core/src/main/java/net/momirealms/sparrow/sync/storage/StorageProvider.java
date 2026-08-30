package net.momirealms.sparrow.sync.storage;

import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.SnapshotQuery.PinFilter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public interface StorageProvider {

    void initialize();

    void shutdown();

    // ---- 查询 ----

    /**
     * 玩家采集时刻最晚的一份快照, 带数据体.
     */
    @NotNull
    CompletableFuture<Optional<Snapshot>> latestSnapshot(@NotNull UUID player);

    /**
     * 按快照身份取回整份快照.
     */
    @NotNull
    CompletableFuture<Optional<Snapshot>> snapshot(@NotNull UUID snapshotId);

    /**
     * 按条件查询快照元数据, 采集时刻降序.
     */
    @NotNull
    CompletableFuture<List<SnapshotMeta>> listSnapshots(@NotNull SnapshotQuery query);

    /**
     * 玩家的全部快照元数据.
     */
    @NotNull
    default CompletableFuture<List<SnapshotMeta>> listSnapshots(@NotNull UUID player) {
        return this.listSnapshots(SnapshotQuery.of(player));
    }

    /**
     * 玩家采集时刻最晚的 limit 份快照元数据.
     */
    @NotNull
    default CompletableFuture<List<SnapshotMeta>> listRecentSnapshots(@NotNull UUID player, int limit) {
        return this.listSnapshots(SnapshotQuery.of(player).withLimit(limit));
    }

    /**
     * 玩家全部被固定的快照元数据.
     * 固定快照豁免轮转, 是玩家历史里唯一不会被自动清掉的部分.
     */
    @NotNull
    default CompletableFuture<List<SnapshotMeta>> listPinnedSnapshots(@NotNull UUID player) {
        return this.listSnapshots(SnapshotQuery.of(player).withPinned(PinFilter.PINNED));
    }

    /**
     * 玩家在某个采集时刻区间内的快照元数据, 两端都含.
     */
    @NotNull
    default CompletableFuture<List<SnapshotMeta>> listSnapshotsBetween(@NotNull UUID player, long from, long to) {
        return this.listSnapshots(SnapshotQuery.of(player).between(from, to));
    }

    // ---- 写入 ----

    /**
     * 写入一份快照.
     *
     * @return 落库结果, 语义见 {@link SaveResult}
     */
    @NotNull
    CompletableFuture<SaveResult> saveSnapshot(@NotNull Snapshot snapshot);

    /**
     * 写入快照并保留可重试失败的原始原因, 供重试编排层收敛日志.
     */
    @NotNull
    default CompletableFuture<SaveOutcome> saveSnapshotOutcome(@NotNull Snapshot snapshot) {
        return this.saveSnapshot(snapshot).thenApply(result -> new SaveOutcome(result, null));
    }

    /**
     * 轮转玩家的历史快照: 未固定的快照多于 maxUnpinned 时删除采集时刻最早的超量部分, 固定快照永不轮转.
     *
     * @return 删除的快照数
     */
    @NotNull
    CompletableFuture<Integer> rotate(@NotNull UUID player, int maxUnpinned);

    /**
     * 固定或取消固定一份快照. 固定后豁免 {@link #rotate} 的清理.
     *
     * @return 目标快照存在且状态发生变化时为 true
     */
    @NotNull
    CompletableFuture<Boolean> setPinned(@NotNull UUID snapshotId, boolean pinned);

    /**
     * @return 目标快照存在并被删除时为 true
     */
    @NotNull
    CompletableFuture<Boolean> deleteSnapshot(@NotNull UUID snapshotId);

    // ---- 用户名字映射 ----

    /**
     * 记录或刷新玩家的名字映射, 每次会话开始调用.
     */
    @NotNull
    CompletableFuture<Void> ensureUser(@NotNull UUID player, @NotNull String name);

    /**
     * 按名字查玩家 UUID.
     * 名字被多人用过时取最近一次会话的那位.
     */
    @NotNull
    CompletableFuture<Optional<UUID>> lookupUser(@NotNull String name);

    /** 一次写入的分类结果与可重试失败原因. */
    record SaveOutcome(@NotNull SaveResult result, @Nullable Throwable failure) {
    }

    /** 快照写入结果. */
    enum SaveResult {
        /** 落库, 且是该玩家目前采集时刻最晚的一份. 在线保存的正常结果. */
        SAVED,
        /** 落库, 同 id 的快照已在库中, 本次写入是幂等重放, 未产生副本. */
        DUPLICATE,
        /**
         * 落库, 但库里已存在采集时刻更晚的快照, 因此它落在历史中段.
         * <p> 预期只有在启动时插回本地留存的快照期间出现本结果.
         * <p> 如果在运行时出现了这个结果, 则代表: <strong>有人在本次采集之后为同一名玩家采集并写入了快照</strong>.
         * <ul>
         *   <li>同一玩家出现第二个写方, 即会话锁失效 —— 误判死亡后的夺锁 (卡住的进程不是死掉的进程,
         *       探测区分不了), Redis 故障转移丢键, 或某条保存路径根本没走锁;</li>
         *   <li>本服违反了 timestamp 每玩家严格递增的契约, 自己把自己排到了自己后面;</li>
         *   <li>有工具写入过时间戳在未来的快照.</li>
         * </ul>
         */
        SAVED_OUT_OF_ORDER,
        /**
         * 没落库, 但同一份数据以后重试有希望成功, 例如存储不可达, 选主中, 写关注不满足.
         * 调用方应保留这份快照并重试, 数据库恢复后补上.
         */
        RETRY_LATER,
        /** 没落库, 快照超出存储的大小上限, 重试不会成功, 需要人工介入. */
        REJECTED_OVERSIZED,
        /** 没落库, 快照编码失败或与存储的约束冲突, 重试不会成功, 需要人工介入. */
        REJECTED_MALFORMED;

        // 数据是否已在库中, 三种已落库结果的区别只在于落到历史的什么位置
        public boolean stored() {
            return this == SAVED || this == DUPLICATE || this == SAVED_OUT_OF_ORDER;
        }

        // 同一份快照重试是否有希望成功
        public boolean retriable() {
            return this == RETRY_LATER;
        }
    }
}
