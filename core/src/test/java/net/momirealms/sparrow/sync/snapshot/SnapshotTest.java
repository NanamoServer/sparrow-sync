package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotTest {

    @Test
    void dataIsReadOnly() {
        Snapshot snapshot = this.snapshot(Map.of(DataKey.sparrow("inventory"), NBT.createString("first")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.data().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.data().put(DataKey.sparrow("health"), NBT.createString("second")));
    }

    @Test
    void dataKeepsGivenIterationOrder() {
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        data.put(DataKey.sparrow("second"), NBT.createString("b"));
        data.put(DataKey.sparrow("first"), NBT.createString("a"));
        Snapshot snapshot = this.snapshot(data);
        assertEquals(
                new ArrayList<>(data.keySet()),
                new ArrayList<>(snapshot.data().keySet())
        );
    }

    private Snapshot snapshot(Map<DataKey, Tag> data) {
        return new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1, SaveCause.COMMAND, false, "test", 4440), data);
    }
}
