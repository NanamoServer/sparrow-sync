package net.momirealms.sparrow.sync.session.gate;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.cluster.LockValue;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.proxy.minecraft.server.network.ServerCommonPacketListenerImplProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionPrepareResult;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
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
public final class ConfigurationPacketGate implements LoginGate {
    private static final AttributeKey<Boolean> GATE_PASSED = AttributeKey.valueOf(ConfigurationPacketGate.class, "gate-passed");

    private SparrowSync plugin;
    private SessionManager sessionManager;
    private NetworkManager networkManager;

    public ConfigurationPacketGate(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onDelayedEnable() {
        this.sessionManager = this.plugin.sessionManager();
        this.networkManager = SparrowUI.getInstance().networkManager();
        this.networkManager.registerNMSPacketListener(new NMSPacketListener() {
            @Override
            public void onPacketSend(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
                ConfigurationPacketGate.this.onFinishConfiguration(user, event);
            }
        }, ClientboundFinishConfigurationPacket.class, PacketFlow.CLIENTBOUND);
    }

    // 运行在连接的 eventLoop 上, 不得阻塞
    private void onFinishConfiguration(NetworkUser user, NMSPacketEvent event) {
        Channel channel = user.channel();
        if (!(channel.pipeline().get("packet_handler") instanceof Connection connection)) return;
        if (!(connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl listener)) return;
        // 获取基本信息
        PlayerProfile profile = listener.paperConnection.getProfile();
        UUID uuid = profile.getId();
        String name = profile.getName();
        assert uuid != null;
        assert name != null;
        // channel 属性区分同一物理连接的 reconfiguration 与新连接快速重入
        boolean passed = Boolean.TRUE.equals(channel.attr(GATE_PASSED).get());
        PlayerSession existing = this.sessionManager.find(uuid);
        if (existing != null) {
            SessionState state = existing.state();
            // 如果是新连接并且旧连接还未释放, 就在旧连接Channel上注册关闭时进行登录的回调, 然后持续等待.
            if (passed && (state == SessionState.SAVING || state == SessionState.CLOSED)) {
                event.cancelled(true);
                ServerCommonPacketListenerImplProxy.INSTANCE.setClosed(listener, false);
                existing.released().thenRun(() -> channel.eventLoop().execute(() -> {
                    if (channel.isActive()) {
                        this.beginLogin(user, listener, uuid, name);
                    }
                }));
                return;
            }
            event.cancelled(true);
            this.rejectTooFast(listener, uuid, name, "previous session is still " + state);
            return;
        }
        // 扣住终结包, 加载期间暂停 vanilla 的 15 秒 finish 应答计时
        event.cancelled(true);
        ServerCommonPacketListenerImplProxy.INSTANCE.setClosed(listener, false);
        this.beginLogin(user, listener, uuid, name);
    }

    // 原子注册会话并启动配置阶段的数据准备
    private void beginLogin(NetworkUser user, ServerConfigurationPacketListenerImpl listener, UUID uuid, String name) {
        PlayerSession session = this.sessionManager.tryOpen(uuid, name, listener.connection);
        if (session == null) {
            this.rejectTooFast(listener, uuid, name, "another connection won session registration");
            return;
        }
        this.plugin.logger().file(LogCategory.JOIN, uuid, name, LogConstants.GATE_HELD);
        // 如果配置阶段就断线, 则直接清理掉.
        user.channel().closeFuture().addListener(future -> {
            if (session.state() == SessionState.PREPARING) {
                this.sessionManager.abort(session);
            }
        });
        // 写用户名映射与读快照作为一组完成才放人, 名字映射失败不阻断进服, 只发日志警告.
        CompletableFuture<Void> userReady = this.plugin.storageProvider().ensureUser(uuid, name).handle((ignored, throwable) -> {
            if (throwable != null) {
                this.plugin.logger().file(LogCategory.STORAGE, uuid, name, throwable, LogConstants.SYNC_USER_FAILED, name);
                return null;
            }
            this.plugin.playerDirectory().remember(uuid, name);
            return null;
        });
        int budget = Math.max(1, PluginConfig.synchronization$loginTimeoutSeconds());
        long lockStart = System.nanoTime();
        this.acquireLock(session, uuid, name, lockStart + TimeUnit.SECONDS.toNanos(budget), lockStart)
                // 锁释放晚于落库, 拿到锁后读库必为最新
                .thenCompose(ignored -> this.sessionManager.prepare(session))
                .thenCombine(userReady, (outcome, ignored) -> outcome)
                .orTimeout(budget, TimeUnit.SECONDS)
                .whenComplete((outcome, throwable) -> {
                    if (throwable != null) {
                        String reason = throwable instanceof TimeoutException ? "data not ready after " + budget + "s" : String.valueOf(throwable);
                        this.refuse(listener, session, name, reason);
                        return;
                    }
                    if (outcome instanceof SessionPrepareResult.Failed failed) {
                        this.refuse(listener, session, name, failed.detail());
                    } else if (outcome instanceof SessionPrepareResult.Ready) {
                        this.release(user, listener, uuid, name);
                    }
                });
    }

    // 抢会话锁, 直取或等持有服交接, 完成时锁值已交给会话
    private CompletableFuture<Void> acquireLock(PlayerSession session, UUID uuid, String name, long deadlineNanos, long lockStart) {
        return this.plugin.sessionLock().tryAcquire(uuid).thenCompose(outcome -> switch (outcome) {
            // 一次抢到, 无人持有
            case SessionLock.AcquireOutcome.Acquired(String value) -> {
                this.sessionManager.lockAcquired(session, value);
                this.plugin.logger().file(LogCategory.LOCK, uuid, name, LogConstants.LOCK_ACQUIRED, name);
                yield CompletableFuture.<Void>completedFuture(null);
            }
            // 被别的服持有, 走交接探测
            case SessionLock.AcquireOutcome.Held(String value) -> {
                // 发现锁值挂着本服 id, 说明是另一台同 id 的服务器在线, 拒绝玩家进服并向发起警告
                LockValue holder = LockValue.parse(value);
                if (holder != null && holder.serverId().equals(ServerConfig.serverId())) {
                    this.plugin.logger().error(LogCategory.LOCK, uuid, name, LogConstants.LOCK_SELF_CONFLICT, name, value);
                    yield CompletableFuture.failedFuture(new IllegalStateException("session lock is held by a server with the same server-id"));
                }
                this.plugin.logger().file(LogCategory.LOCK, uuid, name, LogConstants.LOCK_WAITING, name, value);
                yield this.plugin.handoffManager()
                        .awaitHandoff(uuid, value, deadlineNanos)
                        .thenApply(handoff -> {
                            this.sessionManager.lockAcquired(session, handoff.lockValue());
                            this.plugin.logger().file(
                                    LogCategory.LOCK,
                                    uuid,
                                    name,
                                    LogConstants.LOCK_HANDOFF,
                                    name,
                                    handoff.method(),
                                    String.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lockStart))
                            );
                            return null;
                        });
            }
        });
    }

    // 恢复 vanilla finish 应答计时并补发终结包
    private void release(NetworkUser user, ServerConfigurationPacketListenerImpl listener, UUID uuid, String name) {
        Channel channel = user.channel();
        channel.eventLoop().execute(() -> {
            channel.attr(GATE_PASSED).set(true);
            ServerCommonPacketListenerImplProxy.INSTANCE.setClosedListenerTime(listener, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            ServerCommonPacketListenerImplProxy.INSTANCE.setClosed(listener, true);
            this.plugin.logger().file(LogCategory.JOIN, uuid, name, LogConstants.GATE_RELEASED, name);
            this.networkManager.send(user, ClientboundFinishConfigurationPacket.INSTANCE);
        });
    }

    // 拒绝无法取得会话占位的连接
    private void rejectTooFast(ServerConfigurationPacketListenerImpl listener, UUID uuid, String name, String reason) {
        this.sessionManager.recordLoginRejection();
        this.plugin.logger().file(LogCategory.KICK, uuid, name, LogConstants.GATE_KICKED, name, reason);
        listener.paperConnection.disconnect(MessageConstants.KICK_LOGIN_TOO_FAST.build());
    }

    // 加载失败, 拒绝进服.
    private void refuse(ServerConfigurationPacketListenerImpl listener, PlayerSession session, String name, String reason) {
        this.sessionManager.recordLoginRejection();
        this.sessionManager.abort(session);
        this.plugin.logger().error(LogCategory.KICK, session.uuid(), name, LogConstants.GATE_KICKED, name, reason);
        listener.paperConnection.disconnect(MessageConstants.KICK_SYNC_NOT_READY.build());
    }
}
