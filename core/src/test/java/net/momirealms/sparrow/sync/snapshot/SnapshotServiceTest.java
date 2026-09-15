package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotServiceTest {

    @Test
    void shutdownBeforeServiceAssemblyHasNoPendingWork() {
        SnapshotService service = new SnapshotService(NmsPlayerFixture.allocate(SparrowSync.class));
        service.stopOperations();
        assertTrue(service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        service.stashUnsettled();
    }
}
