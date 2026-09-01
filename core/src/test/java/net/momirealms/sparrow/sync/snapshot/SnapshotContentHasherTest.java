package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SnapshotContentHasherTest {
    private static final DataKey FIRST = DataKey.of("sparrow", "first");
    private static final DataKey SECOND = DataKey.of("sparrow", "second");

    @Test
    void topLevelAndCompoundIterationOrderDoNotAffectHash() {
        CompoundTag firstCompound = compound(Map.entry("name", NBT.createString("sparrow")), Map.entry("count", NBT.createInt(7)));
        CompoundTag secondCompound = compound(Map.entry("count", NBT.createInt(7)), Map.entry("name", NBT.createString("sparrow")));
        Map<DataKey, Tag> firstData = linkedData(Map.entry(FIRST, firstCompound), Map.entry(SECOND, NBT.createLong(12)));
        Map<DataKey, Tag> secondData = linkedData(Map.entry(SECOND, NBT.createLong(12)), Map.entry(FIRST, secondCompound));

        assertEquals(SnapshotContentHasher.hash(firstData), SnapshotContentHasher.hash(secondData));
    }

    @Test
    void dataKeyRemainsAssociatedWithItsValue() {
        Map<DataKey, Tag> firstData = linkedData(Map.entry(FIRST, NBT.createInt(1)), Map.entry(SECOND, NBT.createInt(2)));
        Map<DataKey, Tag> secondData = linkedData(Map.entry(FIRST, NBT.createInt(2)), Map.entry(SECOND, NBT.createInt(1)));

        assertNotEquals(SnapshotContentHasher.hash(firstData), SnapshotContentHasher.hash(secondData));
    }

    @Test
    void listOrderAffectsHash() {
        ListTag firstList = NBT.createList(List.of(NBT.createString("alpha"), NBT.createString("bravo")));
        ListTag secondList = NBT.createList(List.of(NBT.createString("bravo"), NBT.createString("alpha")));

        assertNotEquals(hashSingle(firstList), hashSingle(secondList));
    }

    @Test
    void arrayOrderAffectsHash() {
        assertNotEquals(hashSingle(NBT.createByteArray(new byte[]{1, 2})), hashSingle(NBT.createByteArray(new byte[]{2, 1})));
        assertNotEquals(hashSingle(NBT.createIntArray(new int[]{1, 2})), hashSingle(NBT.createIntArray(new int[]{2, 1})));
        assertNotEquals(hashSingle(NBT.createLongArray(new long[]{1, 2})), hashSingle(NBT.createLongArray(new long[]{2, 1})));
    }

    @Test
    void numericTagTypesHaveDistinctHashes() {
        Set<SnapshotContentHasher.ContentHash> hashes = new HashSet<>();
        hashes.add(hashSingle(NBT.createByte((byte) 1)));
        hashes.add(hashSingle(NBT.createShort((short) 1)));
        hashes.add(hashSingle(NBT.createInt(1)));
        hashes.add(hashSingle(NBT.createLong(1)));
        hashes.add(hashSingle(NBT.createFloat(1)));
        hashes.add(hashSingle(NBT.createDouble(1)));

        assertEquals(6, hashes.size());
    }

    @Test
    void emptyContainerTypesHaveDistinctHashes() {
        assertNotEquals(hashSingle(NBT.createCompound()), hashSingle(NBT.createList()));
        assertNotEquals(hashSingle(NBT.createByteArray(new byte[0])), hashSingle(NBT.createIntArray(new int[0])));
        assertNotEquals(hashSingle(NBT.createIntArray(new int[0])), hashSingle(NBT.createLongArray(new long[0])));
    }

    private static SnapshotContentHasher.ContentHash hashSingle(Tag tag) {
        return SnapshotContentHasher.hash(Map.of(FIRST, tag));
    }

    @SafeVarargs
    private static CompoundTag compound(Map.Entry<String, Tag>... entries) {
        LinkedHashMap<String, Tag> tags = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i++) {
            Map.Entry<String, Tag> entry = entries[i];
            tags.put(entry.getKey(), entry.getValue());
        }
        return NBT.createCompound(tags);
    }

    @SafeVarargs
    private static Map<DataKey, Tag> linkedData(Map.Entry<DataKey, Tag>... entries) {
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i++) {
            Map.Entry<DataKey, Tag> entry = entries[i];
            data.put(entry.getKey(), entry.getValue());
        }
        return data;
    }
}
