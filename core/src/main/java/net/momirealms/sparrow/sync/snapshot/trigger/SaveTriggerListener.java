package net.momirealms.sparrow.sync.snapshot.trigger;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
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
            if (VersionHelper.isFolia()) {
                // Folia 仅在 global tick 的 saveIncrementally(true) 派发定时世界保存事件, 不拥有任何玩家.
                // 到玩家的 Region 后才接受保存, SessionManager 会检查通知是否已经过期.
                this.plugin.scheduler().entity().run(player, () -> this.sessions.captureLaterAndSave(session, player, SaveCause.WORLD_SAVE), () -> {});
            } else {
                // Paper/Spigot 在主线程派发, 玩家线程采集组当场采完并入队, 不再延后 1 tick.
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeforeDeath(PlayerDeathEvent event) {
        PluginConfig.DeathTrigger settings = PluginConfig.synchronization$saveTriggers().deathTrigger();
        if (!settings.saveBeforeDeath()) return;
        Player player = event.getPlayer();
        if (settings.ignoredWorlds().contains(player.getWorld().getName())) return;
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session == null || session.state() != SessionState.ACTIVE) return;
        this.sessions.captureNowAndSave(session, player, SaveCause.PRE_DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAfterDeath(PlayerDeathEvent event) {
        PluginConfig.DeathTrigger settings = PluginConfig.synchronization$saveTriggers().deathTrigger();
        if (!settings.saveAfterDeath()) return;
        Player player = event.getPlayer();
        if (settings.ignoredWorlds().contains(player.getWorld().getName())) return;
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session == null || session.state() != SessionState.ACTIVE) return;
        this.plugin.scheduler().entity().run(player, () -> {
            if (player.isDead()) this.sessions.captureNowAndSave(session, player, SaveCause.DEATH);
        }, () -> {});
    }
}
