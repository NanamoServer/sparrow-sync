package net.momirealms.sparrow.sync.trigger;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.plugin.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
public final class SnapshotIntervalScheduler {
    private final SchedulerAdapter<?> scheduler;
    private final SyncLogger logger;
    private final Object intervalLock = new Object(); // 串行发布 registration 和它的 timer 句柄
    // 配置关闭时保留 ACTIVE 玩家, 下一次 reload 可以直接重新挂链.
    private final Map<UUID, IntervalRegistration> registrations = new HashMap<>();
    private boolean running = true;

    public SnapshotIntervalScheduler(@NotNull SparrowSync plugin, @NotNull SessionManager sessionManager) {
        this.scheduler = plugin.scheduler();
        this.logger = plugin.logger();
    }

    // 为已经进入 ACTIVE 状态的玩家登记定时链.
    public void activate(@NotNull PlayerSession session, @NotNull Player player) {
        synchronized (this.intervalLock) {
            if (!this.running) return;
            UUID playerId = session.uuid();
            IntervalRegistration registration = this.createRegistration(session, player, System.currentTimeMillis());
            IntervalRegistration previous = this.registrations.put(playerId, registration);
            if (previous != null) {
                previous.cancel();
            }
            if (PluginConfig.synchronization$saveTriggers().interval().enabled()) {
                this.scheduleTimerLocked(registration);
            }
        }
    }

    // Player 实例参与身份判断, 前一份 Quit 不会撤销同 UUID 的后续 registration.
    public void deactivate(@NotNull Player player) {
        synchronized (this.intervalLock) {
            UUID playerId = player.getUniqueId();
            IntervalRegistration registration = this.registrations.get(playerId);
            if (registration == null || registration.player != player) return;
            this.registrations.remove(playerId);
            registration.cancel();
        }
    }

    // 每次 reload 都换一代 registration, 已经投递的旧回调随之失效.
    public void reconfigure() {
        synchronized (this.intervalLock) {
            if (!this.running) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, IntervalRegistration> entry : this.registrations.entrySet()) {
                IntervalRegistration previous = entry.getValue();
                IntervalRegistration next = this.createRegistration(previous.session, previous.player, now);
                entry.setValue(next);
                previous.cancel();
                if (PluginConfig.synchronization$saveTriggers().interval().enabled()) {
                    this.scheduleTimerLocked(next);
                }
            }
        }
    }

    // 停止产生新的定时采集, 已经进入玩家队列的保存继续完成.
    public void shutdown() {
        synchronized (this.intervalLock) {
            this.running = false;
            for (IntervalRegistration registration : this.registrations.values()) {
                registration.cancel();
            }
            this.registrations.clear();
        }
    }

    // 使用当前配置为玩家创建一代新的 deadline.
    private IntervalRegistration createRegistration(PlayerSession session, Player player, long now) {
        long periodMillis = TimeUnit.MINUTES.toMillis(PluginConfig.synchronization$saveTriggers().interval().minutes());
        return new IntervalRegistration(session, player, IntervalDeadline.first(session.uuid(), periodMillis, now));
    }

    // timer 到期后只转交 Entity 队列, 玩家数据仍在它的拥有线程采集.
    private void scheduleTimerLocked(IntervalRegistration registration) {
        long delayMillis = Math.max(0L, registration.deadline.dueAt() - System.currentTimeMillis());
        try {
//            registration.task = this.scheduler.timerLater(() -> this.dispatchToEntity(registration), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            if (this.stopCurrent(registration)) {
                this.logSchedulingFailure(registration, "timer", exception);
            }
        }
    }

    // timer 回调确认代次和会话后, 再申请玩家的 Entity 执行权.
    private void dispatchToEntity(IntervalRegistration registration) {
        if (!this.isCurrent(registration)) return;
        if (!this.isActive(registration)) {
            this.removeCurrent(registration);
            return;
        }
        try {
            SchedulerTask entityTask = this.scheduler.entity().run(registration.player, () -> this.captureAndReschedule(registration), () -> this.removeCurrent(registration));
            if (entityTask == null) {
                this.removeCurrent(registration);
            }
        } catch (RuntimeException exception) {
            this.failScheduling(registration, "entity", exception);
        }
    }

    // Entity 回调再次核对代次, Quit 和 reload 期间排入的旧任务在这里作废.
    private void captureAndReschedule(IntervalRegistration registration) {
        if (!this.isCurrent(registration)) return;
        if (!this.isActive(registration)) {
            this.removeCurrent(registration);
            return;
        }
        try {
//            this.snapshotSaver.accept(registration.session, registration.player);
        } finally {
            this.scheduleNext(registration);
        }
    }

    // 从上一份 deadline 向前推进, 跨过停顿期间错过的周期.
    private void scheduleNext(IntervalRegistration registration) {
        if (!this.isActive(registration)) {
            this.removeCurrent(registration);
            return;
        }
        long now = System.currentTimeMillis();
        IntervalRegistration next = new IntervalRegistration(registration.session, registration.player, registration.deadline.after(now));
        synchronized (this.intervalLock) {
            if (!this.isCurrentLocked(registration)) return;
            this.registrations.put(registration.session.uuid(), next);
            registration.cancel();
            this.scheduleTimerLocked(next);
        }
    }

    // registration 仍占据玩家槽位且配置启用时, 它才是当前代次.
    private boolean isCurrent(IntervalRegistration registration) {
        synchronized (this.intervalLock) {
            return this.isCurrentLocked(registration);
        }
    }

    // 调用方已经持有 intervalLock 时复用当前代次判定.
    private boolean isCurrentLocked(IntervalRegistration registration) {
        return this.running && PluginConfig.synchronization$saveTriggers().interval().enabled() && this.registrations.get(registration.session.uuid()) == registration;
    }

    // SessionManager 会在真正提交时再次核对会话对象身份.
    private boolean isActive(IntervalRegistration registration) {
        return registration.session.state() == SessionState.ACTIVE;
    }

    // 只有当前代次可以移除玩家槽位并撤销自己的 timer.
    private boolean removeCurrent(IntervalRegistration registration) {
        synchronized (this.intervalLock) {
            UUID playerId = registration.session.uuid();
            if (this.registrations.get(playerId) != registration) return false;
            this.registrations.remove(playerId);
            registration.cancel();
            return true;
        }
    }

    // 调度失败后保留玩家登记, reload 可以为它换代并恢复定时链.
    private boolean stopCurrent(IntervalRegistration registration) {
        synchronized (this.intervalLock) {
            if (this.registrations.get(registration.session.uuid()) != registration) return false;
            registration.cancel();
            return true;
        }
    }

    // 当前链的调度异常会停下本代 timer, 并留下玩家和失败阶段.
    private void failScheduling(IntervalRegistration registration, String stage, RuntimeException exception) {
        if (this.stopCurrent(registration)) {
            this.logSchedulingFailure(registration, stage, exception);
        }
    }

    // 同一个调度错误只在这里写一次控制台和 SAVE 文件日志.
    private void logSchedulingFailure(IntervalRegistration registration, String stage, RuntimeException exception) {
        this.logger.error(LogCategory.SAVE, registration.session.uuid(), registration.session.playerName(), exception, LogConstants.SYNC_INTERVAL_SCHEDULE_FAILED, registration.session.playerName(), stage);
    }

    // registration 实例同时是代次令牌, 回调只在它仍是 Map 当前值时继续.
    private static final class IntervalRegistration {
        private final PlayerSession session;
        private final Player player;
        private final IntervalDeadline deadline;
        private @Nullable SchedulerTask task;

        // registration 在创建时固定绑定会话、Player 实例和 deadline.
        private IntervalRegistration(PlayerSession session, Player player, IntervalDeadline deadline) {
            this.session = session;
            this.player = player;
            this.deadline = deadline;
        }

        // 幂等清空当前 timer 句柄并取消仍在等待的任务.
        private void cancel() {
            SchedulerTask task = this.task;
            this.task = null;
            if (task != null) {
                task.cancel();
            }
        }
    }

    record IntervalDeadline(long periodMillis, long dueAt) {

        // UUID 决定周期内的固定相位, 首次 deadline 始终落在 now 之后的一个周期内.
        @NotNull
        static IntervalDeadline first(@NotNull UUID player, long periodMillis, long now) {
            long phase = Math.floorMod(mix(player.getMostSignificantBits() ^ player.getLeastSignificantBits()), periodMillis);
            long cycleStart = now - Math.floorMod(now, periodMillis);
            long dueAt = cycleStart + phase;
            return new IntervalDeadline(periodMillis, dueAt > now ? dueAt : dueAt + periodMillis);
        }

        // 跨过停顿期间遗漏的周期, 返回 now 之后最近的一次 deadline.
        @NotNull
        IntervalDeadline after(long now) {
            long periods = Math.floorDiv(now - this.dueAt, this.periodMillis) + 1L;
            return new IntervalDeadline(this.periodMillis, this.dueAt + periods * this.periodMillis);
        }

        // SplitMix64 finalizer 扩散 UUID 位.
        private static long mix(long value) {
            value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
            value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
            return value ^ (value >>> 31);
        }
    }
}
