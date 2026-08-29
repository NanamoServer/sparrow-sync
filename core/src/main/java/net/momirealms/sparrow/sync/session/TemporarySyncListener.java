package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.session.SnapshotService.LoadOutcome;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * 临时测试类 todo 未来删除
 * 临时同步挂点: join 读库应用, quit 采集保存, 串起单服闭环冒烟.
 * 没有登录期拦截, 玩家在应用完成前可自由行动; Phase B 的登录管线就位后本类整体删除.
 */
public final class TemporarySyncListener implements Listener {
    private final SparrowSync plugin;

    public TemporarySyncListener(SparrowSync plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        SnapshotService service = this.plugin.snapshotService();
        if (service == null) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        this.plugin.storageProvider().ensureUser(uuid, player.getName()).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                this.plugin.logger().warn(TranslationManager.console(LogConstants.SYNC_USER_FAILED, player.getName()), throwable);
            }
        });
        service.loadAndApply(player).whenComplete((outcome, throwable) -> {
            if (throwable != null) return;
            if (outcome instanceof LoadOutcome.Applied || outcome instanceof LoadOutcome.Empty) {
                service.markSynced(uuid);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        SnapshotService service = this.plugin.snapshotService();
        if (service == null) return;
        Player player = event.getPlayer();
        if (!service.forgetSynced(player.getUniqueId())) {
            this.plugin.logger().warn(TranslationManager.console(LogConstants.SYNC_SAVE_SKIPPED_UNSYNCED, player.getName()));
            return;
        }
        service.captureAndSave(player, SaveCause.DISCONNECT);
    }
}
