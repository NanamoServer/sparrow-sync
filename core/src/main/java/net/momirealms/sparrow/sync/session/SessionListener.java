package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotApplyResult;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

public final class SessionListener implements Listener {
    private final SparrowSync plugin;
    private final SessionManager sessions;

    public SessionListener(@NotNull SparrowSync plugin, @NotNull SessionManager sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    // 在 Join 事件的 LOWEST 优先级应用数据
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session == null) {
            this.plugin.logger().file(LogCategory.KICK, player.getUniqueId(), player.getName(), LogConstants.GATE_KICKED, player.getName(), "no session, the login gate did not cover this join");
            this.kick(player);
            return;
        }

        // 安装成就进度跟踪器, 失败时终止登录
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

        // 应用数据前安装属性跟踪器, 安装失败的属性仍按常规方式采集
        if (
                PluginConfig.synchronization$attributes().injectOnDirtyConsumer()
                && this.plugin.dataRegistry().type(AttributesDataType.ATTRIBUTES) instanceof AttributesDataType attributes
        ) {
            attributes.injectTracker(player);
        }

        this.plugin.logger().file(LogCategory.JOIN, player.getUniqueId(), player.getName(), LogConstants.SESSION_JOIN);
        SnapshotApplyResult result;
        try {
            result = this.sessions.activate(session, player);
        } catch (Throwable throwable) {
            this.plugin.logger().file(LogCategory.KICK, player.getUniqueId(), player.getName(), throwable, LogConstants.GATE_KICKED, player.getName(), String.valueOf(throwable));
            this.kick(player);
            return;
        }
        if (result instanceof SnapshotApplyResult.Failed failed) {
            this.plugin.logger().file(LogCategory.KICK, player.getUniqueId(), player.getName(), LogConstants.GATE_KICKED, player.getName(), failed.detail());
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
        PlayerSession session = this.sessions.find(player.getUniqueId());
        if (session != null) {
            this.plugin.logger().file(LogCategory.QUIT, player.getUniqueId(), player.getName(), LogConstants.SESSION_QUIT);
            // 原版在退出事件返回后保存玩家文件, 最终采集安排在所在区域的下一 tick
            this.sessions.disconnect(session, player);
        }
        // 将玩家从在线名单中移除
        this.plugin.playerDirectory().presence(player.getUniqueId(), player.getName(), false);
    }

}
