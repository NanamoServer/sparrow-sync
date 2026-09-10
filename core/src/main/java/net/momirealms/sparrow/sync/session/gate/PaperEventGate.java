package net.momirealms.sparrow.sync.session.gate;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.netty.channel.Channel;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.cluster.LockValue;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.proxy.paper.connection.PaperCommonConnectionProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.operation.SessionPrepareResult;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@SuppressWarnings("UnstableApiUsage")
public final class PaperEventGate implements LoginGate, Listener {
    private final SparrowSync plugin;
    private SessionManager sessionManager;

    public PaperEventGate(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onDelayedEnable() {
        this.sessionManager = this.plugin.sessionManager();
        Bukkit.getPluginManager().registerEvents(this, this.plugin.javaPlugin());
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        ServerConfigurationPacketListenerImpl listener = (ServerConfigurationPacketListenerImpl) PaperCommonConnectionProxy.INSTANCE.getHandle(connection);
        Channel channel = listener.connection.channel;
        PlayerProfile profile = connection.getProfile();
        UUID uuid = profile.getId();
        String name = profile.getName();
        assert uuid != null;
        assert name != null;

        PlayerSession existing = this.sessionManager.find(uuid);
        if (existing != null) {
            SessionState state = existing.state();
            if (state == SessionState.SAVING || state == SessionState.CLOSED) {
                existing.released().join();
            } else {
                this.rejectTooFast(connection, uuid, name, "previous session is still " + state);
                return;
            }
        }
        if (channel.isActive()) {
            this.beginLogin(connection, listener.connection, uuid, name);
        }
    }

    // Paper 在所有监听器返回后继续配置任务, 此处等待异步准备链完成
    private void beginLogin(PlayerConfigurationConnection connection, Connection handle, UUID uuid, String name) {
        Channel channel = handle.channel;
        PlayerSession session = this.sessionManager.tryOpen(uuid, name, handle);
        if (session == null) {
            this.rejectTooFast(connection, uuid, name, "another connection won session registration");
            return;
        }
        this.plugin.logger().file(LogCategory.JOIN, uuid, name, LogConstants.GATE_HELD, name);
        // 配置阶段断线没有 PlayerQuitEvent, 直接作废半加载会话
        channel.closeFuture().addListener(future -> {
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
        SessionPrepareResult outcome = this.acquireLock(session, uuid, name, lockStart + TimeUnit.SECONDS.toNanos(budget), lockStart)
                // 锁释放晚于落库, 拿到锁后读库必为最新
                .thenCompose(ignored -> this.sessionManager.prepare(session))
                .thenCombine(userReady, (prepared, ignored) -> prepared)
                .orTimeout(budget, TimeUnit.SECONDS)
                .handle((prepared, throwable) -> {
                    if (throwable == null) return prepared;
                    String reason = throwable instanceof TimeoutException ? "data not ready after " + budget + "s" : String.valueOf(throwable);
                    return new SessionPrepareResult.Failed(reason);
                })
                .join();
        if (!channel.isActive() || session.state() != SessionState.PREPARING) return;
        if (outcome instanceof SessionPrepareResult.Failed failed) {
            this.refuse(connection, session, name, failed.detail());
        } else if (outcome == SessionPrepareResult.READY) {
            this.release(uuid, name);
        }
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
                // 同服锁可能由离线恢复持有, 其余同 id 持锁情况按集群身份冲突处理
                LockValue holder = LockValue.parse(value);
                if (holder != null && holder.serverId().equals(ServerConfig.serverId())) {
                    // 本服离线恢复期间拒绝本次登录, 拒绝日志记录保存中的原因, 玩家可在恢复结束后重连.
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

    // 事件监听器返回后由 Paper 继续配置任务
    private void release(UUID uuid, String name) {
        this.plugin.logger().file(LogCategory.JOIN, uuid, name, LogConstants.GATE_RELEASED, name);
    }

    private void rejectTooFast(PlayerConfigurationConnection connection, UUID uuid, String name, String reason) {
        this.plugin.logger().file(LogCategory.KICK, uuid, name, LogConstants.GATE_KICKED, name, reason);
        connection.disconnect(MessageConstants.KICK_LOGIN_TOO_FAST.build());
    }

    private void refuse(PlayerConfigurationConnection connection, PlayerSession session, String name, String reason) {
        this.sessionManager.abort(session);
        this.plugin.logger().error(LogCategory.KICK, session.uuid(), name, LogConstants.GATE_KICKED, name, reason);
        connection.disconnect(MessageConstants.KICK_SYNC_NOT_READY.build());
    }
}
