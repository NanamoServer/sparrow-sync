package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotDataOverlayTest {
    private static final DataKey FIRST = DataKey.of("test", "first");
    private static final DataKey SECOND = DataKey.of("test", "second");
    private static final DataKey ADDED = DataKey.of("test", "added");

    private final SnapshotDataCodec dataCodec = new SnapshotDataCodec(CompressorRegistry.NONE);
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.NONE);

    @Test
    void replacementsAndAppendsKeepSourceAndEarlierViewsUnchanged() throws IOException {
        Snapshot source = this.source();
        LazySnapshotData lazy = (LazySnapshotData) source.content();
        Tag first = NBT.createString("replacement");
        Tag appended = NBT.createInt(3);
        SnapshotData replaced = lazy.with(FIRST, first);
        SnapshotData added = replaced.with(ADDED, appended);
        SnapshotData changedAgain = added.with(FIRST, NBT.createInt(4));
        assertEquals(0, lazy.decodedBlockCount());
        assertSame(first, replaced.get(FIRST));
        assertSame(first, added.get(FIRST));
        assertNull(replaced.get(ADDED));
        assertSame(appended, changedAgain.get(ADDED));
        assertEquals(List.of(FIRST, SECOND, ADDED), new ArrayList<>(changedAgain.keys()));
        assertEquals(NBT.createInt(4), changedAgain.get(FIRST));
        assertNull(changedAgain.raw(FIRST));
        assertNotNull(changedAgain.raw(SECOND));
        assertEquals(-1, changedAgain.rawLength(FIRST));
        assertEquals(0, lazy.decodedBlockCount());
        assertSame(lazy.get(SECOND), added.get(SECOND));
        assertEquals(1, lazy.decodedBlockCount());
        assertEquals("original", lazy.get(FIRST).getAsString());
        assertThrows(UnsupportedOperationException.class, () -> added.keys().clear());
        assertThrows(UnsupportedOperationException.class, () -> added.all().clear());
    }

    @Test
    void wholeRegionCopyKeepsIndexAndBlocksWithoutDecoding() throws IOException {
        Snapshot source = this.source();
        LazySnapshotData lazy = (LazySnapshotData) source.content();
        byte[] original = lazy.frameBytes();
        byte[] dataFrame = this.dataCodec.encode(lazy);
        SnapshotData fromDataFrame = this.dataCodec.decode(dataFrame);
        SnapshotMeta meta = source.meta();
        SnapshotMeta changed = new SnapshotMeta(meta.id(), meta.player(), meta.timestamp(), meta.cause(), true, "longer-server-name", meta.mcDataVersion());
        Snapshot changedMeta = new Snapshot(changed, fromDataFrame);
        byte[] output = new BinarySnapshotCodec(CompressorRegistry.ZSTD, 0).encode(changedMeta);
        assertArrayEquals(Arrays.copyOfRange(original, lazy.frameOffset(), lazy.frameOffset() + lazy.frameLength()), dataFrame);
        assertArrayEquals(dataFrame, Arrays.copyOfRange(output, SnapshotFixtures.dataOffset(output), output.length));
        assertNotEquals(SnapshotFixtures.dataOffset(original), SnapshotFixtures.dataOffset(output));
        assertSame(original, lazy.raw(FIRST).bytes());
        assertSame(dataFrame, ((LazySnapshotData) fromDataFrame).frameBytes());
        assertEquals(0, ((LazySnapshotData) fromDataFrame).frameOffset());
        assertArrayEquals(indexAndBlocks(original), Arrays.copyOfRange(dataFrame, 9, dataFrame.length));
        assertArrayEquals(indexAndBlocks(original), indexAndBlocks(output));
        assertEquals(0, lazy.decodedBlockCount());
        assertEquals(0, ((LazySnapshotData) fromDataFrame).decodedBlockCount());
        assertTrue(assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(output)).snapshot().meta().pinned());
    }

    @Test
    void partialRewriteCopiesUntouchedCorruptBlockWithoutDecodingIt() throws IOException {
        Snapshot source = this.source();
        byte[] bytes = ((LazySnapshotData) source.content()).frameBytes().clone();
        RawBlock second = source.content().raw(SECOND);
        bytes[(int) second.offset() + 13] ^= 1;
        Snapshot damaged = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(bytes)).snapshot();
        SnapshotData modified = damaged.content().with(FIRST, NBT.createString("changed"));
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(new Snapshot(source.meta(), modified)))).snapshot();
        assertEquals(0, ((LazySnapshotData) damaged.content()).decodedBlockCount());
        assertArrayEquals(blockBytes(damaged.content().raw(SECOND)), blockBytes(restored.content().raw(SECOND)));
        assertEquals("changed", restored.data(FIRST).getAsString());
        assertThrows(UncheckedIOException.class, () -> restored.data(SECOND));
    }

    @Test
    void eagerDataUsesTheSameImmutableOverlayContract() {
        Map<DataKey, Tag> values = Map.of(FIRST, NBT.createInt(1));
        SnapshotData eager = EagerSnapshotData.fromTags(values);
        SnapshotData changed = eager.with(FIRST, NBT.createInt(2)).with(ADDED, NBT.createInt(3));
        assertSame(values.get(FIRST), eager.get(FIRST));
        assertEquals(NBT.createInt(2), changed.get(FIRST));
        assertEquals(NBT.createInt(3), changed.get(ADDED));
        assertNull(changed.raw(FIRST));
        assertEquals(-1, eager.rawLength(FIRST));
    }

    @Test
    void bulkOverridesKeepRawBlocksAndOwnTheirMap() throws IOException {
        Snapshot source = this.source();
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        values.put(FIRST, NBT.createString("new"));
        values.put(ADDED, NBT.createInt(3));
        SnapshotData changed = source.content().with(values);
        values.clear();
        assertEquals("new", changed.get(FIRST).getAsString());
        assertEquals(List.of(FIRST, SECOND, ADDED), new ArrayList<>(changed.keys()));
        assertNull(changed.raw(FIRST));
        assertArrayEquals(blockBytes(source.content().raw(SECOND)), blockBytes(changed.raw(SECOND)));
        assertSame(changed, changed.with(Map.of()));
        SnapshotData again = changed.with(Map.of(FIRST, NBT.createString("newer")));
        assertEquals("new", changed.get(FIRST).getAsString());
        assertEquals("newer", again.get(FIRST).getAsString());
        assertEquals(0, ((LazySnapshotData) source.content()).decodedBlockCount());
    }

    @Test
    void subsetOnlyExposesSelectedKeysAndDefersDecoding() throws IOException {
        Snapshot source = this.source();
        SnapshotData subset = source.content().select(SECOND::equals);
        assertEquals(List.of(SECOND), new ArrayList<>(subset.keys()));
        assertNull(subset.get(FIRST));
        assertNull(subset.raw(FIRST));
        assertEquals(-1, subset.rawLength(FIRST));
        assertEquals(source.content().rawLength(SECOND), subset.rawLength(SECOND));
        assertArrayEquals(blockBytes(source.content().raw(SECOND)), blockBytes(subset.raw(SECOND)));
        assertEquals(0, ((LazySnapshotData) source.content()).decodedBlockCount());
        assertThrows(UnsupportedOperationException.class, () -> subset.keys().clear());
        assertEquals(Map.of(SECOND, NBT.createInt(2)), subset.all());
        assertThrows(UnsupportedOperationException.class, () -> subset.all().clear());
        assertSame(EagerSnapshotData.EMPTY, source.content().select(key -> false));
        assertEquals(1, ((LazySnapshotData) source.content()).decodedBlockCount());
    }

    private Snapshot source() throws IOException {
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        values.put(FIRST, NBT.createString("original"));
        values.put(SECOND, NBT.createInt(2));
        return assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(new Snapshot(SnapshotFixtures.meta(), values)))).snapshot();
    }

    private static byte[] indexAndBlocks(byte[] frame) {
        int offset = SnapshotFixtures.dataOffset(frame) + 9;
        return Arrays.copyOfRange(frame, offset, frame.length);
    }

    private static byte[] blockBytes(RawBlock block) {
        return Arrays.copyOfRange(block.bytes(), (int) block.offset(), (int) block.end());
    }
}
