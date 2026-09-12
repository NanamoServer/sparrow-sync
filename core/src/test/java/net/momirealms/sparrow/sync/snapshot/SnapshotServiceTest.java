package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotServiceTest {
    private static final DataKey FIRST = DataKey.of("test", "first");
    private static final DataKey UNKNOWN = DataKey.of("other", "unknown");

    /** 启动尚未装配业务对象时, 停服回调仍能结束空交接并清理已创建资源. */
    @Test
    void shutdownBeforeServiceAssemblyHasNoPendingWork() {
        SnapshotService service = new SnapshotService(NmsPlayerFixture.allocate(SparrowSync.class));
        service.stopOperations();
        assertTrue(service.sealAndAwaitSaves(0, TimeUnit.NANOSECONDS));
        service.stashUnsettled();
    }

    @Test
    void capturedValuesOverrideRetainedValuesWithTheSameKey() {
        Map<DataKey, Tag> passthrough = new LinkedHashMap<>();
        passthrough.put(UNKNOWN, NBT.createString("unknown"));
        passthrough.put(FIRST, NBT.createString("old"));

        SnapshotData merged = EagerSnapshotData.fromTags(passthrough).with(Map.of(FIRST, NBT.createString("new")));

        assertEquals("unknown", merged.get(UNKNOWN).getAsString());
        assertEquals("new", merged.get(FIRST).getAsString());
    }
}
