package net.momirealms.sparrow.sync.plugin.scheduler;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.scheduler.task.AsyncTask;
import net.momirealms.sparrow.sync.plugin.scheduler.task.LazyAsyncTask;
import net.momirealms.sparrow.sync.plugin.scheduler.task.SchedulerTask;
import org.jetbrains.annotations.NotNull;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public abstract class AbstractJavaScheduler<T> implements SchedulerAdapter<T> {
    private static final int PARALLELISM = 16;
    private static final String SCHEDULER_THREAD_NAME = "plugin-scheduler";
    private static final String WORKER_THREAD_NAME_PREFIX = "plugin-worker-";

    private final Plugin plugin;
    private final ScheduledThreadPoolExecutor scheduler; // 定时调度线程池
    private final ForkJoinPool worker; // 运行线程池

    public AbstractJavaScheduler(Plugin plugin) {
        this.plugin = plugin;
        // 创建一个核心线程数为 4 的 ScheduledThreadPoolExecutor
        this.scheduler = new ScheduledThreadPoolExecutor(4, r -> {
            Thread thread = Executors.defaultThreadFactory().newThread(r);
            thread.setName(SCHEDULER_THREAD_NAME);
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true); // 取消任务时自动移除
        this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); // 关闭时不执行已存在的延迟任务
        // 创建一个并行度为 PARALLELISM 的 ForkJoinPool 作为异步线程池, 使用自定义的线程工厂和异常处理器.
        this.worker = new ForkJoinPool(PARALLELISM, new WorkerThreadFactory(), new ExceptionHandler(), false);
    }

    /**
     * 获取异步执行器.
     * 返回内部的 ForkJoinPool, 提交的任务将在异步线程池中并行执行.
     *
     * @return ForkJoinPool 异步执行器实例.
     */
    @Override
    public Executor async() {
        return this.worker;
    }

    /**
     * 在异步线程池中延迟执行一个任务.
     *
     * @param task  需要执行的任务.
     * @param delay 延迟时间.
     * @param unit  延迟时间的单位.
     * @return 表示该调度任务的 AsyncTask 实例.
     */
    @Override
    public SchedulerTask asyncLater(Runnable task, long delay, TimeUnit unit) {
        ScheduledFuture<?> future = this.scheduler.schedule(() -> this.worker.execute(task), delay, unit);
        return new AsyncTask(future);
    }

    /**
     * 在异步线程池中以固定频率重复执行一个任务.
     *
     * @param task     需要重复执行的任务.
     * @param delay    首次执行前的延迟时间.
     * @param interval 两次执行之间的间隔时间.
     * @param unit     时间单位.
     * @return 表示该调度任务的 AsyncTask 实例.
     */
    @Override
    public SchedulerTask asyncRepeating(Runnable task, long delay, long interval, TimeUnit unit) {
        ScheduledFuture<?> future = this.scheduler.scheduleAtFixedRate(() -> this.worker.execute(task), delay, interval, unit);
        return new AsyncTask(future);
    }

    /**
     * 在异步线程池中以固定频率重复执行一个任务, 任务可以接收 SchedulerTask 参数以实现自我取消.
     *
     * @param task     需要重复执行的任务, 接收自身的 SchedulerTask 作为参数.
     * @param delay    首次执行前的延迟时间.
     * @param interval 两次执行之间的间隔时间.
     * @param unit     时间单位.
     * @return 表示该调度任务的 LazyAsyncTask 实例.
     */
    @Override
    public SchedulerTask asyncRepeating(Consumer<SchedulerTask> task, long delay, long interval, TimeUnit unit) {
        LazyAsyncTask asyncTask = new LazyAsyncTask();
        asyncTask.future = this.scheduler.scheduleAtFixedRate(() -> this.worker.execute(() -> task.accept(asyncTask)), delay, interval, unit);
        return asyncTask;
    }

    /**
     * 关闭调度器.
     */
    @Override
    public void shutdownScheduler() {
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(1, TimeUnit.MINUTES)) {
                this.plugin.logger().error("Timed out waiting for the sparrow-sync scheduler to terminate");
                reportRunningTasks(AbstractJavaScheduler::isSchedulerThread);
            }
        } catch (InterruptedException e) {
            plugin.logger().warn("Thread is interrupted", e);
        }
    }

    /**
     * 关闭异步执行器 (ForkJoinPool).
     */
    @Override
    public void shutdownExecutor() {
        this.worker.shutdown();
        try {
            if (!this.worker.awaitTermination(1, TimeUnit.MINUTES)) {
                this.plugin.logger().error("Timed out waiting for the sparrow-sync worker thread pool to terminate");
                reportRunningTasks(AbstractJavaScheduler::isWorkerThread);
            }
        } catch (InterruptedException e) {
            plugin.logger().warn("Thread is interrupted", e);
        }
    }

    // 关服超时诊断据此筛出调度器线程, 与线程工厂设置的名称取自同一常量
    static boolean isSchedulerThread(@NotNull Thread thread) {
        return SCHEDULER_THREAD_NAME.equals(thread.getName());
    }

    // 关服超时诊断据此筛出异步线程池的 worker, 与线程工厂设置的名称前缀取自同一常量
    static boolean isWorkerThread(@NotNull Thread thread) {
        return thread.getName().startsWith(WORKER_THREAD_NAME_PREFIX);
    }

    /**
     * 报告满足指定条件的仍在运行的线程及其堆栈信息.
     * 用于在关闭超时时诊断阻塞线程.
     *
     * @param predicate 用于筛选目标线程的条件判断器.
     */
    private void reportRunningTasks(Predicate<Thread> predicate) {
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (predicate.test(thread)) {
                this.plugin.logger().warn("Thread " + thread.getName() + " is blocked, and may be the reason for the slow shutdown!\n" +
                        Arrays.stream(stack).map(el -> "  " + el).collect(Collectors.joining("\n"))
                );
            }
        });
    }

    /**
     * ForkJoinPool 的异步线程工厂.
     * 为异步线程设置守护线程属性和统一的命名规则.
     */
    private static final class WorkerThreadFactory implements ForkJoinPool.ForkJoinWorkerThreadFactory {
        private static final AtomicInteger COUNT = new AtomicInteger(0);

        /**
         * 创建一个新的 ForkJoinWorkerThread.
         * @param pool 线程所属的 ForkJoinPool.
         * @return 新创建的 ForkJoinWorkerThread 实例.
         */
        @Override
        public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            thread.setDaemon(true);
            thread.setName(WORKER_THREAD_NAME_PREFIX + COUNT.getAndIncrement());
            return thread;
        }
    }

    /**
     * 异步线程的未捕获异常处理器.
     * 当异步线程中发生未捕获的异常时, 将异常信息记录到插件日志中.
     */
    private final class ExceptionHandler implements UncaughtExceptionHandler {

        /**
         * 处理线程中未捕获的异常.
         *
         * @param t 发生异常的线程.
         * @param e 未捕获的异常对象.
         */
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            AbstractJavaScheduler.this.plugin.logger().warn("Thread " + t.getName() + " threw an uncaught exception", e);
        }
    }
}
