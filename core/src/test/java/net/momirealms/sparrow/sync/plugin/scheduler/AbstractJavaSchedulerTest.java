package net.momirealms.sparrow.sync.plugin.scheduler;

import net.momirealms.sparrow.sync.plugin.scheduler.executor.EntityExecutor;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.RegionExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AbstractJavaSchedulerTest {
    private static final long WAIT_SECONDS = 5L;

    @Test
    void shutdownDiagnosticsRecognizeBothPoolsAndOnlyTheirOwnThreads() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        try {
            // 真正跑起来的工作线程, 名称由 ForkJoinPool 的线程工厂写入
            AtomicReference<Thread> worker = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            scheduler.async().execute(() -> {
                worker.set(Thread.currentThread());
                started.countDown();
            });
            assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS), "异步任务应执行完成");

            // 调度器线程名称由 ScheduledThreadPoolExecutor 的线程工厂写入
            Thread scheduled = field(scheduler, "scheduler").getThreadFactory().newThread(() -> {
            });

            assertTrue(AbstractJavaScheduler.isWorkerThread(worker.get()), worker.get().getName());
            assertFalse(AbstractJavaScheduler.isSchedulerThread(worker.get()), worker.get().getName());
            assertTrue(AbstractJavaScheduler.isSchedulerThread(scheduled), scheduled.getName());
            assertFalse(AbstractJavaScheduler.isWorkerThread(scheduled), scheduled.getName());
        } finally {
            scheduler.shutdownScheduler();
            scheduler.shutdownExecutor();
        }
    }

    private static ScheduledThreadPoolExecutor field(AbstractJavaScheduler<?> scheduler, String name) {
        try {
            Field field = AbstractJavaScheduler.class.getDeclaredField(name);
            field.setAccessible(true);
            return (ScheduledThreadPoolExecutor) field.get(scheduler);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class TestScheduler extends AbstractJavaScheduler<Object> {
        private TestScheduler() {
            super(null);
        }

        @Override
        public RegionExecutor<Object> sync() {
            throw new UnsupportedOperationException();
        }

        @Override
        public EntityExecutor entity() {
            throw new UnsupportedOperationException();
        }
    }
}
