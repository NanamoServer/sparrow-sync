package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType;
import net.momirealms.sparrow.sync.util.VersionHelper;
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
    private final SessionManager sessions;

    public SessionListener(@NotNull SparrowSync plugin, @NotNull SessionManager sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    // 应用配置阶段加载完成的数据.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session == null) {
            this.plugin.logger().file(LogCategory.KICK, player.getUniqueId(), player.getName(), LogConstants.GATE_KICKED, player.getName(), "no session, the login gate did not cover this join");
            this.kick(player);
            return;
        }

        // 注入玩家的 PlayerAdvancements#progressChanged, 注入失败时拒绝进入.
        if (
                PluginConfig.synchronization$advancements().injectProgressChanged()
                && this.plugin.dataRegistry().type(AdvancementsDataType.ADVANCEMENTS) instanceof AdvancementsDataType advancements
        ) {
            try {
                advancements.injectTracker(player);
            } catch (Throwable throwable) {
                this.plugin.logger().error(LogCategory.KICK, player.getUniqueId(), player.getName(), throwable, LogConstants.DATA_ADVANCEMENT_TRACKER_INSTALL_FAILED, player.getName());
                this.sessions.abort(session);
                this.kick(player);
                return;
            }
        }

        // 属性回调在 Player apply 前安装, 单实例失败由类型保留普通采集路径.
        if (
                PluginConfig.synchronization$attributes().injectConsumer()
                && this.plugin.dataRegistry().type(AttributesDataType.ATTRIBUTES) instanceof AttributesDataType attributes
        ) {
            attributes.injectTracker(player);
        }

        this.plugin.logger().file(LogCategory.JOIN, player.getUniqueId(), player.getName(), LogConstants.SESSION_JOIN);
        // todo 设计插件 API 时重新确定登录时的数据同步事件.
        SnapshotApplyResult result;
        try {
            result = this.sessions.activate(session, player);
        } catch (Throwable throwable) {
            this.plugin.logger().file(LogCategory.KICK, player.getUniqueId(), player.getName(), throwable, LogConstants.GATE_KICKED, player.getName(), String.valueOf(throwable));
            this.kick(player);
            return;
        }
        if (result instanceof SnapshotApplyResult.Failed(String detail)) {
            this.plugin.logger().file(LogCategory.KICK, player.getUniqueId(), player.getName(), LogConstants.GATE_KICKED, player.getName(), detail);
            this.kick(player);
        } else if (result instanceof SnapshotApplyResult.Applied) {
            this.plugin.playerDirectory().presence(player.getUniqueId(), player.getName(), true);
        }
    }

    private void kick(Player player) {
        player.kick(MessageConstants.KICK_SYNC_NOT_READY.build());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.plugin.playerDirectory().presence(player.getUniqueId(), player.getName(), false);
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session == null) return;
        this.plugin.logger().file(LogCategory.QUIT, player.getUniqueId(), player.getName(), LogConstants.SESSION_QUIT);
        // 原版会在 PlayerQuitEvent 返回后立刻保存玩家文件, 玩家所在 Region 的下一 tick 再采集时本地数据已经就绪.
        this.sessions.disconnect(session, player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PluginConfig.WorldChangeTrigger settings = PluginConfig.synchronization$saveTriggers().worldChange();
        if (!settings.enabled()) return;
        Player player = event.getPlayer();
        if (settings.ignoredFromWorlds().contains(event.getFrom().getName()) || settings.ignoredToWorlds().contains(player.getWorld().getName())) return;
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session != null) this.sessions.captureNowAndSave(session, player, SaveCause.WORLD_CHANGE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(WorldSaveEvent event) {
        if (!PluginConfig.synchronization$saveTriggers().worldSave().enabled()) return;
        List<Player> players = event.getWorld().getPlayers();
        int size = players.size();
        for (int i = 0; i < size; i++) {
            Player player = players.get(i);
            PlayerSession session = this.sessions.find(player.getUniqueId());
            if (session == null) continue;
            if (VersionHelper.isFolia()) {
                // Folia 仅在 global tick 的 saveIncrementally(true) 派发定时世界保存事件, 不拥有任何玩家.
                // 到玩家的 Region 后才接受保存, SessionManager 会检查通知是否已经过期.
                this.plugin.scheduler().entity().run(player, () -> this.sessions.captureLaterAndSave(session, player, SaveCause.WORLD_SAVE), () -> {});
            } else {
                // Paper/Spigot 在主线程派发, 玩家线程采集组当场采完并入队, 不再延后一 tick.
                this.sessions.captureLaterAndSave(session, player, SaveCause.WORLD_SAVE);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        PluginConfig.GameModeChangeTrigger settings = PluginConfig.synchronization$saveTriggers().gameModeChange();
        GameMode target = event.getNewGameMode();
        if (!settings.enabled() || settings.ignoredTargetModes().contains(target)) return;
        Player player = event.getPlayer();
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session == null || session.state() != SessionState.ACTIVE) return;
        this.plugin.scheduler().entity().run(player, () -> {
            if (player.getGameMode() == target) this.sessions.captureNowAndSave(session, player, SaveCause.GAME_MODE_CHANGE);
        }, () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        PluginConfig.DeathTrigger settings = PluginConfig.synchronization$saveTriggers().deathTrigger();
        Player player = event.getPlayer();
        if (settings.ignoredWorlds().contains(player.getWorld().getName())) return;
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session == null || session.state() != SessionState.ACTIVE) return;
        if (settings.saveBeforeDeath()) this.sessions.captureNowAndSave(session, player, SaveCause.PRE_DEATH);
        if (settings.saveAfterDeath()) {
            this.plugin.scheduler().entity().run(player, () -> {
                if (player.isDead()) this.sessions.captureNowAndSave(session, player, SaveCause.DEATH);
            }, () -> {});
        }
    }
}
