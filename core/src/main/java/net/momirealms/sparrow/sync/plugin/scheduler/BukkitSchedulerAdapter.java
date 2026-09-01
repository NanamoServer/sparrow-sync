package net.momirealms.sparrow.sync.plugin.scheduler;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.BukkitEntityExecutor;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.BukkitExecutor;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.EntityExecutor;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.FoliaEntityExecutor;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.FoliaExecutor;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.RegionExecutor;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

public final class BukkitSchedulerAdapter extends AbstractJavaScheduler<World> {
    private final RegionExecutor<World> sync;
    private final EntityExecutor entity;

    public BukkitSchedulerAdapter(SparrowSync plugin) {
        super(plugin);
        this.sync = VersionHelper.isFolia() ? new FoliaExecutor(plugin) : new BukkitExecutor(plugin);
        this.entity = BukkitSchedulerAdapter.hasEntityScheduler()
                ? new FoliaEntityExecutor(plugin)
                : new BukkitEntityExecutor(plugin);
    }

    @Override
    public RegionExecutor<World> sync() {
        return this.sync;
    }

    @Override
    @NotNull
    public EntityExecutor entity() {
        return this.entity;
    }

    private static boolean hasEntityScheduler() {
        try {
            Entity.class.getMethod("getScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }
}
