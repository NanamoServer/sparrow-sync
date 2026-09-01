package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {
    private final SparrowSync plugin;
    private final SyncLogger logger;
    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    public SessionManager(@NotNull SparrowSync plugin, @NotNull SyncLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @Nullable
    public PlayerSession tryOpen(@NotNull UUID uuid, @NotNull String name) {
        PlayerSession session = new PlayerSession(uuid, name);
        return this.sessions.putIfAbsent(uuid, session) == null ? session : null;
    }

    /**
     * 会话仍为 ACTIVE 时, 采集并提交一份触发器快照.
     *
     * @return 成功发起保存时为 true
     */
    public boolean trySubmitActiveSnapshot(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        if (this.sessions.get(session.uuid()) != session) return false;
        // 监视器一直持有到快照入队, 其他线程发起的会话关闭会在这里等待.
        synchronized (session) {
            if (this.sessions.get(session.uuid()) != session || session.state() != SessionState.ACTIVE || session.triggeredSnapshotInProgress) return false;
            // SnapshotSaveEvent 可以在当前线程重入 close, 标记让关闭快照排在本次快照之后.
            session.triggeredSnapshotInProgress = true;
            try {
                this.plugin.snapshotService().captureAndSave(player, cause);
            } finally {
                session.triggeredSnapshotInProgress = false;
                SaveCause closeCause = session.deferredCloseCause;
                session.deferredCloseCause = null;
                if (closeCause != null) {
                    session.transition(SessionState.SAVING);
                    this.submitClosingSnapshot(session, player, closeCause);
                }
            }
            return true;
        }
    }

    /**
     * 结束玩家会话, ACTIVE 会话会先提交一份 DISCONNECT 或 SHUTDOWN 快照.
     *
     * @return 是否发起了保存, 非 ACTIVE 的会话没有保存这一步.
     */
    public boolean close(@NotNull PlayerSession session, @NotNull SaveCause cause) {
        synchronized (session) {
            if (this.sessions.get(session.uuid()) != session) return false;
            SessionState state = session.state();
            if (state == SessionState.CLOSED || state == SessionState.SAVING) return false;
            if (state == SessionState.ACTIVE) {
                // SnapshotSaveEvent 重入 close 时, 等当前触发器快照入队后再提交关闭快照.
                if (session.triggeredSnapshotInProgress) {
                    if (session.deferredCloseCause == null) session.deferredCloseCause = cause;
                    return true;
                }
                session.transition(SessionState.SAVING);
                this.submitClosingSnapshot(session, Bukkit.getPlayer(session.uuid()), cause);
                return true;
            }
            // 玩家还没有完成同步, 这时退出不回写数据.
            session.transition(SessionState.CLOSED);
            this.release(session);
            return false;
        }
    }

    // 关闭快照完成后结束会话, 再释放分布式锁和会话表条目.
    private void submitClosingSnapshot(PlayerSession session, Player player, SaveCause cause) {
        this.plugin.snapshotService()
                .captureAndSave(player, cause)
                .whenComplete((result, throwable) -> {
                    session.transition(SessionState.CLOSED);
                    this.release(session);
                });
    }

    // 释放会话, 释放分布式锁并摘出注册表. 落库(或作废)在前释放在后, 等锁方抢到后读库必为最新.
    private void release(PlayerSession session) {
        String lockValue = session.lockValue();
        // 拆锁命令需要先于摘注册表, 同时取锁前就失败的会话没有锁可放
        if (lockValue != null) {
            this.plugin.sessionLock()
                    .release(session.uuid(), lockValue)
                    .whenComplete((deleted, throwable) ->
                            this.logger.file(LogCategory.LOCK, session.uuid(), session.playerName(), LogConstants.LOCK_RELEASED, session.playerName(), throwable != null ? "failed" : String.valueOf(deleted))
                    );
        }
        this.sessions.remove(session.uuid(), session);
        session.released().complete(null);
    }

    @Nullable
    public PlayerSession session(@NotNull UUID player) {
        return this.sessions.get(player);
    }

    public int sessionCount() {
        return this.sessions.size();
    }

    // 关服收尾, ACTIVE 投递 SHUTDOWN 保存, 进服中途的作废.
    public void shutdown() {
        int submitted = 0;
        for (PlayerSession session : this.sessions.values()) {
            if (this.close(session, SaveCause.SHUTDOWN)) submitted++;
        }
        if (submitted > 0) {
            this.logger.info(LogCategory.SAVE, LogConstants.SYNC_SHUTDOWN_SAVED, String.valueOf(submitted));
        }
    }
}
