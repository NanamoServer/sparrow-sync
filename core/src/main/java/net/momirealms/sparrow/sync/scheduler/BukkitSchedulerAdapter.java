package net.momirealms.sparrow.sync.scheduler;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.scheduler.executor.BukkitExecutor;
import net.momirealms.sparrow.sync.scheduler.executor.FoliaExecutor;
import net.momirealms.sparrow.sync.scheduler.executor.RegionExecutor;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.World;

public final class BukkitSchedulerAdapter extends AbstractJavaScheduler<World> {
    private final RegionExecutor<World> sync;

    public BukkitSchedulerAdapter(SparrowSync plugin) {
        super(plugin);
        this.sync = VersionHelper.isFolia() ? new FoliaExecutor(plugin) : new BukkitExecutor(plugin);
    }

    @Override
    public RegionExecutor<World> sync() {
        return this.sync;
    }
}
