package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

@ApiStatus.Internal
public final class MapRuntime {
    private static final long REVALIDATE_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final MapReceiver receiver;
    private final IntFunction<CompletableFuture<MapIdentity>> identities;
    private final LongSupplier clock;
    private final SyncLogger logger;
    private final ConcurrentHashMap<Integer, Tracked> tracked = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public MapRuntime(@NotNull MapReceiver receiver, @NotNull IntFunction<CompletableFuture<MapIdentity>> identities, @NotNull LongSupplier clock, @NotNull SyncLogger logger) {
        this.receiver = receiver;
        this.identities = identities;
        this.clock = clock;
        this.logger = logger;
    }

    // 发包线程只登记 ID, 原生查询由 identities 投递到拥有线程.
    public void observe(int globalId) {
        if (this.closed || globalId >= 0 || this.tracked.containsKey(globalId)) return;
        Tracked entry = new Tracked();
        if (this.tracked.putIfAbsent(globalId, entry) != null) return;
        this.identify(globalId, entry);
    }

    // 已经完成接收安装的地图直接沿用其身份, 静止地图同样保留在重查范围内.
    public void installed(@NotNull MapIdentity identity) {
        if (this.closed) return;
        Tracked entry = this.tracked.computeIfAbsent(identity.globalId(), ignored -> new Tracked());
        synchronized (entry) {
            if (entry.identity == null) {
                entry.nextCheck = this.clock.getAsLong() + REVALIDATE_NANOS;
            }
            entry.identity = identity;
        }
    }

    private void identify(int id, Tracked entry) {
        synchronized (entry) {
            if (this.closed || entry.loading) return;
            entry.loading = true;
        }
        CompletableFuture.completedFuture(null).thenCompose(ignored -> this.identities.apply(id)).whenComplete((identity, failure) -> {
            synchronized (entry) {
                entry.loading = false;
                if (this.closed) return;
                entry.identity = identity;
                entry.nextCheck = failure == null ? Long.MAX_VALUE : this.clock.getAsLong() + RETRY_NANOS;
            }
            if (failure != null) {
                this.failed(id, entry, failure);
            } else if (identity != null) {
                this.refresh(id, entry, true);
            }
        });
    }

    public void invalidate(int globalId) {
        if (this.closed) return;
        this.receiver.invalidate(globalId);
        Tracked entry = this.tracked.get(globalId);
        if (entry != null) {
            this.refresh(globalId, entry, false);
        }
    }

    // 只查询最后已提交内容, 定时器和重连均不采集来源世界.
    public void revalidate() {
        if (this.closed) return;
        this.receiver.invalidateAll();
        for (var entry : this.tracked.entrySet()) {
            this.refresh(entry.getKey(), entry.getValue(), true);
        }
    }

    public void tick() {
        if (this.closed) return;
        long now = this.clock.getAsLong();
        for (var current : this.tracked.entrySet()) {
            Tracked entry = current.getValue();
            synchronized (entry) {
                if (now < entry.nextCheck || entry.loading) continue;
            }
            if (entry.identity == null) {
                this.identify(current.getKey(), entry);
            } else {
                this.refresh(current.getKey(), entry, true);
            }
        }
    }

    private void refresh(int id, Tracked entry, boolean database) {
        MapIdentity identity;
        synchronized (entry) {
            identity = entry.identity;
            if (this.closed || identity == null) return;
            if (entry.loading) {
                entry.again = true;
                return;
            }
            entry.loading = true;
        }
        if (database) {
            this.receiver.invalidate(id, true);
        }
        this.receiver.receive(identity).whenComplete((localId, failure) -> {
            boolean again;
            synchronized (entry) {
                entry.loading = false;
                if (this.closed) return;
                entry.nextCheck = this.clock.getAsLong() + (failure == null ? REVALIDATE_NANOS : RETRY_NANOS);
                again = entry.again;
                entry.again = false;
                if (failure == null) {
                    entry.failed = false;
                }
            }
            if (failure != null) {
                this.failed(id, entry, failure);
            }
            if (again) {
                this.refresh(id, entry, true);
            }
        });
    }

    private void failed(int id, Tracked entry, Throwable failure) {
        synchronized (entry) {
            if (entry.failed) return;
            entry.failed = true;
        }
        this.logger.warnWithFileCause(LogCategory.DATA, null, null, failure, LogConstants.DATA_MAP_REFRESH_FAILED, String.valueOf(id), String.valueOf(failure));
    }

    @NotNull
    public List<MapIdentity> identities() {
        List<MapIdentity> identities = new ArrayList<>();
        for (Tracked entry : this.tracked.values()) {
            MapIdentity identity = entry.identity;
            if (identity != null) {
                identities.add(identity);
            }
        }
        return identities;
    }

    public void close() {
        this.closed = true;
    }

    private static final class Tracked {
        private volatile @Nullable MapIdentity identity;
        private long nextCheck;
        private boolean loading;
        private boolean again;
        private boolean failed;
    }
}
