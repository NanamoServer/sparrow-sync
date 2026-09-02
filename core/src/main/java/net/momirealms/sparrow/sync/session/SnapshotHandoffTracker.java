package net.momirealms.sparrow.sync.session;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

final class SnapshotHandoffTracker {
    private int pending;
    private boolean sealed;

    synchronized void accept() {
        if (this.sealed) {
            throw new RejectedExecutionException("snapshot service is shut down");
        }
        this.pending++;
    }

    synchronized void handedOff() {
        this.pending--;
        if (this.pending == 0) this.notifyAll();
    }

    synchronized boolean sealAndAwait(long timeout, @NotNull TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        // seal 与 accept 共用监视器, pending 包含封口前的全部已接纳请求
        this.sealed = true;
        while (this.pending > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
