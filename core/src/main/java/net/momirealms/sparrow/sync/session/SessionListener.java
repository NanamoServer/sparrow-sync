package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.session.SessionManager.CloseResult;
import net.momirealms.sparrow.sync.session.SnapshotService.LoadOutcome;
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
import org.bukkit.event.world.WorldSaveEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;


public final class SessionListener implements Listener {
    private final SparrowSync plugin;
    private final SnapshotService snapshotService;
    private final SessionManager sessionManager;

    public SessionListener(@NotNull SparrowSync plugin, @NotNull SnapshotService snapshotService, @NotNull SessionManager sessionManager) {
        this.plugin = plugin;
        this.snapshotService = snapshotService;
        this.sessionManager = sessionManager;
    }

    // 应用配置阶段加载完成的数据.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = this.sessionManager.session(player.getUniqueId());
        // 会话不存在说明本次进服没有经过配置阶段的挂起加载数据, 直接踢出.
        if (session == null) {
            this.plugin.logger().file(LogCategory.KICK, player.getUniqueId(), player.getName(), LogConstants.GATE_KICKED, player.getName(), "no session, the login gate did not cover this join");
            player.kick(MessageConstants.KICK_SYNC_NOT_READY.build());
            return;
        }
        this.plugin.logger().file(LogCategory.JOIN, player.getUniqueId(), player.getName(), LogConstants.SESSION_JOIN);
        this.applyStored(session, player);
    }

    // 消费会话暂存并应用, 必须在玩家线程上调用.
    private void applyStored(PlayerSession session, Player player) {
        // 抢不到转移说明会话已被断线清理关掉, 本次应用作废
        if (!session.tryTransition(SessionState.PREPARING, SessionState.APPLYING)) return;
        var prepared = session.consumePrepared();
        // 无历史快照代表是新玩家, 只放行不暂存
        if (prepared == null) {
            session.tryTransition(SessionState.APPLYING, SessionState.ACTIVE);
            return;
        }
        // 给玩家应用快照数据
        LoadOutcome outcome;
        try {
            outcome = this.snapshotService.applyPrepared(player, prepared);
        } catch (Throwable throwable) {
            this.plugin.logger().file(LogCategory.KICK, player.getUniqueId(), player.getName(), throwable, LogConstants.GATE_KICKED, player.getName(), String.valueOf(throwable));
            this.kickOnOwningThread(session, player);
            return;
        }
        // 应用成功
        if (outcome instanceof LoadOutcome.Applied) {
            session.tryTransition(SessionState.APPLYING, SessionState.ACTIVE);
            return;
        }
        // 应用失败
        LoadOutcome.Failed failed = (LoadOutcome.Failed) outcome;
        this.plugin.logger().file(LogCategory.KICK, player.getUniqueId(), player.getName(), LogConstants.GATE_KICKED, player.getName(), failed.detail());
        this.kickOnOwningThread(session, player);
    }

    // 关闭会话并踢出玩家. kick 会同步触发 quit 事件重入 onQuit, 所以必须先关闭会话.
    private void kickOnOwningThread(PlayerSession session, Player player) {
        this.sessionManager.close(session, SaveCause.DISCONNECT);
        player.kick(MessageConstants.KICK_SYNC_NOT_READY.build());
    }

    // 玩家退出服务器时关闭会话并保存数据.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = this.sessionManager.session(player.getUniqueId());
        if (session == null) return; // 说明会话已经被关闭, 无需处理.
        this.plugin.logger().file(LogCategory.QUIT, player.getUniqueId(), player.getName(), LogConstants.SESSION_QUIT);
        if (this.sessionManager.closeDeferred(session, player, SaveCause.DISCONNECT) == CloseResult.RELEASED_UNSYNCED) {
            // 会话从未就绪, 没有保存这一步; 半加载状态存出去会覆盖好数据
            this.plugin.logger().file(LogCategory.SAVE, player.getUniqueId(), player.getName(), LogConstants.SYNC_SAVE_SKIPPED_UNSYNCED, player.getName());
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(WorldSaveEvent event) {
        if (!PluginConfig.synchronization$saveTriggers().worldSave().enabled()) return;
        List<Player> players = event.getWorld().getPlayers();
        int size = players.size();
        for (int i = 0; i < size; i++) {
            Player player = players.get(i);
            PlayerSession session = this.sessionManager.session(player.getUniqueId());
            if (session == null) continue;
            this.sessionManager.trySubmitDeferredActiveSnapshot(session, player, SaveCause.WORLD_SAVE);
        }
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
}
