package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {
    private final SparrowSync plugin;
    private SyncLogger logger;
    private SnapshotService snapshotService;
    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    public SessionManager(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
        this.logger = this.plugin.logger();
        this.snapshotService = this.plugin.snapshotService();
    }

    public void onDelayedEnable() {
        Bukkit.getPluginManager().registerEvents(new SessionListener(this.plugin, this.snapshotService, this), this.plugin.javaPlugin());
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
        synchronized (session) {
            if (this.sessions.get(session.uuid()) != session || session.state() != SessionState.ACTIVE) return false;
            this.snapshotService.captureAndSubmitAccepted(player, cause);
            return true;
        }
    }

    /**
     * 会话仍为 ACTIVE 时, 把采集和保存阶段一起提交到玩家串行线程.
     *
     * @return 成功接纳保存时为 true
     */
    public boolean trySubmitDeferredActiveSnapshot(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        synchronized (session) {
            if (this.sessions.get(session.uuid()) != session || session.state() != SessionState.ACTIVE) return false;
            this.snapshotService.submitDeferredCaptureAccepted(player, cause);
            return true;
        }
    }

    /**
     * 结束玩家会话, ACTIVE 会话会在调用线程采集并提交关闭快照.
     *
     * @return 本次关闭对会话产生的结果
     */
    @NotNull
    public CloseResult close(@NotNull PlayerSession session, @NotNull SaveCause cause) {
        return this.close(session, cause, null);
    }

    /**
     * 结束玩家会话, ACTIVE 会话的采集和保存阶段一起进入玩家串行线程.
     *
     * @return 本次关闭对会话产生的结果
     */
    @NotNull
    public CloseResult closeDeferred(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        return this.close(session, cause, player);
    }

    @NotNull
    private CloseResult close(@NotNull PlayerSession session, @NotNull SaveCause cause, @Nullable Player deferredPlayer) {
        synchronized (session) {
            if (this.sessions.get(session.uuid()) != session) return CloseResult.STALE;
            SessionState state = session.state();
            if (state == SessionState.CLOSED || state == SessionState.SAVING) return CloseResult.ALREADY_CLOSING;
            if (state == SessionState.ACTIVE) {
                session.transition(SessionState.SAVING);
                Player player = deferredPlayer == null ? Bukkit.getPlayer(session.uuid()) : deferredPlayer;
                CompletableFuture<SnapshotService.SnapshotSaveOutcome> closing = deferredPlayer != null
                        ? this.snapshotService.submitDeferredCaptureAccepted(player, cause)
                        : this.snapshotService.captureAndSubmitAccepted(player, cause);
                // 关闭快照完成后结束会话, 再释放分布式锁和会话表条目.
                closing.whenComplete((result, throwable) -> {
                    if (result instanceof SnapshotService.SnapshotSaveOutcome.Completed(StorageProvider.SaveResult saveResult) && saveResult.stored()) {
                        this.plugin.handoffManager().recordSettled(session.uuid());
                    }
                    session.transition(SessionState.CLOSED);
                    this.release(session);
                });
                return CloseResult.SNAPSHOT_SUBMITTED;
            }
            // 玩家还没有完成同步, 这时退出不回写数据.
            session.transition(SessionState.CLOSED);
            this.release(session);
            return CloseResult.RELEASED_UNSYNCED;
        }
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
            CloseResult result = this.close(session, SaveCause.SHUTDOWN);
            if (result == CloseResult.SNAPSHOT_SUBMITTED) submitted++;
        }
        if (submitted > 0) {
            this.logger.info(LogCategory.SAVE, LogConstants.SYNC_SHUTDOWN_SAVED, String.valueOf(submitted));
        }
    }

    /** 一次会话关闭请求的处理结果. */
    public enum CloseResult {
        SNAPSHOT_SUBMITTED, // 关闭快照已经入队, 完成后释放会话
        RELEASED_UNSYNCED, // 会话尚未完成同步, 不保存数据并直接释放
        ALREADY_CLOSING, // 会话正在保存或已经关闭, 本次请求不再处理
        STALE // 注册表已经不再持有这份会话
    }
}
