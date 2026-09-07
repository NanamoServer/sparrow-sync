package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

// 维护已经更新或正在展示的本服地图副本, 让后续来源更新能进入 NMS 地图对象.
@ApiStatus.Internal
public final class MapRuntime {
    private final MapReceiver receiver;
    private final Executor worker;
    private final SyncLogger logger;
    private final ConcurrentHashMap<Integer, Tracked> tracked = new ConcurrentHashMap<>(); // 已登记接收更新的全局地图 ID, 登记后持续保留条目
    private volatile boolean closed;

    public MapRuntime(@NotNull MapReceiver receiver, @NotNull Executor worker, @NotNull SyncLogger logger) {
        this.receiver = receiver;
        this.worker = worker;
        this.logger = logger;
    }

    // 首次发送负数 ID 地图数据时登记该地图, 按全局地图 ID 查询数据库中的地图同步标识与内容.
    public void observe(int globalId) {
        if (this.closed || globalId >= 0 || this.tracked.containsKey(globalId)) return;
        Tracked entry = new Tracked();
        if (this.tracked.putIfAbsent(globalId, entry) != null) return;
        // 在发包回调中登记首见 ID, 接收流程交给异步线程发起.
        try {
            this.worker.execute(() -> this.refresh(globalId, entry, true));
        } catch (RejectedExecutionException exception) {
            if (!this.closed) this.failed(globalId, entry, exception);
        }
    }

    // 登记已完成更新的地图副本, 供后续通知触发更新.
    public void updated(int globalId) {
        if (this.closed) return;
        this.tracked.computeIfAbsent(globalId, ignored -> new Tracked());
    }

    // 使本服 Caffeine 地图缓存失效, 并为已登记的地图副本安排数据更新.
    public void invalidate(int globalId) {
        if (this.closed) return;
        this.receiver.invalidate(globalId);
        Tracked entry = this.tracked.get(globalId);
        if (entry != null) {
            this.refresh(globalId, entry, false);
        }
    }

    // 为已登记的地图副本请求地图存储记录, 同图读取和失效补拉由接收器合并.
    private void refresh(int id, Tracked entry, boolean database) {
        if (this.closed) return;
        // 先标记强制读库, 已有接收任务也会在更新前按此要求补拉.
        if (database) {
            this.receiver.invalidate(id, true);
        }
        this.receiver.receive(id).whenComplete((localId, failure) -> {
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

    // 关服停止刷新并释放已登记的全局地图 ID.
    public void close() {
        this.closed = true;
        this.tracked.clear();
    }

    // 保存一张负数地图的告警状态.
    private static final class Tracked {
        private boolean failed; // 本轮连续失败是否已经打印过告警, 由条目监视器保护
    }
}
