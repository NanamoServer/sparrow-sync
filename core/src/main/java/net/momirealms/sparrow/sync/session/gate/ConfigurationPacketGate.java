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
import net.momirealms.sparrow.sync.snapshot.operation.SessionPrepareResult;
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

    // 运行在连接的 eventLoop 上, 不能阻塞等待
    private void onFinishConfiguration(NetworkUser user, NMSPacketEvent event) {
        Channel channel = user.channel();
        if (!(channel.pipeline().get("packet_handler") instanceof Connection connection)) return;
        if (!(connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl listener)) return;
        PlayerProfile profile = listener.paperConnection.getProfile();
        UUID uuid = profile.getId();
        String name = profile.getName();
        assert uuid != null;
        assert name != null;
        // passed 记录本连接是否已放行过配置结束包, 用于识别再次进入配置阶段
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
        // 暂缓发送配置结束包, 数据准备期间暂停原版的应答超时计时
        event.cancelled(true);
        ServerCommonPacketListenerImplProxy.INSTANCE.setClosed(listener, false);
        this.beginLogin(user, listener, uuid, name);
    }

    // 注册会话, 取得锁后准备登录数据
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
        // 登录前等待用户名记录更新完成, 更新失败时记日志并继续登录
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
                // 取得会话锁后再读取快照, 正常交接时旧会话已完成保存
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
                    } else if (outcome == SessionPrepareResult.READY) {
                        this.release(user, listener, uuid, name);
                    }
                });
    }

    // 获取会话锁, 已被占用时等待交接, 成功后将锁值交给会话
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
                // 本服离线恢复也会持锁, 其余同 server-id 的锁按身份冲突处理
                LockValue holder = LockValue.parse(value);
                if (holder != null && holder.serverId().equals(ServerConfig.serverId())) {
                    // 离线恢复尚未完成, 玩家需稍后重新连接
                    if (this.plugin.snapshotService().restoringOffline(uuid)) {
                        yield CompletableFuture.failedFuture(new IllegalStateException("offline snapshot restore is still saving"));
                    }
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

    // 补发配置结束包, 并重新开始原版应答计时
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

    // 已有会话尚未结束时拒绝重复登录
    private void rejectTooFast(ServerConfigurationPacketListenerImpl listener, UUID uuid, String name, String reason) {
        this.plugin.logger().file(LogCategory.KICK, uuid, name, LogConstants.GATE_KICKED, name, reason);
        listener.paperConnection.disconnect(MessageConstants.KICK_LOGIN_TOO_FAST.build());
    }

    // 数据准备失败时作废会话并断开连接
    private void refuse(ServerConfigurationPacketListenerImpl listener, PlayerSession session, String name, String reason) {
        this.sessionManager.abort(session);
        this.plugin.logger().error(LogCategory.KICK, session.uuid(), name, LogConstants.GATE_KICKED, name, reason);
        listener.paperConnection.disconnect(MessageConstants.KICK_SYNC_NOT_READY.build());
    }
}
