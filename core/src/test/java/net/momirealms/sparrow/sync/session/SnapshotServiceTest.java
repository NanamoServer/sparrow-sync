package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnapshotServiceTest {
    private static final DataKey FIRST = DataKey.of("test", "first");
    private static final DataKey UNKNOWN = DataKey.of("other", "unknown");

    @Test
    void capturedValuesOverrideRetainedValuesWithTheSameKey() {
        Map<DataKey, Tag> passthrough = new LinkedHashMap<>();
        passthrough.put(UNKNOWN, NBT.createString("unknown"));
        passthrough.put(FIRST, NBT.createString("old"));

        Map<DataKey, Tag> merged = SnapshotService.mergeData(passthrough, Map.of(FIRST, NBT.createString("new")));

        assertEquals("unknown", merged.get(UNKNOWN).getAsString());
        assertEquals("new", merged.get(FIRST).getAsString());
    }
}
