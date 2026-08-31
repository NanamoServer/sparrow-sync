package net.momirealms.sparrow.sync.session.gate;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.netty.channel.Channel;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.session.cluster.SessionLock;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.proxy.paper.connection.PaperCommonConnectionProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.session.SnapshotService.PreparedOutcome;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
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
    private final SessionManager sessionManager;
    private final SnapshotService snapshotService;

    public PaperEventGate(@NotNull SparrowSync plugin, @NotNull SnapshotService snapshotService, @NotNull SessionManager sessionManager) {
        this.plugin = plugin;
        this.snapshotService = snapshotService;
        this.sessionManager = sessionManager;
    }

    @Override
    public void register() {
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

        PlayerSession existing = this.sessionManager.session(uuid);
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
            this.beginLogin(connection, channel, uuid, name);
        }
    }

    // Paper 在所有监听器返回后继续配置任务, 此处等待异步准备链完成
    private void beginLogin(PlayerConfigurationConnection connection, Channel channel, UUID uuid, String name) {
        PlayerSession session = this.sessionManager.tryOpen(uuid, name);
        if (session == null) {
            this.rejectTooFast(connection, uuid, name, "another connection won session registration");
            return;
        }
        this.plugin.logger().file(LogCategory.JOIN, uuid, name, LogConstants.GATE_HELD, name);
        // 配置阶段断线没有 PlayerQuitEvent, 直接作废半加载会话
        channel.closeFuture().addListener(future -> {
            if (session.state() == SessionState.PREPARING) {
                this.sessionManager.close(session, SaveCause.DISCONNECT);
            }
        });
        // 写用户名映射与读快照作为一组完成才放人, 名字映射失败不阻断进服, 只发日志警告.
        CompletableFuture<Void> userReady = this.plugin.storageProvider().ensureUser(uuid, name).handle((ignored, throwable) -> {
            if (throwable != null) {
                this.plugin.logger().file(LogCategory.STORAGE, uuid, name, throwable, LogConstants.SYNC_USER_FAILED, name);
            }
            return null;
        });
        int budget = Math.max(1, PluginConfig.synchronization$loginTimeoutSeconds());
        long lockStart = System.nanoTime();
        PreparedOutcome outcome = this.acquireLock(session, uuid, name, lockStart + TimeUnit.SECONDS.toNanos(budget), lockStart)
                // 锁释放晚于落库, 拿到锁后读库必为最新
                .thenCompose(ignored -> this.snapshotService.loadAndPrepare(uuid, name))
                .thenCombine(userReady, (prepared, ignored) -> prepared)
                .orTimeout(budget, TimeUnit.SECONDS)
                .handle((prepared, throwable) -> {
                    if (throwable == null) return prepared;
                    String reason = throwable instanceof TimeoutException ? "data not ready after " + budget + "s" : String.valueOf(throwable);
                    return new PreparedOutcome.Failed(reason);
                })
                .join();
        if (!channel.isActive() || session.state() != SessionState.PREPARING) return;
        switch (outcome) {
            case PreparedOutcome.Failed failed -> this.refuse(connection, session, name, failed.detail());
            // 无历史的新玩家只放行不暂存, join 段把会话转 ACTIVE
            case PreparedOutcome.Empty ignored -> this.release(uuid, name);
            case PreparedOutcome.Ready ready -> {
                session.prepared(ready);
                this.release(uuid, name);
            }
        }
    }

    // 抢会话锁, 直取或等持有服交接, 完成时锁值已交给会话
    private CompletableFuture<Void> acquireLock(PlayerSession session, UUID uuid, String name, long deadlineNanos, long lockStart) {
        return this.plugin.sessionLock().tryAcquire(uuid).thenCompose(outcome -> switch (outcome) {
            // 一次抢到, 无人持有
            case SessionLock.AcquireOutcome.Acquired(String value) -> {
                this.lockGranted(session, uuid, value);
                this.plugin.logger().file(LogCategory.LOCK, uuid, name, LogConstants.LOCK_ACQUIRED, name);
                yield CompletableFuture.<Void>completedFuture(null);
            }
            // 被别的服持有, 走交接探测
            case SessionLock.AcquireOutcome.Held(String value) -> {
                this.plugin.logger().file(LogCategory.LOCK, uuid, name, LogConstants.LOCK_WAITING, name, value);
                yield this.plugin.handoffManager()
                        .awaitHandoff(uuid, value, deadlineNanos)
                        .thenApply(handoff -> {
                            this.lockGranted(session, uuid, handoff.lockValue());
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

    // 锁值交给会话保管, 等锁期间会话已被断线清理时立即自释放, 不留残锁
    private void lockGranted(PlayerSession session, UUID uuid, String value) {
        session.lockValue(value);
        if (session.state() == SessionState.CLOSED) {
            this.plugin.sessionLock().release(uuid, value);
        }
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
        this.sessionManager.close(session, SaveCause.DISCONNECT);
        this.plugin.logger().error(LogCategory.KICK, session.uuid(), name, LogConstants.GATE_KICKED, name, reason);
        connection.disconnect(MessageConstants.KICK_SYNC_NOT_READY.build());
    }
}
