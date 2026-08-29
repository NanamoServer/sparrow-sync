package net.momirealms.sparrow.sync.executor;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按玩家 UUID 分桶的串行执行器. 同一玩家的任务永远落在同一条 worker 线程上并严格按提交序执行.
 * 任务可以带一个就绪时刻, 没到点的任务不占线程空转, worker 会等到它到点, 期间新任务照常唤醒.
 */
public final class PlayerSerialExecutor {
    private static final UUID SHUTDOWN_PLAYER = new UUID(0, 0);
    private static final QueuedTask SHUTDOWN_SIGNAL = new QueuedTask(SHUTDOWN_PLAYER, () -> {}, 0);   // 排在队尾的退出哨兵
    private static final long DRAIN_REPORT_MILLIS = 5_000;       // 排空进度的播报间隔

    private final PluginLogger logger;
    private final Worker[] workers;
    private final AtomicLong failures = new AtomicLong();
    private volatile boolean shutdown;

    /**
     * @param workerCount 期望的 worker 数, 会被规范化到最近的不小于它的 2 的幂 (分桶用位运算取模)
     */
    public PlayerSerialExecutor(@NotNull PluginLogger logger, int workerCount) {
        this.logger = logger;
        int count = Math.clamp(workerCount, 1, 64);
        int power = Integer.highestOneBit(count);
        int buckets = power == count ? count : power << 1;
        this.workers = new Worker[buckets];
        for (int i = 0; i < buckets; i++) {
            this.workers[i] = new Worker(i);
        }
    }

    /**
     * 把任务追加到该玩家所在桶的队尾.
     *
     * @throws RejectedExecutionException 当执行器已关停时
     */
    public void submit(@NotNull UUID player, @NotNull Runnable task) {
        this.enqueue(new QueuedTask(player, task, System.nanoTime()));
    }

    /**
     * 把任务追加到队尾, 但在指定延迟之前不执行.
     * 到点之前该玩家的后续任务一起等, 同桶其他玩家照常推进.
     *
     * @throws RejectedExecutionException 当执行器已关停时
     */
    public void submitDelayed(@NotNull UUID player, @NotNull Runnable task, long delay, @NotNull TimeUnit unit) {
        this.enqueue(new QueuedTask(player, task, System.nanoTime() + unit.toNanos(delay)));
    }

    private void enqueue(QueuedTask task) {
        if (this.shutdown) {
            throw new RejectedExecutionException("player serial executor is shut down");
        }
        this.worker(task.player()).enqueue(task);
    }

    private Worker worker(UUID player) {
        return this.workers[player.hashCode() & (this.workers.length - 1)];
    }

    /**
     * 返回绑定到该玩家所在桶的 Executor 视图, 供 CompletableFuture 异步链使用.
     */
    @NotNull
    public Executor executor(@NotNull UUID player) {
        return task -> this.submit(player, task);
    }

    /**
     * 关停执行器, 立即拒绝新任务, 在限时内等待各队列排空, 超时后中断 worker.
     * 满服关服时这里承载全员的最后一次落盘, 超时应按存储写入延迟留足余量.
     *
     * @return 未能执行完的剩余任务数
     */
    public int shutdown(long timeout, @NotNull TimeUnit unit) {
        this.shutdown = true;
        for (int i = 0; i < this.workers.length; i++) {
            this.workers[i].enqueue(SHUTDOWN_SIGNAL);
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (int i = 0; i < this.workers.length; i++) {
            if (!this.awaitWorker(this.workers[i], deadline)) break;
        }
        // 仍在运行的 worker 强制中断, 剩余任务只统计上报;
        // 没保存完的快照由 SnapshotService.stashUnsettled 落盘.
        int remaining = 0;
        for (int i = 0; i < this.workers.length; i++) {
            Worker worker = this.workers[i];
            if (worker.thread.isAlive()) {
                worker.thread.interrupt();
            }
            remaining += worker.dropSentinelAndCount();
        }
        if (remaining > 0) {
            this.logger.warn(TranslationManager.console(LogConstants.EXECUTOR_UNFINISHED_TASKS, String.valueOf(remaining)));
        }
        return remaining;
    }

    // join 在 worker 退出时立即返回, 分片只是为了期间能播报进度
    private boolean awaitWorker(Worker worker, long deadline) {
        while (worker.thread.isAlive()) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) return false;
            try {
                worker.thread.join(Math.min(remainingMillis, DRAIN_REPORT_MILLIS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (worker.thread.isAlive()) {
                this.logger.info(TranslationManager.console(LogConstants.EXECUTOR_DRAINING, String.valueOf(this.pendingTasks())));
            }
        }
        return true;
    }

    public int workerCount() {
        return this.workers.length;
    }

    public int pendingTasks() {
        int pending = 0;
        for (int i = 0; i < this.workers.length; i++) {
            pending += this.workers[i].pending();
        }
        return pending;
    }

    public long failureCount() {
        return this.failures.get();
    }

    private record QueuedTask(@NotNull UUID player, @NotNull Runnable task, long readyAtNanos) {
    }

    private final class Worker implements Runnable {
        private final ArrayDeque<QueuedTask> queue = new ArrayDeque<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition arrived = this.lock.newCondition();
        private final Thread thread;

        private Worker(int index) {
            this.thread = new Thread(this, "sparrow-sync-serial-" + index);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void enqueue(QueuedTask task) {
            this.lock.lock();
            try {
                this.queue.addLast(task);
                this.arrived.signal();
            } finally {
                this.lock.unlock();
            }
        }

        private int pending() {
            this.lock.lock();
            try {
                return this.queue.size();
            } finally {
                this.lock.unlock();
            }
        }

        private int dropSentinelAndCount() {
            this.lock.lock();
            try {
                this.queue.remove(SHUTDOWN_SIGNAL);
                return this.queue.size();
            } finally {
                this.lock.unlock();
            }
        }

        /**
         * 取出队列里第一个到点的任务. 某玩家有任务没到点时, 他后面的任务一起跳过以保住提交序.
         * 其他玩家的任务照常被取走; 全都没到点就等到最早的那个, 期间新任务会提前唤醒.
         */
        private QueuedTask takeReady() throws InterruptedException {
            this.lock.lock();
            try {
                while (true) {
                    long waitNanos = Long.MAX_VALUE;
                    List<UUID> waiting = null;
                    for (Iterator<QueuedTask> iterator = this.queue.iterator(); iterator.hasNext(); ) {
                        QueuedTask candidate = iterator.next();
                        if (waiting != null && waiting.contains(candidate.player())) continue;
                        long remaining = candidate.readyAtNanos() - System.nanoTime();
                        if (remaining <= 0) {
                            iterator.remove();
                            return candidate;
                        }
                        if (waiting == null) waiting = new ArrayList<>(2);
                        waiting.add(candidate.player());
                        waitNanos = Math.min(waitNanos, remaining);
                    }
                    if (waitNanos == Long.MAX_VALUE) {
                        this.arrived.await();
                    } else {
                        this.arrived.awaitNanos(waitNanos);
                    }
                }
            } finally {
                this.lock.unlock();
            }
        }

        @Override
        public void run() {
            while (true) {
                QueuedTask queued;
                try {
                    queued = this.takeReady();
                } catch (InterruptedException exception) {
                    // 关停超时后的强制退出
                    Thread.currentThread().interrupt();
                    return;
                }
                // 哨兵排在关停时刻的队尾, 取到它说明本桶已排空
                if (queued == SHUTDOWN_SIGNAL) return;
                try {
                    queued.task().run();
                } catch (Throwable throwable) {
                    // 任务是各业务提交的回调, 单任务失败计数上报.
                    failures.incrementAndGet();
                    logger.warn(TranslationManager.console(LogConstants.EXECUTOR_TASK_FAILED, this.thread.getName()), throwable);
                }
            }
        }
    }
}
