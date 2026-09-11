package net.momirealms.sparrow.sync.session;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.momirealms.sparrow.sync.cluster.HandoffManager;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.event.SyncCompleteEvent;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataStoragePatch;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.SnapshotService;
import net.momirealms.sparrow.sync.snapshot.operation.SessionPrepareResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotApplyResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotLoadResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.util.EventUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class SessionManager {
    private final SparrowSync plugin;
    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private SnapshotService snapshotService;
    private SessionLock sessionLock;
    private HandoffManager handoffs;
    private SyncLogger logger;
    private PlayerDataStoragePatch playerDataStorage;
    private volatile boolean accepting = true;

    public SessionManager(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
        this.snapshotService = this.plugin.snapshotService();
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
    public synchronized PlayerSession tryOpen(@NotNull UUID player, @NotNull String playerName, @NotNull Connection connection) {
        if (!this.accepting) return null;
        PlayerSession session = new PlayerSession(player, playerName, connection);
        return this.sessions.putIfAbsent(player, session) == null ? session : null;
    }

    /** 并行读取原版玩家数据和远端快照, 远端成功且本地已得到结果后放行 Gate. */
    @NotNull
    public CompletableFuture<SessionPrepareResult> prepare(@NotNull PlayerSession session) {
        // 迟到的锁结果不再为已经关闭的会话启动读取
        if (session.state() != SessionState.PREPARING) {
            return CompletableFuture.completedFuture(SessionPrepareResult.REJECTED);
        }
        long loadStart = System.nanoTime();
        // 原版 .dat 与远端快照在同一 Session 准备窗口内并行读取
        CompletableFuture<PlayerDataPreload> playerData = CompletableFuture
                .supplyAsync(() -> this.playerDataStorage.loadOriginal(session.uuid(), session.playerName()), this.plugin.scheduler().async())
                .handle((loaded, throwable) -> {
                    String loadMillis = millis(loadStart, System.nanoTime());
                    // 读取本地数据失败
                    if (throwable != null) {
                        String detail = String.valueOf(throwable);
                        this.logger.warnWithFileCause(LogCategory.APPLY, session.uuid(), session.playerName(), throwable, LogConstants.SYNC_LOCAL_DATA_FALLBACK, session.playerName(), loadMillis, detail);
                        return PlayerDataPreload.FALLBACK;
                    }
                    // 正常读取成功
                    this.logger.file(LogCategory.APPLY, session.uuid(), session.playerName(), loaded.isPresent() ? LogConstants.SYNC_LOCAL_DATA_READY : LogConstants.SYNC_LOCAL_DATA_EMPTY, session.playerName(), loadMillis);
                    return new PlayerDataPreload.Ready(loaded);
                });
        CompletableFuture<SnapshotLoadResult> snapshot = this.snapshotService
                .loadLatest(session.uuid(), session.playerName())
                .exceptionally(throwable -> new SnapshotLoadResult.Failed(String.valueOf(throwable)));

        // 两份结果齐全后在异步线程生成最终登录 NBT, 再检查 Session 状态并设置会话加载结果
        return playerData.thenCombineAsync(snapshot, (local, remote) -> {
            long asyncReadNanos = System.nanoTime() - loadStart;
            // 已结束的登录不再执行登录数据转换
            if (session.state() != SessionState.PREPARING) {
                return SessionPrepareResult.REJECTED;
            }
            SnapshotLoadResult.Ready loadedSnapshot = remote instanceof SnapshotLoadResult.Ready ready ? ready : null;
            PlayerDataPreload preparedLocal = local;
            long nativeApplyNanos = 0L;
            // 如果开启了 nativeApply, 则进行修改
            boolean nativeApply = PluginConfig.synchronization$nativeAsyncApply().playerData() || PluginConfig.synchronization$nativeAsyncApply().advancements() || PluginConfig.synchronization$nativeAsyncApply().statistics();
            if (nativeApply && loadedSnapshot != null) {
                Optional<CompoundTag> localData = local instanceof PlayerDataPreload.Ready(Optional<CompoundTag> data) ? data : Optional.empty();
                long nativeApplyStart = System.nanoTime();
                preparedLocal = new PlayerDataPreload.Ready(this.snapshotService.applyNative(session, localData, loadedSnapshot));
                nativeApplyNanos = System.nanoTime() - nativeApplyStart;
            }
            // 设置会话加载结果与 abort 共用 Session 监视器, 本服登录数据和快照一起提交
            synchronized (session) {
                if (session.state() != SessionState.PREPARING) {
                    return SessionPrepareResult.REJECTED;
                }
                // 检查快照和本地数据读取是否成功
                if (remote instanceof SnapshotLoadResult.Failed(String detail)) {
                    LoginDataState failed = session.failLoginData(detail);
                    return new SessionPrepareResult.Failed(failed instanceof LoginDataState.Failed(String failure) ? failure : detail);
                }
                // 设置会话加载结果并检查结果状态
                LoginDataState published = session.publishLoginData(preparedLocal, loadedSnapshot, asyncReadNanos, nativeApplyNanos);
                if (published instanceof LoginDataState.Failed(String detail)) {
                    return new SessionPrepareResult.Failed(detail);
                }
                if (!(published instanceof LoginDataState.Ready)) {
                    return new SessionPrepareResult.Failed("player data cache no longer accepts preload results");
                }
                return SessionPrepareResult.READY;
            }
        }, this.plugin.scheduler().async());
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
        long asyncReadNanos;
        long nativeApplyNanos;
        synchronized (session) {
            if (!this.owns(session) || !session.tryTransition(SessionState.PREPARING, SessionState.APPLYING)) {
                return SnapshotApplyResult.REJECTED;
            }
            // Join 验收后 Session 解除对原版数据的引用.
            LoginDataState loginDataState = session.finishLoginData();
            switch (loginDataState) {
                case LoginDataState.Ready ready -> {
                    if (ready.loads() == 0) return new SnapshotApplyResult.Failed("player data cache was not read before PlayerJoinEvent");
                    loaded = ready.snapshot();
                    asyncReadNanos = ready.asyncReadNanos();
                    nativeApplyNanos = ready.nativeApplyNanos();
                }
                case LoginDataState.Failed failed -> {return new SnapshotApplyResult.Failed(failed.detail());}
                case LoginDataState.Preloading ignored -> {return new SnapshotApplyResult.Failed("player data cache was still preloading at PlayerJoinEvent");}
                case LoginDataState.Cleared ignored -> {return new SnapshotApplyResult.Failed("player data cache was cleared before PlayerJoinEvent");}
            }
        }
        // 应用数据
        SnapshotApplyResult result;
        long syncApplyNanos = 0L;
        if (loaded == null) {
            result = new SnapshotApplyResult.Applied(List.of(), List.of(), List.of());
        } else {
            long syncApplyStart = System.nanoTime();
            result = this.snapshotService.applyOnJoin(player, loaded);
            syncApplyNanos = System.nanoTime() - syncApplyStart;
        }
        if (result instanceof SnapshotApplyResult.Applied applied) {
            synchronized (session) {
                if (!this.owns(session) || session.state() != SessionState.APPLYING) {
                    return SnapshotApplyResult.REJECTED;
                }
                if (loaded != null) session.retainedData(loaded.context().passthrough());
                session.transition(SessionState.ACTIVE);
            }
            if (loaded != null) EventUtils.fireAndForget(new SyncCompleteEvent(player, loaded.snapshot(), applied.applied(), applied.skipped()));
            this.logger.info(LogCategory.JOIN, player.getUniqueId(), player.getName(), LogConstants.SYNC_LOGIN_COMPLETE, player.getName(), millis(0, asyncReadNanos), millis(0, nativeApplyNanos), millis(0, syncApplyNanos));
        }
        return result;
    }

    /**
     * 立即采集 ACTIVE 会话的当前状态, 后续阶段进入玩家串行线程.
     * 会话已停止接受新保存请求或已失效时返回 null.
     */
    @Nullable
    public CompletableFuture<SnapshotSaveResult> captureNowAndSave(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        synchronized (session) {
            if (!this.accepting || !this.owns(session) || session.state() != SessionState.ACTIVE) return null;
            return this.snapshotService.captureNowAndSave(player, cause, session.retainedData());
        }
    }

    /**
     * 在玩家线程接受分阶段采集保存请求, 玩家线程采集组采完后提交串行任务.
     * 会话已停止接受新保存请求或已失效时返回 null.
     */
    @Nullable
    public CompletableFuture<SnapshotSaveResult> captureLaterAndSave(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        synchronized (session) {
            if (!this.accepting || !this.owns(session) || session.state() != SessionState.ACTIVE) return null;
            return this.snapshotService.captureLaterAndSave(player, cause, session.retainedData());
        }
    }

    /** 作废尚未激活的会话, 不保存半加载数据. */
    public boolean abort(@NotNull PlayerSession session) {
        synchronized (session) {
            if (!this.owns(session)) return false;
            SessionState state = session.state();
            if (state == SessionState.ACTIVE || state == SessionState.SAVING || state == SessionState.CLOSED) return false;
            session.transition(SessionState.CLOSED);
            session.finishLoginData();
            this.releaseSession(session);
            return true;
        }
    }

    /** 停止接受该玩家的新保存请求, 并在退出所在 Region 的下一 tick 提交最终快照. */
    public void disconnect(@NotNull PlayerSession session, @NotNull Player player) {
        CloseAction action = this.beginClose(session);
        if (action == CloseAction.IGNORED) return;
        // Region 定位取自 Quit 事件线程, 下一 tick 严格排在原版退出调用栈之后.
        Location location = player.getLocation();
        this.plugin.scheduler().sync().runLater(() -> {
            if (action == CloseAction.SAVE_ACCEPTED) {
                this.closeAfterSave(session, this.snapshotService.captureLogoutAndSave(player, SaveCause.DISCONNECT, session.retainedData()));
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

    // 停止接受新保存请求与任务启动分开执行, Quit 事件返回前就停止接受该玩家的新保存.
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
            session.finishLoginData();
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

    @NotNull
    public Map<UUID, String> onlinePlayers() {
        Map<UUID, String> players = new HashMap<>();
        for (PlayerSession session : this.sessions.values()) {
            if (session.state() == SessionState.ACTIVE) {
                players.put(session.uuid(), session.playerName());
            }
        }
        return players;
    }

    /** 关服时为 ACTIVE 会话立即采集最终状态, 其余半加载会话直接作废. */
    public void shutdown() {
        // tryOpen 与这里共享 manager 监视器, 先停止接受新请求再遍历已有会话
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
            CloseAction action = this.closeWithFinalSave(session, () -> this.snapshotService.captureNowAndSave(player, SaveCause.SHUTDOWN, session.retainedData()));
            if (action == CloseAction.SAVE_ACCEPTED) accepted++;
        }
        if (accepted > 0) {
            this.logger.info(LogCategory.SAVE, LogConstants.SYNC_SHUTDOWN_SAVED, String.valueOf(accepted));
        }
    }

    private boolean owns(PlayerSession session) {
        return this.sessions.get(session.uuid()) == session;
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }

    // beginClose 时对会话状态的裁定, 调用方据此选择保存、延迟释放或结束处理.
    enum CloseAction {
        SAVE_ACCEPTED,
        ABORTED,
        IGNORED
    }
}
