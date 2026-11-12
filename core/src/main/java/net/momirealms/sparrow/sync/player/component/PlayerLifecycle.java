package net.momirealms.sparrow.sync.player.component;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.momirealms.sparrow.sync.api.event.PlayerDataReadyEvent;
import net.momirealms.sparrow.sync.api.event.SyncCompleteEvent;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.player.data.LoginDataState;
import net.momirealms.sparrow.sync.player.data.PlayerDataPreload;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataStoragePatch;
import net.momirealms.sparrow.sync.snapshot.SnapshotService;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.operation.SessionPrepareResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotApplyResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotLoadResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.util.EventUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 登记会话 -> 取锁 -> 读取并预应用登录数据 -> Join 应用 -> 在线 -> Quit 最终快照 -> 放锁
 */
@ApiStatus.Internal
public final class PlayerLifecycle implements Listener {
    static final int CLOSED = 1;       // 断线, 退出, 登录被拒或关服已结束本次会话
    static final int WRITES_DONE = 2;  // 未进入在线阶段, 或最终快照已有结果
    static final int PREPARING = 4;    // 取锁和登录数据准备仍在进行
    static final int APPLYING = 8;     // Join 阶段的数据应用仍在进行
    static final int ACTIVE = 16;      // 登录数据已全部应用, 接受采集和在线恢复
    static final int ENDED = 32;       // 已开始最终释放, 后续信号不能重复释放

    private final SparrowSync plugin = SparrowSync.instance();
    private final PlayerSessionLock lock = new PlayerSessionLock();
    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>(); // 断线后保留到保存和解锁结束
    private SnapshotService snapshotService;
    private SyncLogger logger;
    private PlayerDataStoragePatch playerDataStorage;
    private volatile boolean closing; // 关服开始后停止接纳连接和在线采集

    public void onLoad() {
        this.snapshotService = this.plugin.snapshotService();
        this.logger = this.plugin.logger();
        this.lock.onLoad();
    }

    public void onDelayedEnable() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin.javaPlugin());
    }

    public void injectPlayerDataStorage(@NotNull String version) {
        this.playerDataStorage = BukkitProxy.injectPlayerDataStorage(version, this.sessions);
    }

    /* onPrepare */

    /**
     * 为配置阶段的连接开始一次会话, 门控按结果放行或断开.
     *
     * @param uuid 登录玩家
     * @param name 登录名称
     * @param connection 本次连接
     * @return 门控结果, 等待旧会话, 取锁和数据准备共用登录超时预算
     */
    @NotNull
    public CompletableFuture<SessionPrepareResult> login(@NotNull UUID uuid, @NotNull String name, @NotNull Connection connection) {
        int budget = Math.max(1, PluginConfig.synchronization$loginTimeoutSeconds());
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(budget);
        PlayerSession previous = this.sessions.get(uuid);
        CompletableFuture<Void> vacated;
        if (previous == null) {
            vacated = CompletableFuture.completedFuture(null);
        } else if ((previous.state.bits.get() & CLOSED) != 0) {
            // 旧会话正在保存或收尾, 等它释放后再登记.
            vacated = previous.state.ended.thenApply(ignored -> null);
        } else {
            return CompletableFuture.completedFuture(this.busy(uuid, name, "previous session is still " + previous.getPhase()));
        }
        AtomicReference<PlayerSession> opened = new AtomicReference<>();
        return vacated
                .<SessionPrepareResult>thenCompose(ignored -> {
                    if (System.nanoTime() >= deadlineNanos) return CompletableFuture.completedFuture(SessionPrepareResult.REJECTED);
                    PlayerSession session = this.open(uuid, name, connection);
                    if (session == null) return CompletableFuture.completedFuture(this.busy(uuid, name, "another connection won session registration"));
                    opened.set(session);
                    return this.prepare(session, deadlineNanos);
                })
                // 超时只完成门控这条派生链, 收尾仍等待原始数据准备真正结束.
                .orTimeout(budget, TimeUnit.SECONDS)
                .handle((result, failure) -> {
                    if (failure != null) {
                        result = new SessionPrepareResult.Failed(failure instanceof TimeoutException ? "player data preparation timed out" : String.valueOf(failure));
                    }
                    if (result instanceof SessionPrepareResult.Failed(String detail)) {
                        PlayerSession session = opened.get();
                        if (session != null) this.abort(session);
                        this.logger.error(LogCategory.KICK, uuid, name, LogConstants.GATE_KICKED, name, detail);
                    }
                    return result;
                });
    }

    private SessionPrepareResult busy(UUID uuid, String name, String reason) {
        this.logger.file(LogCategory.KICK, uuid, name, LogConstants.GATE_KICKED, name, reason);
        return new SessionPrepareResult.Busy(reason);
    }

    // 登记会话并订阅 Channel 关闭, 与关服停止接纳共用实例锁.
    @Nullable
    private synchronized PlayerSession open(UUID uuid, String name, Connection connection) {
        if (this.closing) return null;
        PlayerSession session = new PlayerSession(uuid, name, connection);
        if (this.sessions.putIfAbsent(uuid, session) != null) return null;
        // 配置阶段断线也提交关闭信号, 已关闭的 Channel 会立即触发.
        connection.channel.closeFuture().addListener(future -> this.abort(session));
        return session;
    }

    // 持锁读取登录数据并完成 native 应用, 结果供原版登录读取.
    private CompletableFuture<SessionPrepareResult> prepare(PlayerSession session, long deadlineNanos) {
        if (this.transition(session, CLOSED, 0, PREPARING, 0) < 0) {
            return CompletableFuture.completedFuture(SessionPrepareResult.REJECTED);
        }
        // 名字记录与取锁并行, 放行前等待它结束.
        CompletableFuture<Void> userReady = CompletableFuture.completedFuture(session).thenCompose(this::updatePlayerName);
        CompletableFuture<SessionPrepareResult> preparation = CompletableFuture.completedFuture(session)
                .thenCompose(current -> this.lock.acquire(current, deadlineNanos))
                .thenCompose(ignored -> this.loadLoginData(session))
                .thenCombine(userReady, (prepared, ignored) -> prepared);
        preparation.whenComplete((ignored, failure) -> this.transition(session, 0, 0, 0, PREPARING));
        return preparation;
    }

    // 名字记录失败沿用登录可继续的约定, 本地目录只记住成功写入的名字.
    private CompletableFuture<Void> updatePlayerName(PlayerSession session) {
        return this.plugin.storageProvider().ensureUser(session.uuid(), session.playerName()).handle((ignored, failure) -> {
            if (failure != null) {
                this.logger.file(LogCategory.STORAGE, session.uuid(), session.playerName(), failure, LogConstants.SYNC_USER_FAILED, session.playerName());
            } else {
                this.plugin.playerDirectory().remember(session.uuid(), session.playerName());
            }
            return null;
        });
    }

    private CompletableFuture<SessionPrepareResult> loadLoginData(PlayerSession session) {
        if ((session.state.bits.get() & CLOSED) != 0) return CompletableFuture.completedFuture(SessionPrepareResult.REJECTED);
        // 本地 .dat 与远端快照并行读取.
        long loadStart = System.nanoTime();
        CompletableFuture<PlayerDataPreload> local = this.readLocalPlayerData(session, loadStart);
        CompletableFuture<SnapshotLoadResult> remote = this.snapshotService.loadLatest(session.uuid(), session.playerName())
                .exceptionally(failure -> new SnapshotLoadResult.Failed(String.valueOf(failure)));
        // native 写入排在本玩家已有串行任务之后, 完成后再发布原版读取入口.
        return local.thenCombineAsync(remote, (playerData, snapshot) -> {
            long readNanos = System.nanoTime() - loadStart;
            if ((session.state.bits.get() & CLOSED) != 0) return SessionPrepareResult.REJECTED;
            SnapshotLoadResult.Ready loaded = snapshot instanceof SnapshotLoadResult.Ready ready ? ready : null;
            PlayerDataPreload prepared = playerData;
            long nativeNanos = 0L;
            if (loaded != null && this.nativeApplyEnabled()) {
                Optional<CompoundTag> data = playerData instanceof PlayerDataPreload.Ready ready ? ready.data() : Optional.empty();
                long nativeStart = System.nanoTime();
                prepared = new PlayerDataPreload.Ready(this.plugin.playerDataPipeline().applyNative(session, data, loaded.context()));
                nativeNanos = System.nanoTime() - nativeStart;
            }
            return this.publishLoginData(session, prepared, snapshot, loaded, readNanos, nativeNanos);
        }, this.plugin.playerExecutor().executor(session.uuid()));
    }

    private boolean nativeApplyEnabled() {
        return PluginConfig.synchronization$nativeAsyncApply().playerData()
                || PluginConfig.synchronization$nativeAsyncApply().advancements()
                || PluginConfig.synchronization$nativeAsyncApply().statistics();
    }

    // 本地文件读取失败时提供空数据, 远端快照仍按正常流程准备.
    private CompletableFuture<PlayerDataPreload> readLocalPlayerData(PlayerSession session, long started) {
        return CompletableFuture.supplyAsync(() -> this.playerDataStorage.loadOriginal(session.uuid(), session.playerName()), this.plugin.scheduler().async())
                .handle((loaded, failure) -> {
                    String elapsed = millis(started, System.nanoTime());
                    if (failure != null) {
                        this.logger.warnWithFileCause(LogCategory.APPLY, session.uuid(), session.playerName(), failure, LogConstants.SYNC_LOCAL_DATA_FALLBACK, session.playerName(), elapsed, String.valueOf(failure));
                        return PlayerDataPreload.FALLBACK;
                    }
                    this.logger.file(LogCategory.APPLY, session.uuid(), session.playerName(), loaded.isPresent() ? LogConstants.SYNC_LOCAL_DATA_READY : LogConstants.SYNC_LOCAL_DATA_EMPTY, session.playerName(), elapsed);
                    return new PlayerDataPreload.Ready(loaded);
                });
    }

    // 早读失败和已清理的状态保留在原版读取入口, 迟到的准备结果不会覆盖它们.
    private SessionPrepareResult publishLoginData(
            PlayerSession session,
            PlayerDataPreload local,
            SnapshotLoadResult remote,
            @Nullable SnapshotLoadResult.Ready loaded,
            long readNanos,
            long nativeApplyNanos
    ) {
        PlayerLoginData loginData = session.state.loginData;
        // 远端快照读取失败时终止登录.
        if (remote instanceof SnapshotLoadResult.Failed(String detail)) {
            return new SessionPrepareResult.Failed(loginData.fail(detail) instanceof LoginDataState.Failed(String failure) ? failure : detail);
        }
        return switch (loginData.publish(local, loaded, readNanos, nativeApplyNanos)) {
            case LoginDataState.Ready ignored -> SessionPrepareResult.READY;
            case LoginDataState.Failed(String detail) -> new SessionPrepareResult.Failed(detail);
            case LoginDataState.Cleared ignored -> SessionPrepareResult.REJECTED;
            case LoginDataState.Preloading ignored -> new SessionPrepareResult.Failed("player data cache no longer accepts preload results");
        };
    }


    /* OnJoin */


    // 原版读完登录数据后, 在玩家所属线程开始剩余应用.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = this.sessions.get(player.getUniqueId());
        if (session == null) {
            this.logger.file(LogCategory.KICK, player.getUniqueId(), player.getName(), LogConstants.GATE_KICKED, player.getName(), "no session, the login gate did not cover this join");
            this.kickSyncNotReady(player);
            return;
        }
        // 跟踪器先于数据应用安装, 成就跟踪器失败会结束本次登录.
        if (!this.installTrackers(session, player)) return;
        this.logger.file(LogCategory.JOIN, player.getUniqueId(), player.getName(), LogConstants.SESSION_JOIN);
        if (this.transition(session, CLOSED | APPLYING | ACTIVE, 0, APPLYING, 0) < 0) return;
        // 接管原版已读取的准备结果, 数据写入和就绪发布全部结束后才清除 APPLYING.
        LoginDataState login = session.state.loginData.clear();
        CompletableFuture.completedFuture(login)
                .thenApply(state -> state instanceof LoginDataState.Ready ready && ready.loads() != 0
                        ? this.applyLogin(session, player, ready)
                        : this.invalidLoginData(state))
                .whenComplete((result, failure) -> {
                    this.transition(session, 0, 0, 0, APPLYING);
                    if (failure != null) {
                        this.logger.file(LogCategory.KICK, player.getUniqueId(), player.getName(), failure, LogConstants.GATE_KICKED, player.getName(), String.valueOf(failure));
                        this.kickSyncNotReady(player);
                    } else if (result instanceof SnapshotApplyResult.Failed failed) {
                        this.logger.file(LogCategory.KICK, player.getUniqueId(), player.getName(), LogConstants.GATE_KICKED, player.getName(), failed.detail());
                        this.kickSyncNotReady(player);
                    } else if (result instanceof SnapshotApplyResult.Applied) {
                        this.plugin.playerDirectory().presence(player.getUniqueId(), player.getName(), true);
                    }
                });
    }

    // 跟踪器保持各自的失败规则, 属性跟踪失败仍可通过常规采集获得数据.
    private boolean installTrackers(PlayerSession session, Player player) {
        // 注入成就缓存
        if (
                PluginConfig.synchronization$advancements().injectProgressChanged()
                && this.plugin.dataRegistry().type(AdvancementsDataType.ADVANCEMENTS) instanceof AdvancementsDataType advancements
        ) {
            try {
                advancements.injectTracker(player);
            } catch (Throwable throwable) {
                this.logger.error(LogCategory.KICK, player.getUniqueId(), player.getName(), throwable, LogConstants.DATA_ADVANCEMENT_TRACKER_INSTALL_FAILED, player.getName());
                this.abort(session);
                this.kickSyncNotReady(player);
                return false;
            }
        }
        // 注入属性缓存
        if (
                PluginConfig.synchronization$attributes().injectOnDirtyConsumer()
                && this.plugin.dataRegistry().type(AttributesDataType.ATTRIBUTES) instanceof AttributesDataType attributes
        ) {
            attributes.injectTracker(player);
        }
        return true;
    }

    // 预加载状态决定原版是否已经消费本次数据, 未成功消费时终止入服.
    private SnapshotApplyResult invalidLoginData(LoginDataState state) {
        String detail = switch (state) {
            case LoginDataState.Ready ignored -> "player data cache was not read before PlayerJoinEvent";
            case LoginDataState.Failed failed -> failed.detail();
            case LoginDataState.Preloading ignored -> "player data cache was still preloading at PlayerJoinEvent";
            case LoginDataState.Cleared ignored -> "player data cache was cleared before PlayerJoinEvent";
        };
        return new SnapshotApplyResult.Failed(detail);
    }

    // native 已完成的部分由原版加载, 此处应用剩余数据和 native 后续动作, 全部完成后才开放在线操作.
    private SnapshotApplyResult applyLogin(PlayerSession session, Player player, LoginDataState.Ready ready) {
        SnapshotLoadResult.Ready loaded = ready.snapshot();
        long applyStart = System.nanoTime();
        SnapshotApplyResult result = loaded == null
                ? new SnapshotApplyResult.Applied(List.of(), List.of(), List.of())
                : this.plugin.playerDataPipeline().applySnapshot(player, loaded);
        if (!(result instanceof SnapshotApplyResult.Applied applied)) return result;
        if (loaded != null) session.state.retainedData = loaded.context().passthrough();
        // 应用期间已断线或退出的会话拒绝迟到结果.
        if (this.transition(session, CLOSED, APPLYING, ACTIVE, APPLYING) < 0) return SnapshotApplyResult.REJECTED;
        EventUtils.fireAndForget(new PlayerDataReadyEvent(player, loaded == null ? null : loaded.snapshot(), applied.skipped()));
        if (loaded != null) EventUtils.fireAndForget(new SyncCompleteEvent(player, loaded.snapshot(), applied.applied(), applied.skipped()));
        this.logger.info(
                LogCategory.JOIN,
                player.getUniqueId(),
                player.getName(),
                LogConstants.SYNC_LOGIN_COMPLETE,
                player.getName(),
                millis(0, ready.asyncReadNanos()),
                millis(0, ready.nativeApplyNanos()),
                millis(applyStart, System.nanoTime())
        );
        return result;
    }


    private void kickSyncNotReady(Player player) {
        player.kickPlayer(LegacyComponentSerializer.legacySection().serialize(MessageConstants.KICK_SYNC_NOT_READY.build()));
    }

    // 在线会话接受即时采集, 失效或关服中的请求返回 null.
    @Nullable
    public CompletableFuture<SnapshotSaveResult> captureNowAndSave(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        if (this.closing || !session.isActive()) return null;
        return this.snapshotService.captureNowAndSave(player, cause, session.state.retainedData);
    }

    // 在线会话接受分阶段采集, 同步部分在调用线程完成.
    @Nullable
    public CompletableFuture<SnapshotSaveResult> captureLaterAndSave(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        if (this.closing || !session.isActive()) return null;
        return this.snapshotService.captureLaterAndSave(player, cause, session.state.retainedData);
    }


    /* onQuit */



    // 退出事件返回前停止接受在线保存, 最终快照在原版保存结束后采集.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = this.sessions.get(player.getUniqueId());
        if (session != null) {
            this.logger.file(LogCategory.QUIT, player.getUniqueId(), player.getName(), LogConstants.SESSION_QUIT);
            int before = this.transition(session, CLOSED, 0, CLOSED, ACTIVE);
            if (before >= 0) {
                boolean active = (before & ACTIVE) != 0;
                // 原版在退出事件返回后保存玩家文件, 使用退出所在区域的下一 tick 收尾.
                Location location = player.getLocation();
                this.plugin.scheduler().platform().runLater(() -> {
                    if (active) {
                        this.saveFinalSnapshot(session, player, SaveCause.DISCONNECT);
                    } else {
                        this.transition(session, 0, 0, WRITES_DONE, 0);
                    }
                }, 1, player.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
                if (!active) {
                    this.logger.file(LogCategory.SAVE, player.getUniqueId(), player.getName(), LogConstants.SYNC_SAVE_SKIPPED_UNSYNCED, player.getName());
                }
            }
        }
        // 最终保存和解锁仍可在进行, 玩家已经从在线名单退出.
        this.plugin.playerDirectory().presence(player.getUniqueId(), player.getName(), false);
    }

    // 普通退出使用离线采集及退出前结果, 关服在当前线程立即采集全部类型.
    private void saveFinalSnapshot(PlayerSession session, Player player, SaveCause cause) {
        CompletableFuture.completedFuture(session)
                .thenCompose(current -> cause == SaveCause.DISCONNECT
                        ? this.snapshotService.captureLogoutAndSave(player, cause, current.state.retainedData)
                        : this.snapshotService.captureNowAndSave(player, cause, current.state.retainedData))
                .whenComplete((result, failure) -> {
                    // 只有确认落库才提供交接标记, 标记先于解锁写入.
                    if (result instanceof SnapshotSaveResult.Settled settled && settled.result().stored()) {
                        this.lock.settled(session);
                    }
                    this.transition(session, 0, 0, WRITES_DONE, 0);
                });
    }

    // 作废尚未进入在线阶段的会话, 已开始的阶段结束后释放锁.
    private void abort(PlayerSession session) {
        if (this.transition(session, CLOSED | ACTIVE, 0, CLOSED | WRITES_DONE, 0) >= 0) {
            session.state.loginData.clear();
        }
    }

    /**
     * 合并阶段信号, 满足收尾条件时由一次成功的原子更新启动释放.
     *
     * @param blocked 任一位已置入时放弃本次更新
     * @param required 必须全部置入才执行本次更新
     * @param add 需要置入的状态位
     * @param remove 需要清除的状态位
     * @return 更新前的状态位, 放弃更新时为 -1
     */
    private int transition(PlayerSession session, int blocked, int required, int add, int remove) {
        AtomicInteger bits = session.state.bits;
        while (true) {
            int current = bits.get();
            if ((current & blocked) != 0 || (current & required) != required) return -1;
            int next = (current | add) & ~remove;
            // 会话已关闭且写入结束, 准备和应用均不在进行时, 本次更新取得收尾资格.
            boolean complete = (next & (CLOSED | WRITES_DONE | PREPARING | APPLYING | ENDED)) == (CLOSED | WRITES_DONE);
            if (complete) {
                next |= ENDED;
            }
            if (bits.compareAndSet(current, next)) {
                if (complete) this.finishSession(session);
                return current;
            }
        }
    }

    // 清理会话数据并解锁, 解锁尝试结束后才允许下一次登录登记.
    private void finishSession(PlayerSession session) {
        session.state.loginData.clear();
        CompletableFuture.completedFuture(session)
                .thenCompose(this.lock::release)
                .whenComplete((ignored, failure) -> {
                    this.sessions.remove(session.uuid(), session);
                    session.state.ended.complete(null);
                });
    }

    // 停止接纳新连接, 为已接纳会话补齐退出信号, 在线会话提交关服保存.
    public void shutdown() {
        synchronized (this) {
            this.closing = true;
        }
        int accepted = 0;
        for (PlayerSession session : this.sessions.values()) {
            Player player = Bukkit.getPlayer(session.uuid());
            if (player == null) {
                this.abort(session);
                continue;
            }
            int before = this.transition(session, CLOSED, 0, CLOSED, ACTIVE);
            if (before < 0) continue;
            // 关服无法等待下一 tick, 在线会话立即采集, 未就绪的会话直接结束.
            if ((before & ACTIVE) != 0) {
                this.saveFinalSnapshot(session, player, SaveCause.SHUTDOWN);
                accepted++;
            } else {
                this.transition(session, 0, 0, WRITES_DONE, 0);
            }
        }
        if (accepted > 0) {
            this.logger.info(LogCategory.SAVE, LogConstants.SYNC_SHUTDOWN_SAVED, String.valueOf(accepted));
        }
    }

    // 关服等待所有已接纳会话释放, 各会话共用同一个截止时刻.
    public boolean awaitReleases(long timeout, @NotNull TimeUnit unit) {
        List<PlayerSession> ending = List.copyOf(this.sessions.values());
        long deadline = System.nanoTime() + unit.toNanos(Math.max(0, timeout));
        try {
            for (PlayerSession session : ending) {
                session.state.ended.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException exception) {
            this.logger.warn(LogCategory.LIFECYCLE, LogConstants.SYNC_SHUTDOWN_LOCK_TIMEOUT, String.valueOf(this.sessions.size()));
            return false;
        } catch (ExecutionException exception) {
            throw new CompletionException(exception.getCause());
        }
    }

    @Nullable
    public PlayerSession find(@NotNull UUID player) {
        return this.sessions.get(player);
    }

    public int size() {
        return this.sessions.size();
    }

    // 对外名单只包含已经应用完成的本服会话.
    @NotNull
    public Map<UUID, String> onlinePlayers() {
        Map<UUID, String> players = new HashMap<>();
        for (PlayerSession session : this.sessions.values()) {
            if (session.isActive()) {
                players.put(session.uuid(), session.playerName());
            }
        }
        return players;
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.format(Locale.ROOT, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }

    /**
     * 保存一次会话的生命周期进度. 状态位由 PlayerLifecycle 原子更新.
     */
    static final class Session {
        final AtomicInteger bits = new AtomicInteger(); // 跨线程合并关闭, 写入结束, 准备和应用进度的位状态
        final PlayerLoginData loginData = new PlayerLoginData(); // 原版登录读取的准备结果
        final CompletableFuture<Void> ended = new CompletableFuture<>(); // 会话从注册表移除后完成
        volatile String lockToken; // 取锁时的完整锁值, 尚未取得时为 null, 释放时原样传回
        volatile SnapshotData retainedData = EagerSnapshotData.EMPTY; // 本服未注册的数据, 保存时原样写回
    }
}