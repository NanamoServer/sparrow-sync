package net.momirealms.sparrow.sync.cluster;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

public final class HandoffManager {
    private static final long PROBE_INTERVAL_MILLIS = 500;   // 探测周期, 兼作存活采样率
    private static final long PROBE_TIMEOUT_MILLIS = 500;    // 单次探测的应答等待
    private static final long DEAD_SILENCE_MILLIS = 3000;    // 连续静默判死阈值

    private SparrowSync plugin;
    private MessageBroker<ByteBuf> broker;
    private SessionLock lock;
    private Predicate<UUID> hasSession;
    private ProbeScheduler scheduler;
    private final long probeIntervalMillis;
    private final long probeTimeoutMillis;
    private final long deadSilenceMillis;
    private final Cache<UUID, Boolean> settled = Caffeine.newBuilder().expireAfterWrite(30_000, TimeUnit.MILLISECONDS).build(); // 最近完成退出保存的玩家

    public HandoffManager(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
        this.probeIntervalMillis = PROBE_INTERVAL_MILLIS;
        this.probeTimeoutMillis = PROBE_TIMEOUT_MILLIS;
        this.deadSilenceMillis = DEAD_SILENCE_MILLIS;
    }

    public HandoffManager(
            @NotNull MessageBroker<ByteBuf> broker,
            @NotNull SessionLock lock,
            @NotNull Predicate<UUID> hasSession,
            @NotNull ProbeScheduler scheduler
    ) {
        this(broker, lock, hasSession, scheduler, PROBE_INTERVAL_MILLIS, PROBE_TIMEOUT_MILLIS, DEAD_SILENCE_MILLIS);
    }

    HandoffManager(
            @NotNull MessageBroker<ByteBuf> broker,
            @NotNull SessionLock lock,
            @NotNull Predicate<UUID> hasSession,
            @NotNull ProbeScheduler scheduler,
            long probeIntervalMillis,
            long probeTimeoutMillis,
            long deadSilenceMillis
    ) {
        this.broker = broker;
        this.lock = lock;
        this.hasSession = hasSession;
        this.scheduler = scheduler;
        this.probeIntervalMillis = probeIntervalMillis;
        this.probeTimeoutMillis = probeTimeoutMillis;
        this.deadSilenceMillis = deadSilenceMillis;
        HandoffRequestMessage.service(this);
    }

    /** 绑定集群依赖并安装交接消息处理器. */
    public void onLoad() {
        this.broker = this.plugin.messageBrokerManager().broker();
        this.lock = this.plugin.sessionLock();
        this.hasSession = uuid -> this.plugin.sessionManager().find(uuid) != null;
        this.scheduler = (task, delayMillis) -> this.plugin.scheduler().asyncLater(task, delayMillis, TimeUnit.MILLISECONDS);
        HandoffRequestMessage.service(this);
    }

    // ===== 持有服的应答与记录 =====

    /**
     * 回答一次交接探测, 在消息消费线程上执行.
     * 只读内存状态, <strong>不得阻塞在保存或任何 IO 上</strong>.
     */
    @NotNull
    public HandoffResponseMessage answer(@NotNull UUID player) {
        // 会话还在注册表里就是没走完, 含 CLOSED 后 remove 前的微窗口, 让对方下一轮再问.
        if (this.hasSession.test(player)) return HandoffResponseMessage.saving();
        if (this.settled.getIfPresent(player) != null) return HandoffResponseMessage.done();
        return HandoffResponseMessage.unknown();
    }

    /** 登记已成功落库的退出保存, <strong>必须先于释放会话锁调用</strong>. */
    public void recordSettled(@NotNull UUID player) {
        this.settled.put(player, Boolean.TRUE);
    }

    /** 清除该玩家上一次退出保存留下的诊断标记. */
    public void clearSettled(@NotNull UUID player) {
        this.settled.invalidate(player);
    }

    // ===== 等锁方的探测循环 =====

    /**
     * 等待持有服交出会话锁.
     * 周期探测持有服, 按应答推进, 持续静默按判死夺锁.
     *
     * @param observedValue 抢锁时观察到的持有者锁值
     * @param deadlineNanos 放弃时刻(System.nanoTime 基准), 通常为登录预算的截止点
     * @return 拿到锁后携带新锁值; 截止前没拿到则以 {@link TimeoutException} 异常完成
     */
    @NotNull
    public CompletableFuture<HandoffOutcome> awaitHandoff(@NotNull UUID player, @NotNull String observedValue, long deadlineNanos) {
        Probe probe = new Probe(player, observedValue, deadlineNanos);
        this.probe(probe);
        return probe.future;
    }

    private void probe(Probe probe) {
        if (System.nanoTime() >= probe.deadline) {
            probe.future.completeExceptionally(new TimeoutException("session lock handoff not settled within the login budget"));
            return;
        }
        // 锁值不是本插件的格式, 问不出持有者, 按无主残锁直接夺
        LockValue holder = LockValue.parse(probe.observed); // todo 每次轮询都解析, 没必要
        if (holder == null) {
            this.seize(probe);
            return;
        }
        // 发布消息催促
        this.broker.publishTwoWay(new HandoffRequestMessage(probe.player), holder.serverId())
                .orTimeout(this.probeTimeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((response, throwable) -> {
                    // 无应答, 静默持续超过判死阈值即认定持有者已死
                    if (throwable != null) {
                        long now = System.nanoTime();
                        if (probe.silentSince == 0) probe.silentSince = now;
                        if (now - probe.silentSince >= TimeUnit.MILLISECONDS.toNanos(this.deadSilenceMillis)) {
                            this.seize(probe);
                            return;
                        }
                        this.scheduleProbe(probe);
                        return;
                    }
                    // 有应答, 继续推进
                    probe.silentSince = 0;
                    switch (response.status()) {
                        case SAVING -> this.scheduleProbe(probe);
                        case DONE -> this.reacquire(probe);
                        case UNKNOWN -> this.seize(probe);
                    }
                });
    }

    // 持有服已保存释放, 重试抢锁.
    private void reacquire(Probe probe) {
        this.lock.tryAcquire(probe.player)
                .whenComplete((outcome, throwable) -> {
                    // 还是没抢到, 下一轮重来
                    if (throwable != null) {
                        this.scheduleProbe(probe);
                        return;
                    }
                    switch (outcome) {
                        case SessionLock.AcquireOutcome.Acquired(String value) -> probe.future.complete(new HandoffOutcome(value, "done"));
                        // 别人抢先或旧锁还没删净, 带着最新持有锁的服务器ID继续探测
                        case SessionLock.AcquireOutcome.Held(String value) -> {
                            probe.observed = value;
                            this.scheduleProbe(probe);
                        }
                    }
                });
    }

    // 持有者已死或不认账, 锁值仍是观察值才换成自己的.
    private void seize(Probe probe) {
        this.lock.seize(probe.player, probe.observed)
                .whenComplete((taken, throwable) -> {
                    if (throwable != null) {
                        this.scheduleProbe(probe);
                        return;
                    }
                    if (taken.isPresent()) {
                        probe.future.complete(new HandoffOutcome(taken.get(), "seized"));
                        return;
                    }
                    // 观察值已失效, 锁被释放或已换主, 重抢一次拿最新事实
                    this.lock.tryAcquire(probe.player).whenComplete((outcome, error) -> {
                        if (error != null) {
                            this.scheduleProbe(probe);
                            return;
                        }
                        switch (outcome) {
                            case SessionLock.AcquireOutcome.Acquired(String value) -> probe.future.complete(new HandoffOutcome(value, "seized"));
                            case SessionLock.AcquireOutcome.Held(String value) -> {
                                probe.observed = value;
                                probe.silentSince = 0;
                                this.scheduleProbe(probe);
                            }
                        }
                    });
                });
    }

    // 重新调度探测
    private void scheduleProbe(Probe probe) {
        this.scheduler.later(() -> this.probe(probe), this.probeIntervalMillis);
    }

    // 一次等锁的推进状态, 只在探测回调链上串行访问
    private static final class Probe {
        final UUID player;
        final long deadline;
        final CompletableFuture<HandoffOutcome> future = new CompletableFuture<>();
        String observed;
        long silentSince;  // 0 表示上一轮有应答

        Probe(UUID player, String observed, long deadline) {
            this.player = player;
            this.observed = observed;
            this.deadline = deadline;
        }
    }

    /**
     * 一次交接的结果.
     * 数据一律在拿到锁后读数据库, 锁释放晚于落库, 读到的必为最新.
     *
     * @param lockValue 本服写入的新锁值, 交给会话保管供退出释放
     * @param method 拿到锁的方式, done 为正常交接, seized 为判死夺锁, 供日志检索
     */
    public record HandoffOutcome(@NotNull String lockValue, @NotNull String method) {
    }

    /** 探测调度的接插件调度器. */
    public interface ProbeScheduler {
        void later(@NotNull Runnable task, long delayMillis);
    }
}
