package net.momirealms.sparrow.sync.map;

import net.minecraft.server.MinecraftServer;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

// 维护已经更新或正在展示的负数副本, 让后续来源更新能进入原生地图对象.
@ApiStatus.Internal
public final class MapRuntime {
    private final MapReceiver receiver;
    private final NativeMapAdapter nativeMaps;
    private final MinecraftServer server;
    private final Executor nativeExecutor; // 身份查询由允许原生访问的线程执行
    private final SyncLogger logger;
    private final ConcurrentHashMap<Integer, Tracked> tracked = new ConcurrentHashMap<>(); // 本服务已发现的负数 ID, 静止地图仍保留条目
    private volatile boolean closed;

    public MapRuntime(@NotNull MapReceiver receiver, @NotNull NativeMapAdapter nativeMaps, @NotNull MinecraftServer server, @NotNull Executor nativeExecutor, @NotNull SyncLogger logger) {
        this.receiver = receiver;
        this.nativeMaps = nativeMaps;
        this.server = server;
        this.nativeExecutor = nativeExecutor;
        this.logger = logger;
    }

    // 从发包路径发现待识别的负数地图.
    public void observe(int globalId) {
        if (this.closed || globalId >= 0 || this.tracked.containsKey(globalId)) return;
        Tracked entry = new Tracked();
        if (this.tracked.putIfAbsent(globalId, entry) != null) return;
        this.identify(globalId, entry);
    }

    // 登记已完成更新的身份, 使静止副本也能接收后续更新.
    public void updated(@NotNull MapIdentity identity) {
        if (this.closed) return;
        Tracked entry = this.tracked.computeIfAbsent(identity.globalId(), ignored -> new Tracked());
        entry.identity = identity;
    }

    // 识别原生地图维度中的来源身份, 成功后核对数据库内容.
    private void identify(int id, Tracked entry) {
        if (this.closed) return;
        CompletableFuture.completedFuture(null).thenCompose(ignored -> CompletableFuture.supplyAsync(() -> {
            if (this.closed) return null;
            return this.nativeMaps.replicaIdentity(this.server.overworld(), id);
        }, this.nativeExecutor)).whenComplete((identity, failure) -> {
            if (this.closed) return;
            entry.identity = identity;
            if (failure != null) {
                this.failed(id, entry, failure);
                // 身份查询失败后撤销登记, 后续发包可再次触发识别.
                this.tracked.remove(id, entry);
            } else if (identity != null) {
                this.refresh(id, entry, true);
            }
        });
    }

    // 通知对应的内容过期, 已知副本立即安排刷新.
    public void invalidate(int globalId) {
        if (this.closed) return;
        this.receiver.invalidate(globalId);
        Tracked entry = this.tracked.get(globalId);
        if (entry != null) {
            this.refresh(globalId, entry, false);
        }
    }

    // 为已识别副本请求当前内容, 同图读取和失效补拉由接收器合并.
    private void refresh(int id, Tracked entry, boolean database) {
        MapIdentity identity = entry.identity;
        if (this.closed || identity == null) return;
        // 先标记强制读库, 已有接收任务也会在更新前按此要求补拉.
        if (database) {
            this.receiver.invalidate(id, true);
        }
        this.receiver.receive(identity).whenComplete((localId, failure) -> {
            if (this.closed) return;
            if (failure == null) {
                synchronized (entry) {
                    entry.failed = false;
                }
            } else {
                this.failed(id, entry, failure);
            }
        });
    }

    // 记录一个连续故障周期的首次失败, 成功刷新后允许再次记录.
    private void failed(int id, Tracked entry, Throwable failure) {
        synchronized (entry) {
            if (entry.failed) return;
            entry.failed = true;
        }
        this.logger.warnWithFileCause(LogCategory.DATA, null, null, failure, LogConstants.DATA_MAP_REFRESH_FAILED, String.valueOf(id), String.valueOf(failure));
    }

    // 关服停止刷新并释放已追踪的身份.
    public void close() {
        this.closed = true;
        this.tracked.clear();
    }

    // 保存一张负数地图的身份和告警状态.
    private static final class Tracked {
        private volatile @Nullable MapIdentity identity; // 识别完成后的完整身份, null 表示尚未识别或不属于本插件
        private boolean failed; // 本轮连续失败是否已经打印过告警, 由条目监视器保护
    }
}
