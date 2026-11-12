package net.momirealms.sparrow.sync.player.component;

import net.momirealms.sparrow.sync.cluster.HandoffManager;
import net.momirealms.sparrow.sync.cluster.LockValue;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class PlayerSessionLock {
    private final SparrowSync plugin = SparrowSync.instance();
    private SessionLock sessionLock;
    private HandoffManager handoffs;
    private SyncLogger logger;

    void onLoad() {
        this.sessionLock = this.plugin.sessionLock();
        this.handoffs = this.plugin.handoffManager();
        this.logger = this.plugin.logger();
    }

    // 取得玩家的会话锁并把锁值交给会话, 被其他服占用时等待对方交接或接管.
    @NotNull
    CompletableFuture<Void> acquire(@NotNull PlayerSession session, long deadlineNanos) {
        UUID uuid = session.uuid();
        String name = session.playerName();
        long started = System.nanoTime();
        return this.sessionLock.tryAcquire(uuid).thenCompose(outcome ->
                switch (outcome) {
                    case SessionLock.AcquireOutcome.Acquired(String value) -> {
                        this.accept(session, value);
                        this.logger.file(LogCategory.LOCK, uuid, name, LogConstants.LOCK_ACQUIRED, name);
                        yield CompletableFuture.<Void>completedFuture(null);
                    }
                    case SessionLock.AcquireOutcome.Held(String value) -> {
                        // 本实例的离线恢复也会持锁, 玩家需稍后重新连接.
                        LockValue holder = LockValue.parse(value);
                        if (holder != null && holder.instanceId().equals(this.plugin.serverRegistry().instanceId())) {
                            if (this.plugin.snapshotService().restoringOffline(uuid)) {
                                yield CompletableFuture.failedFuture(new IllegalStateException("offline snapshot restore is still saving"));
                            }
                            this.logger.error(LogCategory.LOCK, uuid, name, LogConstants.LOCK_SELF_CONFLICT, name, value);
                            yield CompletableFuture.failedFuture(new IllegalStateException("session lock is held by this server instance"));
                        }
                        this.logger.file(LogCategory.LOCK, uuid, name, LogConstants.LOCK_WAITING, name, value);
                        yield this.handoffs.awaitHandoff(uuid, value, deadlineNanos).thenAccept(handoff -> {
                            this.accept(session, handoff.lockValue());
                            this.logger.file(
                                    LogCategory.LOCK,
                                    uuid,
                                    name,
                                    LogConstants.LOCK_HANDOFF,
                                    name,
                                    handoff.method(),
                                    String.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                            );
                        });
                    }
                }
        );
    }

    private void accept(PlayerSession session, String lockValue) {
        this.handoffs.clearSettled(session.uuid());
        session.state.lockToken = lockValue;
    }

    // 退出快照已入库, 接手方探测到后可以直接取锁. <strong>必须先于 release 调用</strong>.
    void settled(@NotNull PlayerSession session) {
        this.handoffs.recordSettled(session.uuid());
    }

    // 按本次取得的完整锁值归还, 会话未取得过锁时直接完成.
    @NotNull
    CompletableFuture<Void> release(@NotNull PlayerSession session) {
        String token = session.state.lockToken;
        if (token == null) return CompletableFuture.completedFuture(null);
        return this.sessionLock.release(session.uuid(), token).handle((deleted, failure) -> {
            this.logger.file(LogCategory.LOCK, session.uuid(), session.playerName(), LogConstants.LOCK_RELEASED, session.playerName(), failure != null ? "failed" : String.valueOf(deleted));
            return null;
        });
    }
}