package net.momirealms.sparrow.sync.scheduler.task;

public final class DummyTask implements SchedulerTask {

    @Override
    public void cancel() {
    }

    @Override
    public boolean cancelled() {
        return true;
    }
}