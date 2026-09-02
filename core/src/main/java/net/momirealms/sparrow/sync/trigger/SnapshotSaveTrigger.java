package net.momirealms.sparrow.sync.trigger;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
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
import org.bukkit.event.world.WorldSaveEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class SnapshotSaveTrigger implements Listener {
    private final SparrowSync plugin;
    private SessionManager sessionManager;

    public SnapshotSaveTrigger(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onDelayedEnable() {
        this.sessionManager = this.plugin.sessionManager();
        Bukkit.getPluginManager().registerEvents(this, this.plugin.javaPlugin());
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
            this.plugin.scheduler().entity().run(player, () -> this.sessionManager.trySubmitActiveSnapshot(session, player, SaveCause.WORLD_SAVE), () -> {});
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
