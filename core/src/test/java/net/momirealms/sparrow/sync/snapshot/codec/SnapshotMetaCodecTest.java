package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotMetaCodecTest {
    @Test
    void metadataRoundTripsIndependently() throws IOException {
        SnapshotMeta meta = SnapshotFixtures.meta().withPinned(true);
        byte[] bytes = SnapshotMetaCodec.encode(meta);
        assertEquals(meta, SnapshotMetaCodec.decode(bytes));
        CompoundTag tree = SnapshotMetaCodec.toCompoundTag(meta);
        assertEquals(7, tree.size());
        assertFalse(tree.containsKey("data"));
    }

    @Test
    void metadataRequiresExactlyOneCompound() throws IOException {
        byte[] bytes = SnapshotMetaCodec.encode(SnapshotFixtures.meta());
        assertThrows(IOException.class, () -> SnapshotMetaCodec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IOException.class, () -> SnapshotMetaCodec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IOException.class, () -> SnapshotMetaCodec.decode(NBT.toBytes(NBT.createInt(1), false)));
    }

    @Test
    void metadataRequiresBothIdentities() throws IOException {
        for (String field : new String[]{"id", "player"}) {
            CompoundTag tree = SnapshotMetaCodec.toCompoundTag(SnapshotFixtures.meta());
            tree.remove(field);
            IOException failure = assertThrows(IOException.class, () -> SnapshotMetaCodec.decode(NBT.toBytes(tree, false)));
            assertTrue(failure.getMessage().contains(field));
        }
    }
}
