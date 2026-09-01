package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
public final class SaveTriggerListener implements Listener {
    private static final long INTERVAL_CHECK_TICKS = 20L; // deadline 轮询频率, 实际保存按各自周期触发

    private final SparrowSync plugin;
    private final SessionManager sessionManager;
    // 全局定时线程负责推进, 玩家退出时由拥有线程移除. // todo 不能异步吗?
    private final ConcurrentHashMap<UUID, IntervalDeadline> intervalDeadlines = new ConcurrentHashMap<>();
    private @Nullable SchedulerTask intervalTask;

    public SaveTriggerListener(@NotNull SparrowSync plugin, @NotNull SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    // 检查任务常驻运行并逐轮读取配置, reload 直接作用于下一轮检查.
    public void start() {
        this.intervalTask = this.plugin.scheduler().sync().runRepeating(this::runIntervalSaves, INTERVAL_CHECK_TICKS, INTERVAL_CHECK_TICKS);
    }

    // 停止产生新的定时采集, 已经进入玩家队列的保存继续完成.
    public void shutdown() {
        SchedulerTask task = this.intervalTask;
        if (task != null) task.cancel();
        this.intervalDeadlines.clear();
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
        player.getScheduler().run(this.plugin.javaPlugin(), task -> {
            // todo 我认为玩家可能会在当前tick退出服务器
            if (player.getGameMode() == target) this.sessionManager.trySubmitActiveSnapshot(session, player, SaveCause.GAME_MODE_CHANGE);
        }, null);
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
            // todo 我认为玩家退出和死亡可能发生在同1tick, 不可能存在下1tick.
            // todo 不兼容spigot
            player.getScheduler().run(this.plugin.javaPlugin(), task -> {
                if (player.isDead()) this.sessionManager.trySubmitActiveSnapshot(session, player, SaveCause.DEATH);
            }, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.intervalDeadlines.remove(event.getPlayer().getUniqueId());
    }

    // 每秒筛出到期玩家, 实际数据采集回到各自的拥有线程.
    private void runIntervalSaves() {
        PluginConfig.IntervalTrigger settings = PluginConfig.synchronization$saveTriggers().interval();
        if (!settings.enabled()) {
            // 再次开启时按当前时间重新分配下一次触发点.
            this.intervalDeadlines.clear();
            return;
        }
        long now = System.currentTimeMillis();
        long periodMillis = TimeUnit.MINUTES.toMillis(settings.minutes());
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerSession session = this.sessionManager.session(player.getUniqueId());
            if (session == null || session.state() != SessionState.ACTIVE) continue;
            UUID playerId = player.getUniqueId();
            IntervalDeadline deadline = this.intervalDeadlines.get(playerId);
            if (deadline == null || deadline.periodMillis() != periodMillis) {
                // 首次见到玩家或 reload 改变周期时, 从当前时间登记下一次触发点.
                this.intervalDeadlines.put(playerId, IntervalDeadline.first(playerId, periodMillis, now));
                continue;
            }
            if (deadline.dueAt() > now) continue;
            // 退出清理若已移除这份 deadline, 本轮调度随之作废.
            if (!this.intervalDeadlines.replace(playerId, deadline, deadline.after(now))) continue;
            // todo 不兼容spigot
            player.getScheduler().run(this.plugin.javaPlugin(), task -> this.sessionManager.trySubmitActiveSnapshot(session, player, SaveCause.INTERVAL), null);
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

        // SplitMix64 finalizer 扩散 UUID 位, 相近 UUID 也能分布到不同相位.
        private static long mix(long value) {
            value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
            value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
            return value ^ (value >>> 31);
        }
    }
}
