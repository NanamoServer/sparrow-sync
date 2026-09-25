package net.momirealms.sparrow.sync.plugin.scheduler;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.AbstractBukkitExecutor;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.BukkitExecutor;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.FoliaExecutor;
import net.momirealms.sparrow.sync.util.VersionHelper;


public final class BukkitSchedulerAdapter extends AbstractJavaScheduler {
    private final SparrowSync plugin;
    private final AbstractBukkitExecutor sync;

    public BukkitSchedulerAdapter(SparrowSync plugin) {
        super(plugin);
        this.plugin = plugin;
        if (VersionHelper.hasFoliaPatch) {
            this.sync = new FoliaExecutor(plugin);
        } else {
            this.sync = new BukkitExecutor(plugin);
        }
    }

    @Override
    public AbstractBukkitExecutor platform() {
        return this.sync;
    }
}
