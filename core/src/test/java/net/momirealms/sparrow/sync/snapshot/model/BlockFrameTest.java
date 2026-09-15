package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockCodec;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockIndexCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class BlockFrameTest {
    private final SnapshotDataCodec dataCodec = new SnapshotDataCodec(CompressorRegistry.NONE);
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE);

    @Test
    void eachDamagedBlockIsIsolated() throws IOException {
        Snapshot source = SnapshotFixtures.snapshot();
        byte[] original = this.codec.encode(source);
        for (Map.Entry<String, BlockIndex> item : entries(original).entrySet()) {
            byte[] damaged = original.clone();
            damaged[base(damaged) + item.getValue().offset() + 13] ^= 1;
            Snapshot restored = this.valid(damaged);
            DataKey broken = DataKey.parse(item.getKey());
            assertBlockFailure(restored, broken, InvalidReason.CORRUPTED);
            for (DataKey key : source.keys()) {
                if (!key.equals(broken)) {
                    assertEquals(source.data(key), restored.data(key));
                }
            }
            assertThrows(UncheckedIOException.class, restored::allData);
        }
    }

    @Test
    void truncationReportsIndividualKeys() throws IOException {
        Snapshot source = SnapshotFixtures.snapshot();
        byte[] original = this.codec.encode(source);
        LinkedHashMap<String, BlockIndex> entries = entries(original);
        for (BlockIndex cut : entries.values()) {
            int length = base(original) + cut.offset() + 13 + ByteBuffer.wrap(original).getInt(base(original) + cut.offset() + 1) / 2;
            Snapshot restored = this.valid(Arrays.copyOf(original, length));
            for (Map.Entry<String, BlockIndex> item : entries.entrySet()) {
                DataKey key = DataKey.parse(item.getKey());
                if (base(original) + item.getValue().offset() + 13 + ByteBuffer.wrap(original).getInt(base(original) + item.getValue().offset() + 1) <= length) {
                    assertEquals(source.data(key), restored.data(key));
                } else {
                    assertBlockFailure(restored, key, InvalidReason.CORRUPTED);
                }
            }
        }
    }

    @Test
    void indexChecksumRejectsWholeFrame() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        bytes[SnapshotFixtures.dataOffset(bytes) + 9] ^= 1;
        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(bytes));
        assertEquals(InvalidReason.CORRUPTED, invalid.reason());
        assertTrue(invalid.detail().contains("index"));
    }

    @Test
    void unsupportedDataVersionsAreRejected() throws IOException {
        for (int version : new int[]{0, 99}) {
            byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
            bytes[SnapshotFixtures.dataOffset(bytes)] = (byte) version;
            assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(bytes)).reason());
        }
    }

    @Test
    void lazyCachePreservesIdentityAcrossThreads() throws Exception {
        Snapshot source = SnapshotFixtures.snapshot();
        Snapshot restored = this.valid(this.codec.encode(source));
        LazySnapshotData data = assertInstanceOf(LazySnapshotData.class, restored.content());
        assertEquals(source.keys(), data.keys());
        assertNull(data.get(DataKey.parse("unknown:absent")));
        assertEquals(0, data.decodedBlockCount());
        DataKey key = source.keys().iterator().next();
        List<CompletableFuture<Tag>> reads = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            reads.add(CompletableFuture.supplyAsync(() -> data.get(key)));
        }
        Tag first = reads.getFirst().get();
        for (int i = 0; i < reads.size(); i++) {
            assertSame(first, reads.get(i).get());
        }
        assertEquals(1, data.decodedBlockCount());
        assertEquals(source.allData(), data.all());
        assertEquals(source.keys().size(), data.decodedBlockCount());
    }

    @Test
    void unknownTypesAndPhysicalOrderSurviveRoundTrip() throws IOException {
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        values.put(DataKey.parse("third:z"), NBT.createInt(4));
        values.put(DataKey.parse("third:a"), NBT.createString("kept"));
        Snapshot source = new Snapshot(SnapshotFixtures.meta(), values);
        byte[] bytes = this.codec.encode(source);
        Snapshot restored = this.valid(bytes);
        assertEquals(new ArrayList<>(source.keys()), new ArrayList<>(restored.keys()));
        assertEquals(source, restored);
        assertArrayEquals(bytes, this.codec.encode(restored));
        for (BlockIndex entry : entries(bytes).values()) {
            assertEquals(CompressorRegistry.NONE.id(), bytes[base(bytes) + entry.offset()]);
        }
    }

    @Test
    void emptyAndDataOnlyFramesRoundTrip() throws IOException {
        Snapshot empty = new Snapshot(SnapshotFixtures.meta(), Map.of());
        byte[] bytes = this.codec.encode(empty);
        assertTrue(entries(bytes).isEmpty());
        assertEquals(base(bytes), bytes.length);
        assertEquals(empty, this.valid(bytes));
        DataKey key = DataKey.parse("other:data");
        Map<DataKey, Tag> data = Map.of(key, NBT.createInt(8));
        byte[] framed = this.dataCodec.encode(EagerSnapshotData.fromTags(data));
        assertEquals(1, framed[0]);
        assertEquals(9 + ByteBuffer.wrap(framed).getInt(1), SnapshotFixtures.dataBlockBase(framed));
        LazySnapshotData restored = assertInstanceOf(LazySnapshotData.class, this.dataCodec.decode(framed));
        assertEquals(data.keySet(), restored.keys());
        assertEquals(0, restored.decodedBlockCount());
        assertEquals(data, restored.all());
        assertEquals(1, restored.decodedBlockCount());
        assertSame(restored.get(key), restored.get(key));
        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(framed)).reason());
    }

    @Test
    void dataFrameReaderRejectsSnapshotContainer() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        FormatException failure = assertThrows(FormatException.class, () -> this.dataCodec.decode(bytes));
        assertEquals(InvalidReason.CORRUPTED, failure.reason());
    }

    @Test
    void unknownCompressorIsLocalToBlock() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        var item = entries(bytes).entrySet().iterator().next();
        bytes[base(bytes) + item.getValue().offset()] = 99;
        assertBlockFailure(this.valid(bytes), DataKey.parse(item.getKey()), InvalidReason.UNSUPPORTED_COMPRESSION);
    }

    @Test
    void blockRoundTripAndThreshold() throws IOException {
        String key = "other:value";
        Tag value = NBT.createString("data".repeat(100));
        CompoundTag tree = NBT.createCompound();
        tree.put(key, value);
        int rawLength = NBT.toBytes(tree, false).length;
        for (CompressorRegistry compressor : CompressorRegistry.values()) {
            byte[] block = BlockCodec.encode(key, value, compressor, rawLength);
            RawBlock raw = new RawBlock(block, 0, block.length);
            assertEquals(13, BlockCodec.BLOCK_HEADER_LENGTH);
            assertEquals(block.length - 13, ByteBuffer.wrap(block).getInt(1));
            assertEquals(rawLength, ByteBuffer.wrap(block).getInt(5));
            assertEquals(value, BlockCodec.decode(raw, key));
            assertEquals(compressor.id(), block[0]);
            assertEquals(CompressorRegistry.NONE.id(), BlockCodec.encode(key, value, compressor, rawLength + 1)[0]);
        }
    }

    @Test
    void blockLengthsAndOffsetsAreChecked() throws IOException {
        String key = "other:value";
        byte[] block = BlockCodec.encode(key, NBT.createInt(3), CompressorRegistry.NONE, 256);
        int raw = ByteBuffer.wrap(block).getInt(5);
        ByteBuffer.wrap(block).putInt(5, raw + 1);
        assertEquals(InvalidReason.CORRUPTED, assertThrows(FormatException.class,
                () -> BlockCodec.decode(new RawBlock(block, 0, block.length), key)).reason());
        assertEquals(InvalidReason.CORRUPTED, assertThrows(FormatException.class,
                () -> BlockCodec.decode(new RawBlock(block, 14L + Integer.MAX_VALUE, 28L + Integer.MAX_VALUE), key)).reason());
    }

    @Test
    void malformedBlockRootsAreCorrupted() throws IOException {
        String key = "other:value";
        CompoundTag wrong = NBT.createCompound();
        wrong.put("other:wrong", NBT.createInt(1));
        CompoundTag multiple = NBT.createCompound();
        multiple.put(key, NBT.createInt(1));
        multiple.put("other:extra", NBT.createInt(2));
        List<Tag> roots = List.of(NBT.createString("invalid"), NBT.createCompound(), wrong, multiple);
        for (int i = 0; i < roots.size(); i++) {
            byte[] raw = NBT.toBytes(roots.get(i), false);
            CRC32 crc = new CRC32();
            crc.update(raw);
            byte[] block = ByteBuffer.allocate(13 + raw.length).put((byte) 0).putInt(raw.length).putInt(raw.length).putInt((int) crc.getValue()).put(raw).array();
            RawBlock entry = new RawBlock(block, 0, block.length);
            assertEquals(InvalidReason.CORRUPTED, assertThrows(FormatException.class, () -> BlockCodec.decode(entry, key)).reason());
        }
    }

    @Test
    void indexRoundTripAndValidation() throws IOException {
        LinkedHashMap<String, BlockIndex> values = new LinkedHashMap<>();
        values.put("other:z", new BlockIndex(0));
        values.put("other:a", new BlockIndex(25));
        assertInstanceOf(IntTag.class, BlockIndexCodec.write(values).get("other:z"));
        assertEquals(25, BlockIndexCodec.write(values).getInt("other:a"));
        assertEquals(values, BlockIndexCodec.read(BlockIndexCodec.write(values)));
        assertEquals(new ArrayList<>(values.keySet()), new ArrayList<>(BlockIndexCodec.read(BlockIndexCodec.write(values)).keySet()));
        for (Tag invalid : List.of(NBT.createCompound(), NBT.createLong(0), NBT.createString("0"), NBT.createInt(-1))) {
            CompoundTag index = BlockIndexCodec.write(values);
            index.put("other:z", invalid);
            assertThrows(FormatException.class, () -> BlockIndexCodec.read(index));
        }
    }

    @Test
    void unsignedIndexLengthIsChecked() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        ByteBuffer.wrap(bytes).putInt(SnapshotFixtures.dataOffset(bytes) + 1, -1);
        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(bytes)).reason());
    }

    private Snapshot valid(byte[] bytes) {
        return assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(bytes)).snapshot();
    }

    private static int base(byte[] bytes) {
        return SnapshotFixtures.blockBase(bytes);
    }

    private static LinkedHashMap<String, BlockIndex> entries(byte[] bytes) throws IOException {
        int dataOffset = SnapshotFixtures.dataOffset(bytes);
        int indexLength = ByteBuffer.wrap(bytes).getInt(dataOffset + 1);
        CompoundTag tree = (CompoundTag) NBT.readUnnamedTag(new DataInputStream(new ByteArrayInputStream(bytes, dataOffset + 9, indexLength)), false);
        return BlockIndexCodec.read(tree);
    }

    private static void assertBlockFailure(Snapshot snapshot, DataKey key, InvalidReason reason) {
        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> snapshot.data(key));
        FormatException cause = assertInstanceOf(FormatException.class, failure.getCause());
        assertEquals(reason, cause.reason());
        assertTrue(cause.getMessage().contains(key.asString()));
    }
}
