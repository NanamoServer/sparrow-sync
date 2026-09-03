package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.cluster.HandoffManager;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.event.SyncCompleteEvent;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataStoragePatch;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.util.EventUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 玩家会话的注册表与生命周期编排入口. */
public final class SessionManager {
    private final SparrowSync plugin;
    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private SnapshotService snapshots;
    private SessionLock sessionLock;
    private HandoffManager handoffs;
    private SyncLogger logger;
    private PlayerDataStoragePatch playerDataStorage;
    private volatile boolean accepting = true;

    public SessionManager(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
        this.snapshots = this.plugin.snapshotService();
        this.sessionLock = this.plugin.sessionLock();
        this.handoffs = this.plugin.handoffManager();
        this.logger = this.plugin.logger();
    }

    public void onDelayedEnable() {
        Bukkit.getPluginManager().registerEvents(new SessionListener(this.plugin, this), this.plugin.javaPlugin());
    }

    public void injectPlayerDataStorage(@NotNull String version) {
        this.playerDataStorage = BukkitProxy.injectPlayerDataStorage(version, this.sessions);
    }

    @Nullable
    public synchronized PlayerSession tryOpen(@NotNull UUID player, @NotNull String playerName) {
        if (!this.accepting) return null;
        PlayerSession session = new PlayerSession(player, playerName);
        return this.sessions.putIfAbsent(player, session) == null ? session : null;
    }

    /** 并行读取原版玩家数据和远端快照, 远端成功且本地已得到结果后放行 Gate. */
    @NotNull
    public CompletableFuture<SessionPrepareResult> prepare(@NotNull PlayerSession session) {
        CompletableFuture<PlayerDataPreload> playerData;
        CompletableFuture<SnapshotLoadResult> snapshot;
        synchronized (session) {
            if (!this.owns(session) || session.state() != SessionState.PREPARING) {
                return CompletableFuture.completedFuture(new SessionPrepareResult.Rejected());
            }
            // 原版 .dat 与远端快照在同一 Session 准备窗口内并行读取.
            long playerDataLoadStart = System.nanoTime();
            playerData = CompletableFuture
                    .supplyAsync(() -> this.playerDataStorage.loadOriginal(session.uuid(), session.playerName()), this.plugin.scheduler().async())
                    .handle((loaded, throwable) -> {
                        String loadMillis = String.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - playerDataLoadStart));
                        // 读取本地数据失败
                        if (throwable != null) {
                            String detail = String.valueOf(throwable);
                            this.logger.warnWithFileCause(LogCategory.APPLY, session.uuid(), session.playerName(), throwable, LogConstants.SYNC_LOCAL_DATA_FALLBACK, session.playerName(), loadMillis, detail);
                            return new PlayerDataPreload.Fallback();
                        }
                        // 正常读取成功
                        this.logger.file(LogCategory.APPLY, session.uuid(), session.playerName(), loaded.isPresent() ? LogConstants.SYNC_LOCAL_DATA_READY : LogConstants.SYNC_LOCAL_DATA_EMPTY, session.playerName(), loadMillis);
                        return new PlayerDataPreload.Ready(loaded);
                    });
            snapshot = this.snapshots
                    .loadLatest(session.uuid(), session.playerName())
                    .exceptionally(throwable -> new SnapshotLoadResult.Failed(String.valueOf(throwable)));
        }

        // 两份结果齐全后重验 Session, 再一次发布进入世界需要的数据.
        return playerData.thenCombine(snapshot, (local, remote) -> {
            synchronized (session) {
                if (!this.owns(session) || session.state() != SessionState.PREPARING) {
                    return new SessionPrepareResult.Rejected();
                }
                if (remote instanceof SnapshotLoadResult.Failed(String detail)) {
                    PlayerDataState failed = session.failPlayerData(detail);
                    return new SessionPrepareResult.Failed(failed instanceof PlayerDataState.Failed(String failure) ? failure : detail);
                }
                PlayerDataState published = session.publishPlayerData(local);
                if (published instanceof PlayerDataState.Failed(String detail)) {
                    return new SessionPrepareResult.Failed(detail);
                }
                if (!(published instanceof PlayerDataState.Ready)) {
                    return new SessionPrepareResult.Failed("player data cache no longer accepts preload results");
                }
                if (remote instanceof SnapshotLoadResult.Ready ready) session.loadedSnapshot(ready);
                return new SessionPrepareResult.Ready();
            }
        });
    }

    /**
     * 把登录阶段取得的分布式锁交给会话.
     * 会话已经结束时立即归还这把迟到的锁.
     */
    public void lockAcquired(@NotNull PlayerSession session, @NotNull String lockToken) {
        this.handoffs.clearSettled(session.uuid());
        boolean release;
        synchronized (session) {
            release = !this.owns(session) || session.state() == SessionState.CLOSED;
            if (!release) session.lockToken(lockToken);
        }
        if (release) this.sessionLock.release(session.uuid(), lockToken);
    }

    /** 在玩家线程消费登录阶段的数据并激活会话. */
    @NotNull
    SnapshotApplyResult activate(@NotNull PlayerSession session, @NotNull Player player) {
        SnapshotLoadResult.Ready loaded;
        synchronized (session) {
            if (!this.owns(session) || !session.tryTransition(SessionState.PREPARING, SessionState.APPLYING)) {
                return new SnapshotApplyResult.Rejected();
            }
            // Join 验收后 Session 解除对原版数据的引用.
            PlayerDataState playerDataState = session.finishPlayerData();
            // todo 多语言日志
            String playerDataFailure = switch (playerDataState) {
                case PlayerDataState.Ready ready -> ready.loads() == 0 ? "player data cache was not read before PlayerJoinEvent" : null;
                case PlayerDataState.Failed failed -> failed.detail();
                case PlayerDataState.Preloading ignored -> "player data cache was still preloading at PlayerJoinEvent";
                case PlayerDataState.Cleared ignored -> "player data cache was cleared before PlayerJoinEvent";
            };
            if (playerDataFailure != null) return new SnapshotApplyResult.Failed(playerDataFailure);
            loaded = session.takeLoadedSnapshot();
        }
        if (loaded == null) {
            synchronized (session) {
                if (!this.owns(session) || !session.tryTransition(SessionState.APPLYING, SessionState.ACTIVE)) {
                    return new SnapshotApplyResult.Rejected();
                }
            }
            return new SnapshotApplyResult.Applied(List.of(), List.of());
        }

        SnapshotApplyResult result = this.snapshots.apply(player, loaded);
        if (result instanceof SnapshotApplyResult.Applied applied) {
            synchronized (session) {
                if (!this.owns(session) || session.state() != SessionState.APPLYING) {
                    return new SnapshotApplyResult.Rejected();
                }
                session.retainedData(loaded.data().passthrough());
                session.transition(SessionState.ACTIVE);
            }
            EventUtils.fireAndForget(new SyncCompleteEvent(player, loaded.snapshot(), applied.applied(), applied.skipped()));
        }
        return result;
    }

    /**
     * 立即采集 ACTIVE 会话的当前状态, 后续阶段进入玩家串行线程.
     * 会话已经封口或失效时不接纳并返回 null.
     */
    @Nullable
    public CompletableFuture<SnapshotSaveResult> captureNowAndSave(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        synchronized (session) {
            if (!this.accepting || !this.owns(session) || session.state() != SessionState.ACTIVE) return null;
            return this.snapshots.captureNowAndSave(player, cause, session.retainedData());
        }
    }

    /**
     * 把 ACTIVE 会话的采集和后续阶段一起提交到玩家串行线程.
     * 会话已经封口或失效时不接纳并返回 null.
     */
    @Nullable
    public CompletableFuture<SnapshotSaveResult> captureLaterAndSave(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        synchronized (session) {
            if (!this.accepting || !this.owns(session) || session.state() != SessionState.ACTIVE) return null;
            return this.snapshots.captureLaterAndSave(player, cause, session.retainedData());
        }
    }

    /** 读取并在玩家拥有线程应用最新快照, 供恢复命令等即时场景使用. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restoreLatest(@NotNull Player player) {
        PlayerSession session = this.find(player.getUniqueId());
        if (session == null) return CompletableFuture.completedFuture(new SnapshotRestoreResult.Gone());
        synchronized (session) {
            if (!this.owns(session) || session.state() != SessionState.ACTIVE) {
                return CompletableFuture.completedFuture(new SnapshotRestoreResult.Gone());
            }
        }
        return this.snapshots.loadLatest(player.getUniqueId(), player.getName()).thenCompose(result -> switch (result) {
            case SnapshotLoadResult.Empty ignored -> CompletableFuture.completedFuture(new SnapshotRestoreResult.Empty());
            case SnapshotLoadResult.Failed failed -> CompletableFuture.completedFuture(new SnapshotRestoreResult.Failed(failed.detail()));
            case SnapshotLoadResult.Ready ready -> this.applyRestored(session, player, ready);
        });
    }

    private CompletableFuture<SnapshotRestoreResult> applyRestored(PlayerSession session, Player player, SnapshotLoadResult.Ready loaded) {
        CompletableFuture<SnapshotRestoreResult> completion = new CompletableFuture<>();
        player.getScheduler().run(this.plugin.javaPlugin(), task -> {
            synchronized (session) {
                if (!this.owns(session) || session.state() != SessionState.ACTIVE) {
                    completion.complete(new SnapshotRestoreResult.Gone());
                    return;
                }
            }
            try {
                switch (this.snapshots.apply(player, loaded)) {
                    case SnapshotApplyResult.Applied applied -> {
                        synchronized (session) {
                            if (this.owns(session) && session.state() == SessionState.ACTIVE) {
                                session.retainedData(loaded.data().passthrough());
                            }
                        }
                        EventUtils.fireAndForget(new SyncCompleteEvent(player, loaded.snapshot(), applied.applied(), applied.skipped()));
                        completion.complete(new SnapshotRestoreResult.Applied(applied.applied().size(), applied.skipped().size()));
                    }
                    case SnapshotApplyResult.Failed failed -> completion.complete(new SnapshotRestoreResult.Failed(failed.detail()));
                    case SnapshotApplyResult.Rejected ignored -> completion.complete(new SnapshotRestoreResult.Gone());
                }
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        }, () -> completion.complete(new SnapshotRestoreResult.Gone()));
        return completion;
    }

    /** 作废尚未激活的会话, 不保存半加载数据. */
    public boolean abort(@NotNull PlayerSession session) {
        synchronized (session) {
            if (!this.owns(session)) return false;
            SessionState state = session.state();
            if (state == SessionState.ACTIVE || state == SessionState.SAVING || state == SessionState.CLOSED) return false;
            session.transition(SessionState.CLOSED);
            session.finishPlayerData();
            this.releaseSession(session);
            return true;
        }
    }

    /** 封口玩家会话, 并在退出所在 Region 的下一 tick 提交最终快照. */
    public void disconnect(@NotNull PlayerSession session, @NotNull Player player) {
        CloseAction action = this.beginClose(session);
        if (action == CloseAction.IGNORED) return;
        // Region 定位取自 Quit 事件线程, 下一 tick 严格排在原版退出调用栈之后.
        Location location = player.getLocation();
        this.plugin.scheduler().sync().runLater(() -> {
            if (action == CloseAction.SAVE_ACCEPTED) {
                this.closeAfterSave(session, this.snapshots.captureLaterAndSave(player, SaveCause.DISCONNECT, session.retainedData()));
            } else {
                this.releaseSession(session);
            }
        }, 1, player.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        if (action == CloseAction.ABORTED) {
            this.logger.file(LogCategory.SAVE, player.getUniqueId(), player.getName(), LogConstants.SYNC_SAVE_SKIPPED_UNSYNCED, player.getName());
        }
    }

    // 关服路径没有可等待的下一 Region tick, 立即启动最终保存.
    CloseAction closeWithFinalSave(PlayerSession session, Supplier<CompletableFuture<SnapshotSaveResult>> finalSave) {
        CloseAction action = this.beginClose(session);
        if (action == CloseAction.SAVE_ACCEPTED) {
            this.closeAfterSave(session, finalSave.get());
        } else if (action == CloseAction.ABORTED) {
            this.releaseSession(session);
        }
        return action;
    }

    // 封口与任务启动分离, Quit 事件返回前就停止接纳该玩家的新保存.
    private CloseAction beginClose(PlayerSession session) {
        synchronized (session) {
            if (!this.owns(session)) return CloseAction.IGNORED;
            SessionState state = session.state();
            if (state == SessionState.SAVING || state == SessionState.CLOSED) return CloseAction.IGNORED;
            if (state == SessionState.ACTIVE) {
                session.transition(SessionState.SAVING);
                return CloseAction.SAVE_ACCEPTED;
            }
            session.transition(SessionState.CLOSED);
            session.finishPlayerData();
            return CloseAction.ABORTED;
        }
    }

    // 最终保存完成后记录成功写入的 handoff, 再结束 Session 并进入锁释放流程.
    private void closeAfterSave(PlayerSession session, CompletableFuture<SnapshotSaveResult> finalSave) {
        finalSave.whenComplete((result, throwable) -> {
            if (result instanceof SnapshotSaveResult.Settled settled && settled.result().stored()) {
                this.handoffs.recordSettled(session.uuid());
            }
            synchronized (session) {
                session.transition(SessionState.CLOSED);
                this.releaseSession(session);
            }
        });
    }

    // Redis 释放尝试完成后再摘注册表, released 对等待方代表旧锁已经处理完毕.
    private void releaseSession(PlayerSession session) {
        String lockToken = session.lockToken();
        if (lockToken != null) {
            this.sessionLock
                    .release(session.uuid(), lockToken)
                    .whenComplete((deleted, throwable) -> {
                        this.logger.file(LogCategory.LOCK, session.uuid(), session.playerName(), LogConstants.LOCK_RELEASED, session.playerName(), throwable != null ? "failed" : String.valueOf(deleted));
                        // released 在注册表摘除后完成, 等待方此时才可创建下一代 Session.
                        this.sessions.remove(session.uuid(), session);
                        session.released().complete(null);
                    });
            return;
        }
        // released 在注册表摘除后完成, 等待方此时才可创建下一代 Session.
        this.sessions.remove(session.uuid(), session);
        session.released().complete(null);
    }

    @Nullable
    public PlayerSession find(@NotNull UUID player) {
        return this.sessions.get(player);
    }

    public int size() {
        return this.sessions.size();
    }

    /** 关服时为 ACTIVE 会话立即采集最终状态, 其余半加载会话直接作废. */
    public void shutdown() {
        // tryOpen 与这里共享 manager 监视器, 先完成接纳封口再遍历已有会话
        synchronized (this) {
            this.accepting = false;
        }
        int accepted = 0;
        for (PlayerSession session : this.sessions.values()) {
            Player player = Bukkit.getPlayer(session.uuid());
            if (player == null) {
                this.abort(session);
                continue;
            }
            CloseAction action = this.closeWithFinalSave(session, () -> this.snapshots.captureNowAndSave(player, SaveCause.SHUTDOWN, session.retainedData()));
            if (action == CloseAction.SAVE_ACCEPTED) accepted++;
        }
        if (accepted > 0) {
            this.logger.info(LogCategory.SAVE, LogConstants.SYNC_SHUTDOWN_SAVED, String.valueOf(accepted));
        }
    }

    private boolean owns(PlayerSession session) {
        return this.sessions.get(session.uuid()) == session;
    }

    // beginClose 时对会话状态的裁定, 调用方据此选择保存、延迟释放或结束处理.
    enum CloseAction {
        SAVE_ACCEPTED,
        ABORTED,
        IGNORED
    }
}
