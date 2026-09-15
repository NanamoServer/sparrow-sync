package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class BlockHeaderTest {
    private static final DataKey FIRST = DataKey.of("test", "first");
    private static final DataKey LAST = DataKey.of("test", "last");

    private final SnapshotDataCodec codec = new SnapshotDataCodec(CompressorRegistry.DEFLATE, 0);

    @ParameterizedTest
    @CsvSource({"1,-1", "1,0", "1,2147483647", "5,-1"})
    void malformedLengthsFailOnlyWhenTheBlockIsAccessed(int field, int value) throws IOException {
        byte[] bytes = this.frame();
        RawBlock last = this.codec.decode(bytes).raw(LAST);
        ByteBuffer.wrap(bytes).putInt((int) last.offset() + field, value);
        LazySnapshotData data = (LazySnapshotData) this.codec.decode(bytes);
        assertEquals(List.of(FIRST, LAST), List.copyOf(data.keys()));
        assertEquals(0, data.decodedBlockCount());
        assertTrue(data.rawLength(FIRST) > 0);
        assertEquals(0, data.decodedBlockCount());
        assertTrue(assertThrows(UncheckedIOException.class, () -> data.rawLength(LAST)).getMessage().contains(LAST.asString()));
        assertTrue(assertThrows(UncheckedIOException.class, () -> data.get(LAST)).getMessage().contains(LAST.asString()));
        assertTrue(assertThrows(FormatException.class, () -> this.codec.encode(data.select(LAST::equals))).getMessage().contains(LAST.asString()));
        assertEquals(NBT.createString("first"), data.get(FIRST));
        assertArrayEquals(bytes, this.codec.encode(data));
    }

    @Test
    void everyTruncatedHeaderIsReportedLocally() throws IOException {
        byte[] original = this.frame();
        int lastOffset = (int) this.codec.decode(original).raw(LAST).offset();
        for (int kept = 0; kept < 13; kept++) {
            SnapshotData data = this.codec.decode(Arrays.copyOf(original, lastOffset + kept));
            assertEquals(NBT.createString("first"), data.get(FIRST));
            assertTrue(assertThrows(UncheckedIOException.class, () -> data.rawLength(LAST)).getMessage().contains(LAST.asString()));
            assertThrows(UncheckedIOException.class, () -> data.get(LAST));
            assertThrows(FormatException.class, () -> this.codec.encode(data.select(LAST::equals)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copyingUnknownCompressionDoesNotValidatePayload(boolean badCrc) throws IOException {
        byte[] bytes = this.frame();
        RawBlock last = this.codec.decode(bytes).raw(LAST);
        bytes[(int) last.offset()] = 99;
        if (badCrc) {
            bytes[(int) last.offset() + 13] ^= 1;
        }
        LazySnapshotData data = (LazySnapshotData) this.codec.decode(bytes);
        assertTrue(data.rawLength(LAST) > 0);
        SnapshotData changed = data.with(FIRST, NBT.createString("replacement"));
        SnapshotData restored = this.codec.decode(this.codec.encode(changed));
        RawBlock copied = restored.raw(LAST);
        assertArrayEquals(Arrays.copyOfRange(bytes, (int) last.offset(), (int) last.end()),
                Arrays.copyOfRange(copied.bytes(), (int) copied.offset(), (int) copied.end()));
        assertEquals(0, data.decodedBlockCount());
        assertEquals(NBT.createString("replacement"), restored.get(FIRST));
        assertThrows(UncheckedIOException.class, () -> restored.get(LAST));
    }

    @Test
    void offsetsAreSortedAndDuplicateOrNegativeOffsetsAreRejected() throws IOException {
        byte[] frame = this.frame();
        SnapshotData source = this.codec.decode(frame);
        int base = SnapshotFixtures.dataBlockBase(frame);
        int secondOffset = (int) source.raw(LAST).offset() - base;
        CompoundTag index = NBT.createCompound(new LinkedHashMap<>());
        index.putInt(LAST.asString(), secondOffset);
        index.putInt(FIRST.asString(), 0);
        SnapshotData restored = this.codec.decode(reindex(frame, index));
        assertEquals(List.of(FIRST, LAST), List.copyOf(restored.keys()));
        assertEquals(source.all(), restored.all());
        index.putInt(LAST.asString(), 0);
        assertThrows(FormatException.class, () -> this.codec.decode(reindex(frame, index)));
        index.putInt(LAST.asString(), -1);
        assertThrows(FormatException.class, () -> this.codec.decode(reindex(frame, index)));
    }

    @Test
    void trailingBytesDisagreeWithTheLastBlockHeader() throws IOException {
        byte[] frame = this.frame();
        SnapshotData data = this.codec.decode(Arrays.copyOf(frame, frame.length + 1));
        assertEquals(NBT.createString("first"), data.get(FIRST));
        assertThrows(UncheckedIOException.class, () -> data.get(LAST));
        assertThrows(FormatException.class, () -> this.codec.encode(data.select(LAST::equals)));
    }

    private byte[] frame() throws IOException {
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        values.put(FIRST, NBT.createString("first"));
        values.put(LAST, NBT.createString("last"));
        return this.codec.encode(new EagerSnapshotData(values));
    }

    private static byte[] reindex(byte[] frame, CompoundTag index) throws IOException {
        byte[] encoded = NBT.toBytes(index, false);
        int base = SnapshotFixtures.dataBlockBase(frame);
        CRC32 crc = new CRC32();
        crc.update(encoded);
        return ByteBuffer.allocate(9 + encoded.length + frame.length - base)
                .put((byte) 1)
                .putInt(encoded.length).putInt((int) crc.getValue()).put(encoded)
                .put(frame, base, frame.length - base).array();
    }
}
