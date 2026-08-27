package net.momirealms.sparrow.sync.scheduler.executor;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.scheduler.task.DummyTask;
import net.momirealms.sparrow.sync.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.sync.scheduler.task.platform.BukkitTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

public final class BukkitExecutor implements RegionExecutor<World> {
    private final SparrowSync plugin;

    public BukkitExecutor(SparrowSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run(Runnable runnable, World world, int x, int z) {
        execute(runnable);
    }

    @Override
    public void runDelayed(Runnable r, World world, int x, int z) {
        Bukkit.getScheduler().runTask(plugin.javaPlugin(), r);
    }

    @Override
    public SchedulerTask runAsyncRepeating(Runnable runnable, long delay, long period) {
        return new BukkitTask(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin.javaPlugin(), runnable, delay, period));
    }

    @Override
    public SchedulerTask runAsyncLater(Runnable runnable, long delay) {
        return new BukkitTask(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin.javaPlugin(), runnable, delay));
    }

    @Override
    public SchedulerTask runLater(Runnable runnable, long delay, World world, int x, int z) {
        if (delay <= 0) {
            if (Bukkit.isPrimaryThread()) {
                runnable.run();
                return new DummyTask();
            } else {
                return new BukkitTask(Bukkit.getScheduler().runTask(plugin.javaPlugin(), runnable));
            }
        }
        return new BukkitTask(Bukkit.getScheduler().runTaskLater(plugin.javaPlugin(), runnable, delay));
    }

    @Override
    public SchedulerTask runRepeating(Runnable runnable, long delay, long period, World world, int x, int z) {
        return new BukkitTask(Bukkit.getScheduler().runTaskTimer(plugin.javaPlugin(), runnable, delay, period));
    }

    @Override
    public void execute(@NotNull Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin.javaPlugin(), runnable);
    }
}
