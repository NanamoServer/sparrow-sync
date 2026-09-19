package net.momirealms.sparrow.sync.plugin.scheduler.executor;

import net.momirealms.sparrow.sync.plugin.scheduler.task.SchedulerTask;
import org.bukkit.entity.Entity;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Executor;

public interface PlatformExecutor extends Executor {

    /**
     * 判断当前线程是否拥有实体的执行权.
     *
     * @param entity 目标实体
     * @return 当前线程拥有执行权时返回 true
     */
    boolean isOwnedByCurrentRegion(@NotNull Entity entity);

    // Run

    void run(Runnable r, World world, int x, int z);

    void run(Runnable r, Runnable retired, Entity entity);

    default void run(Runnable r) {
        run(r, null, 0, 0);
    }

    // Delayed

    void runDelayed(Runnable r, World world, int x, int z);

    void runDelayed(Runnable r, Runnable retired, Entity entity);

    default void runDelayed(Runnable r) {
        runDelayed(r, null, 0, 0);
    }

    // Later

    default SchedulerTask runLater(Runnable r, long delay) {
        return runLater(r, delay, null, 0 ,0);
    }

    SchedulerTask runLater(Runnable r, long delay, World world, int x, int z);

    @Nullable
    SchedulerTask runLater(Runnable r, Runnable retired, long delay, Entity entity);

    // Repeating

    default SchedulerTask runRepeating(Runnable r, long delay, long period) {
        return runRepeating(r, delay, period, null, 0, 0);
    }

    SchedulerTask runRepeating(Runnable r, long delay, long period, World world, int x, int z);

    @Nullable
    SchedulerTask runRepeating(Runnable r, Runnable retired, long delay, long period, Entity entity);

    SchedulerTask runAsyncLater(Runnable r, long delayTicks);

    SchedulerTask runAsyncRepeating(Runnable r, long delayTicks, long periodTicks);
}
