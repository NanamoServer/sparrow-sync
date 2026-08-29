package net.momirealms.sparrow.sync.plugin.scheduler.task;

public final class DummyTask implements SchedulerTask {

    @Override
    public void cancel() {
    }

    @Override
    public boolean cancelled() {
        return true;
    }
}