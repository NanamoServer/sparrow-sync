package net.momirealms.sparrow.sync.snapshot.trigger;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class SaveTriggerListener implements Listener {
    private final SparrowSync plugin;
    private final SessionManager sessions;

    public SaveTriggerListener(@NotNull SparrowSync plugin, @NotNull SessionManager sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
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
            if (VersionHelper.hasFoliaPatch) {
                // Folia 的定时世界保存事件在全局线程触发
                // 切到玩家区域后再接受保存, SessionManager 会检查会话是否仍有效
                this.plugin.scheduler().platform().runLater(() -> this.sessions.captureLaterAndSave(session, player, SaveCause.WORLD_SAVE), () -> {}, 0, player);
            } else {
                // Paper/Spigot 已在主线程, 立即完成同步类型采集并排队保存
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
        this.plugin.scheduler().platform().runLater(() -> {
            if (player.getGameMode() == target) this.sessions.captureNowAndSave(session, player, SaveCause.GAME_MODE_CHANGE);
        }, () -> {}, 0, player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeforeDeath(PlayerDeathEvent event) {
        PluginConfig.DeathTrigger settings = PluginConfig.synchronization$saveTriggers().deathTrigger();
        if (!settings.saveBeforeDeath()) return;
        Player player = event.getEntity();
        if (settings.ignoredWorlds().contains(player.getWorld().getName())) return;
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session == null || session.state() != SessionState.ACTIVE) return;
        this.sessions.captureNowAndSave(session, player, SaveCause.PRE_DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAfterDeath(PlayerDeathEvent event) {
        PluginConfig.DeathTrigger settings = PluginConfig.synchronization$saveTriggers().deathTrigger();
        if (!settings.saveAfterDeath()) return;
        Player player = event.getEntity();
        if (settings.ignoredWorlds().contains(player.getWorld().getName())) return;
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session == null || session.state() != SessionState.ACTIVE) return;
        this.plugin.scheduler().platform().runLater(() -> {
            if (player.isDead()) this.sessions.captureNowAndSave(session, player, SaveCause.DEATH);
        }, () -> {}, 0, player);
    }
}
