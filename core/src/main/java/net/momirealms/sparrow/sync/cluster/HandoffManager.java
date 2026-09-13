package net.momirealms.sparrow.sync.cluster;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import net.momirealms.sparrow.sync.cluster.message.HandoffRequestMessage;
import net.momirealms.sparrow.sync.cluster.message.HandoffResponseMessage;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

public final class HandoffManager {
    private static final long PROBE_INTERVAL_MILLIS = 500;   // 两次探测的间隔
    private static final long PROBE_TIMEOUT_MILLIS = 500;    // 单次探测的最长等待时间
    private static final long DEAD_SILENCE_MILLIS = 3000;    // 连续探测失败多久后尝试接管锁

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

    public void onLoad() {
        this.broker = this.plugin.messageBrokerManager().broker();
        this.lock = this.plugin.sessionLock();
        this.hasSession = uuid -> this.plugin.sessionManager().find(uuid) != null || this.plugin.snapshotService().restoringOffline(uuid);
        this.scheduler = (task, delayMillis) -> this.plugin.scheduler().asyncLater(task, delayMillis, TimeUnit.MILLISECONDS);
        HandoffRequestMessage.service(this);
    }

    /**
     * 根据本服内存中的会话和保存记录回答探测, <strong>不得等待保存或执行阻塞 I/O</strong>.
     */
    @NotNull
    public HandoffResponseMessage answer(@NotNull UUID player) {
        // 已关闭但尚未移除的会话, 以及离线恢复任务, 都需要继续等待
        if (this.hasSession.test(player)) return HandoffResponseMessage.saving();
        if (this.settled.getIfPresent(player) != null) return HandoffResponseMessage.done();
        return HandoffResponseMessage.unknown();
    }

    /** 记录退出快照已存入数据库, <strong>必须在释放会话锁前调用</strong>. */
    public void recordSettled(@NotNull UUID player) {
        this.settled.put(player, Boolean.TRUE);
    }

    /** 清除玩家上次退出时的保存完成标记. */
    public void clearSettled(@NotNull UUID player) {
        this.settled.invalidate(player);
    }

    /**
     * 等待会话锁交接, 持有者持续无响应时尝试接管.
     *
     * @param observedValue 获取锁时返回的持有者锁值
     * @param deadlineNanos 等待截止时间, 以 {@link System#nanoTime()} 为基准
     * @return 成功时返回本服的新锁值和获取方式, 超时则以 {@link TimeoutException} 异常完成
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
        // 锁值无法解析时无法联系持有者, 直接尝试接管
        if (probe.holder == null) {
            this.seize(probe);
            return;
        }
        this.broker.publishTwoWay(new HandoffRequestMessage(probe.player), probe.holder.serverId())
                .orTimeout(this.probeTimeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((response, throwable) -> {
                    // 连续探测失败达到阈值后尝试接管锁
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
                    // 收到应答后清零, 下次失败再开始计时
                    probe.silentSince = 0;
                    switch (response.status()) {
                        case SAVING -> this.scheduleProbe(probe);
                        case DONE -> this.reacquire(probe);
                        case UNKNOWN -> this.seize(probe);
                    }
                });
    }

    // 持有服已完成保存, 重新尝试获取锁
    private void reacquire(Probe probe) {
        this.lock.tryAcquire(probe.player)
                .whenComplete((outcome, throwable) -> {
                    if (throwable != null) {
                        this.scheduleProbe(probe);
                        return;
                    }
                    switch (outcome) {
                        case SessionLock.AcquireOutcome.Acquired(String value) -> probe.future.complete(new HandoffOutcome(value, "done"));
                        // 锁仍被占用, 改为探测当前持有者
                        case SessionLock.AcquireOutcome.Held(String value) -> {
                            probe.setObserved(value);
                            this.scheduleProbe(probe);
                        }
                    }
                });
    }

    // 仅在锁值未变时接管, 锁值变化后重新确认持有者
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
                    // 锁已释放或换了持有者, 再次获取锁并读取当前锁值
                    this.lock.tryAcquire(probe.player).whenComplete((outcome, error) -> {
                        if (error != null) {
                            this.scheduleProbe(probe);
                            return;
                        }
                        switch (outcome) {
                            case SessionLock.AcquireOutcome.Acquired(String value) -> probe.future.complete(new HandoffOutcome(value, "seized"));
                            case SessionLock.AcquireOutcome.Held(String value) -> {
                                probe.setObserved(value);
                                probe.silentSince = 0;
                                this.scheduleProbe(probe);
                            }
                        }
                    });
                });
    }

    private void scheduleProbe(Probe probe) {
        this.scheduler.later(() -> this.probe(probe), this.probeIntervalMillis);
    }

    // 单次交接的状态, 由探测回调串行更新
    private static final class Probe {
        final UUID player;
        final long deadline;
        final CompletableFuture<HandoffOutcome> future = new CompletableFuture<>();
        String observed;    // 探测时的完整锁值, 接管时用于比较
        LockValue holder;   // 解析出的持有者, 格式错误时为 null
        long silentSince;  // 连续探测失败的起点, 0 表示尚未计时

        Probe(UUID player, String observed, long deadline) {
            this.player = player;
            this.deadline = deadline;
            this.setObserved(observed);
        }

        void setObserved(String observed) {
            this.observed = observed;
            this.holder = LockValue.parse(observed);
        }
    }

    public record HandoffOutcome(@NotNull String lockValue, @NotNull String method) {
    }

    /** 按毫秒延迟执行下一次探测. */
    public interface ProbeScheduler {
        void later(@NotNull Runnable task, long delayMillis);
    }
}
