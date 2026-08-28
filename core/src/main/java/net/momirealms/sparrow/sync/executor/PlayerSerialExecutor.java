package net.momirealms.sparrow.sync.executor;

import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按玩家 UUID 分桶的串行执行器. 同一玩家的任务永远落在同一条 worker 线程上并严格按提交序执行.
 */
public final class PlayerSerialExecutor {
    private static final Runnable SHUTDOWN_SIGNAL = () -> {};    // 排在队尾的退出哨兵, 前面的任务跑完才会被取到
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
        if (this.shutdown) {
            throw new RejectedExecutionException("player serial executor is shut down");
        }
        this.worker(player).queue.addLast(task);
    }

    /**
     * 把任务插到该玩家所在桶的队首, 供交接请求把保存任务提前.
     * <strong>插队会越过桶内既有任务, 包括同一玩家更早提交的任务; 仅当该玩家此刻没有未决任务,
     * 或插队任务与它们的顺序无关时使用</strong>, 否则破坏该玩家的提交序契约.
     *
     * @throws RejectedExecutionException 当执行器已关停时
     */
    public void submitFirst(@NotNull UUID player, @NotNull Runnable task) {
        if (this.shutdown) {
            throw new RejectedExecutionException("player serial executor is shut down");
        }
        this.worker(player).queue.addFirst(task);
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
            this.workers[i].queue.addLast(SHUTDOWN_SIGNAL);
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (int i = 0; i < this.workers.length; i++) {
            if (!this.awaitWorker(this.workers[i], deadline)) break;
        }
        // 仍在运行的 worker 强制中断, 剩余任务只统计上报
        // todo 这里需要改进一下, 如果保存失败则想办法dump到本地, 然后在下次服务器启动时将快照插入回数据库的正确位置, SaveCause 也许要扩展一个字段, 代表保存失败后, 启动时/命令恢复. .
        // todo 恢复完之后记得把就文件删了.
        int remaining = 0;
        for (int i = 0; i < this.workers.length; i++) {
            Worker worker = this.workers[i];
            if (worker.thread.isAlive()) {
                worker.thread.interrupt();
            }
            worker.queue.remove(SHUTDOWN_SIGNAL);
            remaining += worker.queue.size();
        }
        if (remaining > 0) {
            this.logger.warn("Player serial executor shut down with " + remaining + " unfinished tasks");
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
                this.logger.info("Draining player tasks, " + this.pendingTasks() + " remaining");
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
            pending += this.workers[i].queue.size();
        }
        return pending;
    }

    public long failureCount() {
        return this.failures.get();
    }

    private final class Worker implements Runnable {
        private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
        private final Thread thread;

        private Worker(int index) {
            this.thread = new Thread(this, "sparrow-sync-serial-" + index);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        @Override
        public void run() {
            while (true) {
                Runnable task;
                try {
                    task = this.queue.takeFirst();
                } catch (InterruptedException exception) {
                    // 关停超时后的强制退出
                    Thread.currentThread().interrupt();
                    return;
                }
                // 哨兵排在关停时刻的队尾, 取到它说明本桶已排空
                if (task == SHUTDOWN_SIGNAL) return;
                try {
                    task.run();
                } catch (Throwable throwable) {
                    // 任务是各业务提交的回调, 单任务失败计数上报.
                    failures.incrementAndGet();
                    logger.warn("Player task failed on " + this.thread.getName(), throwable);
                }
            }
        }
    }
}
