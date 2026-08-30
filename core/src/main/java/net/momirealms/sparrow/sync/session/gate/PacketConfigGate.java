package net.momirealms.sparrow.sync.session.gate;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.proxy.minecraft.server.network.ServerCommonPacketListenerImplProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.session.SnapshotService.PreparedOutcome;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.network.NMSPacketEvent;
import net.momirealms.sparrow.ui.network.NMSPacketListener;
import net.momirealms.sparrow.ui.network.NetworkManager;
import net.momirealms.sparrow.ui.network.NetworkUser;
import net.momirealms.sparrow.ui.network.PacketFlow;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@SuppressWarnings("UnstableApiUsage")
public final class PacketConfigGate {
    private final SparrowSync plugin;
    private final SessionManager sessionManager;
    private final SnapshotService snapshotService;
    private final NetworkManager networkManager;

    public PacketConfigGate(@NotNull SparrowSync plugin, @NotNull SnapshotService snapshotService, @NotNull SessionManager sessionManager) {
        this.plugin = plugin;
        this.snapshotService = snapshotService;
        this.sessionManager = sessionManager;
        this.networkManager = SparrowUI.getInstance().networkManager();
    }

    public void register() {
        this.networkManager.registerNMSPacketListener(new NMSPacketListener() {
            @Override
            public void onPacketSend(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
                PacketConfigGate.this.onFinishConfiguration(user, event);
            }
        }, ClientboundFinishConfigurationPacket.class, PacketFlow.CLIENTBOUND);
    }

    // 运行在连接的 eventLoop 上, 不得阻塞
    private void onFinishConfiguration(NetworkUser user, NMSPacketEvent event) {
        if (!(user.channel().pipeline().get("packet_handler") instanceof Connection connection)) return;
        if (!(connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl listener)) return;
        // 获取基本信息
        PlayerProfile profile = listener.paperConnection.getProfile();
        UUID uuid = profile.getId();
        String name = profile.getName();
        assert uuid != null;
        assert name != null;
        // 上一会话的状态决定这次连入的去向
        PlayerSession existing = this.sessionManager.session(uuid);
        if (existing != null && existing.state() != SessionState.CLOSED) {
            // ACTIVE = 在线玩家被送回配置阶段再返回 (Reconfiguration, 插件 API 触发时不处理).
            if (existing.state() == SessionState.ACTIVE) return;
            // 上一会话仍在收尾或另一次登录仍在进行就拒绝连入.
            event.cancelled(true);
            this.plugin.logger().warn(TranslationManager.console(LogConstants.GATE_KICKED, name, "previous session is still " + existing.state()));
            listener.paperConnection.disconnect(MessageConstants.KICK_LOGIN_TOO_FAST.build());
            return;
        }
        // 取消数据包, 翻转 isClosed 字段, 取消服务端 15 秒限制.
        event.cancelled(true);
        ServerCommonPacketListenerImplProxy.INSTANCE.setClosed(listener, false);
        PlayerSession session = this.sessionManager.open(uuid, name);
        // 如果配置阶段就断线, 则直接清理掉.
        user.channel().closeFuture().addListener(future -> {
            if (session.state() == SessionState.PREPARING) {
                this.sessionManager.close(session, SaveCause.DISCONNECT);
            }
        });
        // 写用户名映射与读快照作为一组完成才放人; 名字映射失败不阻断进服, 只告警
        CompletableFuture<Void> userReady = this.plugin.storageProvider().ensureUser(uuid, name).handle((ignored, throwable) -> {
            if (throwable != null) {
                this.plugin.logger().warn(TranslationManager.console(LogConstants.SYNC_USER_FAILED, name), throwable);
            }
            return null;
        });
        int budget = Math.max(1, PluginConfig.synchronization$loginTimeoutSeconds());
        this.snapshotService.loadAndPrepare(uuid, name)
                .thenCombine(userReady, (outcome, ignored) -> outcome)
                .orTimeout(budget, TimeUnit.SECONDS)
                .whenComplete((outcome, throwable) -> {
                    if (throwable != null) {
                        String reason = throwable instanceof TimeoutException ? "data not ready after " + budget + "s" : String.valueOf(throwable);
                        this.refuse(listener, session, name, reason);
                        return;
                    }
                    switch (outcome) {
                        case PreparedOutcome.Failed failed -> this.refuse(listener, session, name, failed.detail());
                        // 无历史的新玩家只放行不暂存, join 段把会话转 ACTIVE
                        case PreparedOutcome.Empty ignored -> this.release(user);
                        case PreparedOutcome.Ready ready -> {
                            session.prepared(ready);
                            this.release(user);
                        }
                    }
                });
    }

    // 补发被扣下的终结包.
    private void release(NetworkUser user) {
        this.networkManager.send(user, ClientboundFinishConfigurationPacket.INSTANCE);
    }

    // 加载失败, 拒绝进服.
    private void refuse(ServerConfigurationPacketListenerImpl listener, PlayerSession session, String name, String reason) {
        this.sessionManager.close(session, SaveCause.DISCONNECT);
        this.plugin.logger().error(TranslationManager.console(LogConstants.GATE_KICKED, name, reason));
        listener.paperConnection.disconnect(MessageConstants.KICK_SYNC_NOT_READY.build());
    }
}
