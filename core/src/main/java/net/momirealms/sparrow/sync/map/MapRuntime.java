package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;

// 维护已经更新或正在展示的负数副本, 让后续来源更新能进入原生地图对象.
@ApiStatus.Internal
public final class MapRuntime {
    private final MapReceiver receiver;
    private final SyncLogger logger;
    private final ConcurrentHashMap<Integer, Tracked> tracked = new ConcurrentHashMap<>(); // 本服务已发现的负数 ID, 静止地图仍保留条目
    private volatile boolean closed;

    public MapRuntime(@NotNull MapReceiver receiver, @NotNull SyncLogger logger) {
        this.receiver = receiver;
        this.logger = logger;
    }

    // 首次观察负数地图时按全局 ID 查库, 身份与内容一起取自共享记录.
    public void observe(int globalId) {
        if (this.closed || globalId >= 0 || this.tracked.containsKey(globalId)) return;
        Tracked entry = new Tracked();
        if (this.tracked.putIfAbsent(globalId, entry) != null) return;
        this.refresh(globalId, entry, true);
    }

    // 登记已完成更新的地图, 使静止副本也能接收后续更新.
    public void updated(int globalId) {
        if (this.closed) return;
        this.tracked.computeIfAbsent(globalId, ignored -> new Tracked());
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

    // 为已观察副本请求共享记录, 同图读取和失效补拉由接收器合并.
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

    // 关服停止刷新并释放已追踪的身份.
    public void close() {
        this.closed = true;
        this.tracked.clear();
    }

    // 保存一张负数地图的告警状态.
    private static final class Tracked {
        private boolean failed; // 本轮连续失败是否已经打印过告警, 由条目监视器保护
    }
}
