package net.momirealms.sparrow.sync.storage.mysql;

import net.momirealms.sparrow.sync.storage.SnapshotRow;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RowSnapshotCodecTest {
    private final RowSnapshotCodec codec = new RowSnapshotCodec(new SnapshotDataCodec(CompressorRegistry.DEFLATE));

    @Test
    void writingBackLazyDataDoesNotDecodeBlocks() throws Exception {
        SnapshotRow original = this.codec.encode(SnapshotFixtures.snapshot());
        Snapshot lazy = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(original)).snapshot();
        SnapshotRow rewritten = this.codec.encode(lazy);
        assertArrayEquals(original.data(), rewritten.data());
        assertEquals(0, SnapshotFixtures.decodedBlockCount(lazy));
    }

    @ParameterizedTest
    @EnumSource(CompressorRegistry.class)
    void largeRawDataRoundTripsAndOnlyEncodedFrameSizeDependsOnCompression(CompressorRegistry compressor) throws IOException {
        var codec = new RowSnapshotCodec(new SnapshotDataCodec(compressor));
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of(SnapshotFixtures.UNKNOWN_DOC, NBT.createByteArray(new byte[17 * 1024 * 1024])));
        var encoded = codec.encode(snapshot);
        int frameSize = encoded.data().length;
        assertEquals(compressor == CompressorRegistry.NONE, frameSize > 15 * 1024 * 1024);
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(encoded)).snapshot());
    }

    @ParameterizedTest
    @EnumSource(CompressorRegistry.class)
    void everyCompressorCanBeReadByTheSameDecoder(CompressorRegistry compressor) throws IOException {
        Snapshot snapshot = SnapshotFixtures.snapshot();
        SnapshotRow row = new RowSnapshotCodec(new SnapshotDataCodec(compressor)).encode(snapshot);
        assertSame(snapshot.meta(), row.meta());
        assertEquals(SnapshotCodec.CURRENT_VERSION, row.format());
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(row)).snapshot());
    }

    @Test
    void dataFrameKeepsEveryTagTypeAndUnknownKeys() throws IOException {
        CompoundTag data = NBT.createCompound();
        data.putByte("byte", (byte) 1);
        data.putShort("short", (short) 2);
        data.putInt("int", 3);
        data.putLong("long", 4);
        data.putFloat("float", 5.5F);
        data.putDouble("double", 6.5);
        data.putString("string", "大小写 É😀");
        data.putByteArray("bytes", new byte[]{-1, 2});
        data.putIntArray("ints", new int[]{Integer.MIN_VALUE, 3});
        data.putLongArray("longs", new long[]{Long.MIN_VALUE, 4});
        ListTag list = NBT.createList();
        list.add(NBT.createString("nested"));
        data.put("list", list);
        data.put("compound", NBT.createCompound());
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of(SnapshotFixtures.UNKNOWN_DOC, data));
        SnapshotRow row = this.codec.encode(snapshot);
        SnapshotData frame = new SnapshotDataCodec(CompressorRegistry.NONE).decode(row.data());
        assertEquals(1, frame.keys().size());
        assertEquals(data, frame.get(SnapshotFixtures.UNKNOWN_DOC));
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(row)).snapshot());
    }

    @Test
    void emptyDataAndChangedMetadataNeedNoPayloadRewrite() throws IOException {
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of());
        SnapshotRow row = this.codec.encode(snapshot);
        SnapshotRow pinned = new SnapshotRow(row.meta().withPinned(true), row.format(), row.data());
        Snapshot decoded = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(pinned)).snapshot();
        assertTrue(decoded.meta().pinned());
        assertEquals(Map.of(), decoded.allData());
        assertSame(row.data(), pinned.data());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2, 3, 255})
    void unsupportedRowFormatsFailBeforeReadingData(int format) {
        DecodedSnapshot decoded = this.codec.decode(new SnapshotRow(SnapshotFixtures.meta(), format, new byte[0]));
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, decoded).reason());
    }

    @Test
    void malformedFramesPreserveTheFailureReason() throws IOException {
        SnapshotRow row = this.codec.encode(SnapshotFixtures.snapshot());
        assertEquals(InvalidReason.CORRUPTED, this.reason(new byte[]{'S', 'S'}));
        byte[] bytes = row.data().clone();
        bytes[0] = 0;
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, this.reason(bytes));
        bytes = row.data().clone();
        bytes[0] = 99;
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, this.reason(bytes));
        bytes = row.data().clone();
        bytes[0] = 2;
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, this.reason(bytes));
        assertEquals(InvalidReason.CORRUPTED, this.reason(SnapshotFixtures.nonCompoundIndexFrame()));
    }

    @Test
    void uuidEncodingHasStableByteOrder() {
        UUID uuid = UUID.fromString("fedcba98-7654-3210-0123-456789abcdef");
        byte[] bytes = UUIDUtils.toBytes(uuid);
        assertEquals("fedcba98765432100123456789abcdef", HexFormat.of().formatHex(bytes));
        assertEquals(uuid, UUIDUtils.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> UUIDUtils.fromBytes(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> UUIDUtils.fromBytes(new byte[17]));
    }

    private InvalidReason reason(byte[] bytes) {
        return assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(new SnapshotRow(SnapshotFixtures.meta(), SnapshotCodec.CURRENT_VERSION, bytes))).reason();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 99})
    void damagedBlocksFailOnAccessAndLeaveOtherTypesReadable(int corruption) throws IOException {
        Snapshot source = SnapshotFixtures.snapshot();
        SnapshotRow row = this.codec.encode(source);
        int blockBase = SnapshotFixtures.dataBlockBase(row.data());
        if (corruption == 0) {
            row.data()[blockBase + 13] ^= 1;
        } else {
            row.data()[blockBase] = (byte) corruption;
        }
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(row)).snapshot();
        DataKey broken = restored.keys().iterator().next();
        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> restored.data(broken));
        FormatException cause = assertInstanceOf(FormatException.class, failure.getCause());
        assertEquals(corruption == 0 ? InvalidReason.CORRUPTED : InvalidReason.UNSUPPORTED_COMPRESSION, cause.reason());
        assertTrue(cause.getMessage().contains(broken.asString()));
        for (DataKey key : source.keys()) {
            if (!key.equals(broken)) {
                assertEquals(source.data(key), restored.data(key));
            }
        }
    }
}
