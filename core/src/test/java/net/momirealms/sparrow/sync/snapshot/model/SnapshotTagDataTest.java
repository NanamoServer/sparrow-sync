package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotTagDataTest {
    private static final DataKey FIRST = DataKey.of("test", "first");
    private static final DataKey SECOND = DataKey.of("test", "second");

    @Test
    void eagerDataReusesReadOnlyTagsInSourceOrder() {
        Tag first = NBT.createString("original");
        Tag second = NBT.createInt(2);
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        values.put(FIRST, first);
        values.put(SECOND, second);
        EagerSnapshotData data = new EagerSnapshotData(values);
        assertEquals(List.of(FIRST, SECOND), new ArrayList<>(data.keys()));
        assertSame(first, data.get(FIRST));
        assertSame(second, data.all().get(SECOND));
        assertSame(data.all(), data.all());
        assertThrows(UnsupportedOperationException.class, () -> data.all().put(FIRST, second));
        assertThrows(UnsupportedOperationException.class, () -> data.keys().remove(FIRST));
        assertSame(EagerSnapshotData.EMPTY, EagerSnapshotData.fromTags(Map.of()));
    }

    @Test
    void snapshotEqualityUsesSnapshotMetadataAndTypeTags() {
        SnapshotMeta meta = SnapshotFixtures.meta();
        Snapshot first = new Snapshot(meta, Map.of(FIRST, NBT.createInt(1)));
        Snapshot same = new Snapshot(meta, new EagerSnapshotData(Map.of(FIRST, NBT.createInt(1))));
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, new Snapshot(meta, first.content().with(FIRST, NBT.createInt(2))));
        assertNotEquals(first, new Snapshot(meta.withPinned(true), first.content()));
    }
}
