package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotSummaryTest {
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0);

    @Test
    void sizesMatchSerializedNbtAndKeepPhysicalOrder() throws IOException {
        Snapshot source = SnapshotFixtures.snapshot();
        Map<DataKey, Integer> expected = new LinkedHashMap<>();
        for (DataKey key : source.keys()) {
            var single = NBT.createCompound();
            single.put(key.asString(), source.data(key));
            expected.put(key, NBT.toBytes(single, false).length);
        }
        var summary = this.codec.summarize(this.codec.encode(source));
        assertEquals(expected, summary);
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(summary.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> summary.clear());
        assertEquals(Map.of(), this.codec.summarize(this.codec.encode(new Snapshot(source.meta(), Map.of()))));
    }

    @Test
    void nonSnapshotInputsHaveNoSummary() throws IOException {
        assertNull(assertDoesNotThrow(() -> this.codec.summarize("{\"data\":{}}".getBytes(StandardCharsets.UTF_8))));
        byte[] standalone = new SnapshotDataCodec(CompressorRegistry.DEFLATE).encode(SnapshotFixtures.snapshot().content());
        assertNull(assertDoesNotThrow(() -> this.codec.summarize(standalone)));
        Random random = new Random(7);
        for (int length : new int[]{0, 1, 6, 7, 9, 32, 300}) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            assertNull(assertDoesNotThrow(() -> this.codec.summarize(bytes)));
        }
        byte[] bytes = new byte[300];
        random.nextBytes(bytes);
        ByteBuffer.wrap(bytes).put((byte) 1).putShort((short) 1).putInt(0).put((byte) 0)
                .put((byte) 1).putInt(20).putInt(0);
        assertNull(assertDoesNotThrow(() -> this.codec.summarize(bytes)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 255})
    void unsupportedFrameVersionsHaveNoSummary(int version) throws IOException {
        byte[] source = this.codec.encode(SnapshotFixtures.snapshot());
        for (int offset : new int[]{0, SnapshotFixtures.dataOffset(source)}) {
            byte[] bytes = source.clone();
            bytes[offset] = (byte) version;
            assertNull(assertDoesNotThrow(() -> this.codec.summarize(bytes)));
        }
    }

    @Test
    void invalidSegmentLengthsAndTruncationHaveNoSummary() throws IOException {
        byte[] source = this.codec.encode(SnapshotFixtures.snapshot());
        for (int length : new int[]{0, 65535, source.length - 7}) {
            byte[] bytes = source.clone();
            ByteBuffer.wrap(bytes).putShort(1, (short) length);
            assertNull(assertDoesNotThrow(() -> this.codec.summarize(bytes)));
        }
        int dataOffset = SnapshotFixtures.dataOffset(source);
        for (int length : new int[]{0, 6, dataOffset - 1, dataOffset + 8, SnapshotFixtures.blockBase(source) - 1}) {
            assertNull(assertDoesNotThrow(() -> this.codec.summarize(Arrays.copyOf(source, length))));
        }
        ByteBuffer.wrap(source).putInt(dataOffset + 1, -1);
        assertNull(this.codec.summarize(source));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 7})
    void damagedMetaDoesNotDiscardIntactDataSummary(int position) throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        var expected = this.codec.summarize(bytes);
        bytes[position] ^= 0x7F;
        assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(bytes));
        assertEquals(expected, assertDoesNotThrow(() -> this.codec.summarize(bytes)));
    }

    @Test
    void corruptChecksumAndInvalidIndexHaveNoSummary() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        int offset = SnapshotFixtures.dataOffset(bytes);
        bytes[offset + 5] ^= 1;
        assertNull(this.codec.summarize(bytes));
        byte[] invalid = SnapshotFixtures.nonCompoundIndexFrame();
        byte[] malformed = Arrays.copyOf(bytes, offset + invalid.length);
        System.arraycopy(invalid, 0, malformed, offset, invalid.length);
        assertNull(assertDoesNotThrow(() -> this.codec.summarize(malformed)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"payload", "raw", "truncated"})
    void badBlockHeaderOnlyLosesItsOwnSize(String damage) throws IOException {
        byte[] source = this.codec.encode(SnapshotFixtures.snapshot());
        Snapshot located = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(source)).snapshot();
        DataKey last = new ArrayList<>(located.keys()).getLast();
        Map<DataKey, Integer> expected = new LinkedHashMap<>(this.codec.summarize(source));
        int offset = (int) located.content().raw(last).offset();
        byte[] bytes = damage.equals("truncated") ? Arrays.copyOf(source, offset + 12) : source.clone();
        if (!damage.equals("truncated")) {
            ByteBuffer.wrap(bytes).putInt(offset + (damage.equals("payload") ? 1 : 5), -1);
        }
        expected.put(last, -1);
        assertEquals(expected, assertDoesNotThrow(() -> this.codec.summarize(bytes)));
    }

    @Test
    void unknownCompressionAndPayloadDamageStillKeepDeclaredSizes() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        Map<DataKey, Integer> expected = new LinkedHashMap<>(this.codec.summarize(bytes));
        DataKey first = expected.keySet().iterator().next();
        int offset = SnapshotFixtures.blockBase(bytes);
        bytes[offset] = 99;
        bytes[offset + 13] ^= 1;
        ByteBuffer.wrap(bytes).putInt(offset + 5, 0);
        expected.put(first, 0);
        assertEquals(expected, this.codec.summarize(bytes));
    }
}
