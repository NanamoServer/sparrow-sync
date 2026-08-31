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
     * 结束玩家会话.
     *
     * @return 是否发起了保存, 非 ACTIVE 的会话没有保存这一步.
     */
    public boolean close(@NotNull PlayerSession session, @NotNull SaveCause cause) {
        while (true) {
            SessionState state = session.state();
            // 保存已在进行中/完成
            if (state == SessionState.CLOSED || state == SessionState.SAVING) return false;
            // ACTIVE 走退出保存
            if (state == SessionState.ACTIVE) {
                Player player = Bukkit.getPlayer(session.uuid());
                if (this.submitSave(session, player, cause)) return true;
                continue;
            }
            // 其余状态直接作废终结
            if (session.tryTransition(state, SessionState.CLOSED)) {
                this.release(session);
                return false;
            }
        }
    }

    // 尝试抢下 ACTIVE -> SAVING 并投递采集落库.
    private boolean submitSave(PlayerSession session, Player player, SaveCause cause) {
        if (!session.tryTransition(SessionState.ACTIVE, SessionState.SAVING)) return false;
        this.plugin.snapshotService()
                .captureAndSave(player, cause)
                .whenComplete((result, throwable) -> {
                    session.transition(SessionState.CLOSED);
                    this.release(session);
                });
        return true;
    }

    // 释放会话, 摘出注册表并释放分布式锁. 落库(或作废)在前释放在后, 等锁方抢到后读库必为最新.
    private void release(PlayerSession session) {
        this.sessions.remove(session.uuid(), session);
        String lockValue = session.lockValue();
        // 取锁前就失败的会话没有锁可放
        if (lockValue != null) {
            this.plugin.sessionLock()
                    .release(session.uuid(), lockValue)
                    .whenComplete((deleted, throwable) ->
                            this.logger.file(LogCategory.LOCK, session.uuid(), session.playerName(), LogConstants.LOCK_RELEASED, session.playerName(), throwable != null ? "failed" : String.valueOf(deleted))
                    );
        }
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
