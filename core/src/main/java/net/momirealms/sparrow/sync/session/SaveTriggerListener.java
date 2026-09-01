package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
public final class SaveTriggerListener implements Listener {
    private final SparrowSync plugin;
    private final SessionManager sessionManager;
    private final Object intervalLock = new Object(); // 串行发布 registration 和它的任务句柄
    // 配置关闭 interval 时仍保留 ACTIVE 会话, reload 重新开启后可以直接挂链.
    private final Map<UUID, IntervalRegistration> intervalRegistrations = new HashMap<>();
    private boolean intervalRunning = true;

    public SaveTriggerListener(@NotNull SparrowSync plugin, @NotNull SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    // 停止产生新的定时采集, 已经进入玩家队列的保存继续完成.
    public void shutdown() {
        synchronized (this.intervalLock) {
            this.intervalRunning = false;
            for (IntervalRegistration registration : this.intervalRegistrations.values()) {
                registration.cancel();
            }
            this.intervalRegistrations.clear();
        }
    }

    // reload 使用同一个当前时刻重建 deadline, 每个会话依然保持自己的 UUID 相位.
    public void reconfigureIntervalTasks() {
        synchronized (this.intervalLock) {
            if (!this.intervalRunning) return;
            PluginConfig.IntervalTrigger settings = PluginConfig.synchronization$saveTriggers().interval();
            if (!settings.enabled()) {
                // ACTIVE registration 继续留在表中, 下次 reload 开启 interval 时直接重挂.
                for (IntervalRegistration registration : this.intervalRegistrations.values()) {
                    registration.cancel();
                }
                return;
            }
            long periodMillis = TimeUnit.MINUTES.toMillis(settings.minutes());
            long now = System.currentTimeMillis();
            // 重新开启或修改周期都会换一代 registration, 已投递的旧回调随之失效.
            for (Map.Entry<UUID, IntervalRegistration> entry : this.intervalRegistrations.entrySet()) {
                IntervalRegistration previous = entry.getValue();
                IntervalRegistration next = new IntervalRegistration(previous.session, previous.player, IntervalDeadline.first(entry.getKey(), periodMillis, now));
                entry.setValue(next);
                previous.cancel();
                this.scheduleIntervalTaskLocked(next);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = this.sessionManager.session(player.getUniqueId());
        // 登录应用失败时会在 Join 事件内嵌套触发 Quit, 只有最终 ACTIVE 的会话会挂上定时链.
        if (session == null || session.state() != SessionState.ACTIVE) return;
        // 配置关闭时只保留 registration, reload 开启后再挂 timer.
        synchronized (this.intervalLock) {
            if (!this.intervalRunning) return;
            PluginConfig.IntervalTrigger settings = PluginConfig.synchronization$saveTriggers().interval();
            UUID playerId = session.uuid();
            long periodMillis = TimeUnit.MINUTES.toMillis(settings.minutes());
            IntervalRegistration registration = new IntervalRegistration(session, player, IntervalDeadline.first(playerId, periodMillis, System.currentTimeMillis()));
            IntervalRegistration previous = this.intervalRegistrations.put(playerId, registration);
            if (previous != null) {
                previous.cancel();
            }
            if (settings.enabled()) {
                this.scheduleIntervalTaskLocked(registration);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        // Player 实例参与身份判断, 前一份 Quit 不会撤销同 UUID 的后续 registration.
        synchronized (this.intervalLock) {
            IntervalRegistration registration = this.intervalRegistrations.get(playerId);
            if (registration == null || registration.player != player) return;
            this.intervalRegistrations.remove(playerId);
            registration.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PluginConfig.WorldChangeTrigger settings = PluginConfig.synchronization$saveTriggers().worldChange();
        if (!settings.enabled()) return;
        Player player = event.getPlayer();
        if (settings.ignoredFromWorlds().contains(event.getFrom().getName()) || settings.ignoredToWorlds().contains(player.getWorld().getName())) return;
        PlayerSession session = this.sessionManager.session(player.getUniqueId());
        if (session != null) this.sessionManager.trySubmitActiveSnapshot(session, player, SaveCause.WORLD_CHANGE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        PluginConfig.GameModeChangeTrigger settings = PluginConfig.synchronization$saveTriggers().gameModeChange();
        GameMode target = event.getNewGameMode();
        if (!settings.enabled() || settings.ignoredTargetModes().contains(target)) return;
        Player player = event.getPlayer();
        PlayerSession session = this.sessionManager.session(player.getUniqueId());
        if (session == null || session.state() != SessionState.ACTIVE) return;
        // 事件仍处于模式切换过程, 下一玩家 tick 再核对最终模式并采集.
        this.plugin.scheduler().entity().run(player, () -> {
            if (player.getGameMode() == target) this.sessionManager.trySubmitActiveSnapshot(session, player, SaveCause.GAME_MODE_CHANGE);
        }, () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        PluginConfig.DeathTrigger settings = PluginConfig.synchronization$saveTriggers().death();
        Player player = event.getPlayer();
        if (settings.ignoredWorlds().contains(player.getWorld().getName())) return;
        PlayerSession session = this.sessionManager.session(player.getUniqueId());
        if (session == null || session.state() != SessionState.ACTIVE) return;
        // MONITOR 阶段采集插件调整后的死亡事件状态, Paper 此时还未清理玩家物品.
        if (settings.saveBeforeDeath()) this.sessionManager.trySubmitActiveSnapshot(session, player, SaveCause.PRE_DEATH);
        if (settings.saveAfterDeath()) {
            // 事件返回后 Paper 按 keepInventory 和 itemsToKeep 清理物品, 下一玩家 tick 采集实际留存状态.
            this.plugin.scheduler().entity().run(player, () -> {
                if (player.isDead()) this.sessionManager.trySubmitActiveSnapshot(session, player, SaveCause.DEATH);
            }, () -> {});
        }
    }

    // 根据 deadline 挂起一次异步 timer, 唤醒后的回调仍受当前 registration 约束.
    private void scheduleIntervalTaskLocked(IntervalRegistration registration) {
        long delayMillis = Math.max(0L, registration.deadline.dueAt() - System.currentTimeMillis());
        // 墙钟到期后只投递实体任务, 玩家数据始终留在它的拥有线程采集.
        registration.task(this.plugin.scheduler().asyncLater(() -> {
            // 核对 registration, Quit 和 reload, 已换代的任务作废.
            if (!this.isCurrentInterval(registration) || !this.usesCurrentIntervalSettings(registration)) return;
            if (!this.isActiveInterval(registration)) {
                this.removeInterval(registration);
                return;
            }
            // 进行一次快照保存
            SchedulerTask entityTask = this.plugin.scheduler().entity().run(registration.player, () -> {
                // 核对 registration, Quit 和 reload, 已换代的任务作废.
                if (!this.isCurrentInterval(registration) || !this.usesCurrentIntervalSettings(registration)) return;
                if (!this.isActiveInterval(registration)) {
                    this.removeInterval(registration);
                    return;
                }
                try {
                    this.sessionManager.trySubmitActiveSnapshot(registration.session, registration.player, SaveCause.INTERVAL);
                } finally {
                    this.scheduleNextInterval(registration);
                }
            }, () -> this.removeInterval(registration));
            if (entityTask == null) {
                this.removeInterval(registration);
            }
        }, delayMillis, TimeUnit.MILLISECONDS));
    }

    // 从上一份 deadline 向前推进, 跨过停顿期间错过的周期.
    private void scheduleNextInterval(IntervalRegistration registration) {
        if (!this.isActiveInterval(registration)) {
            this.removeInterval(registration);
            return;
        }
        long now = System.currentTimeMillis();
        IntervalRegistration next = new IntervalRegistration(registration.session, registration.player, registration.deadline.after(now));
        synchronized (this.intervalLock) {
            UUID playerId = registration.session.uuid();
            if (!this.intervalRunning || this.intervalRegistrations.get(playerId) != registration || !this.usesCurrentIntervalSettings(registration)) return;
            this.intervalRegistrations.put(playerId, next);
            registration.cancel();
            this.scheduleIntervalTaskLocked(next);
        }
    }

    // 检查 registration 仍占据玩家槽位且定时链正在运行时, 它才是当前代次.
    private boolean isCurrentInterval(IntervalRegistration registration) {
        synchronized (this.intervalLock) {
            return this.intervalRunning && this.intervalRegistrations.get(registration.session.uuid()) == registration;
        }
    }

    // 通过会话对象身份确认 registration 仍对应当前 ACTIVE 会话.
    private boolean isActiveInterval(IntervalRegistration registration) {
        return this.sessionManager.session(registration.session.uuid()) == registration.session && registration.session.state() == SessionState.ACTIVE;
    }

    // 回调携带的周期必须与 reload 后的当前配置一致.
    private boolean usesCurrentIntervalSettings(IntervalRegistration registration) {
        PluginConfig.IntervalTrigger settings = PluginConfig.synchronization$saveTriggers().interval();
        return settings.enabled() && TimeUnit.MINUTES.toMillis(settings.minutes()) == registration.deadline.periodMillis();
    }

    // 移除玩家槽位并撤销自己的任务.
    private void removeInterval(IntervalRegistration registration) {
        synchronized (this.intervalLock) {
            UUID playerId = registration.session.uuid();
            // 有当前代次可以移除
            if (this.intervalRegistrations.get(playerId) != registration) return;
            this.intervalRegistrations.remove(playerId);
            registration.cancel();
        }
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

        // 发布新句柄时撤销上一份句柄, 同一 registration 最多保留一个 timer.
        private void task(SchedulerTask task) {
            SchedulerTask previous = this.task;
            this.task = task;
            if (previous != null) {
                previous.cancel();
            }
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
