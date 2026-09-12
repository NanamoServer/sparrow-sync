package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockIndexCodec;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockMetaCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.codec.upgrade.BlockMetaUpgradePipeline;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证元信息在块模型, 索引和覆盖视图之间完整传递, 与压缩载荷独立读写. */
class BlockMetadataTest {
    private static final DataKey KEY = DataKey.of("test", "state"); // 往返与覆盖使用的单类型标识
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0); // 强制压缩以检查元信息读取不解块

    /**
     * Eager, 子集和覆盖都保留元信息; 完整块覆盖能显式替换它.
     *
     * @throws IOException 当测试快照编解码失败时
     */
    @Test
    void metadataTravelsWithTagAcrossViewsAndEncoding() throws IOException {
        SnapshotData source = new EagerSnapshotData(Map.of(KEY, new SnapshotBlock(BlockMeta.DISCARD_UNKNOWN, NBT.createInt(1))));
        SnapshotData subset = source.select(KEY::equals);
        assertFalse(subset.meta(KEY).keepUnknown());
        assertEquals(BlockMeta.DEFAULT, subset.meta(DataKey.of("missing", "key")));
        SnapshotData changed = subset.with(KEY, NBT.createInt(2)).with(Map.of(KEY, NBT.createInt(3)));
        assertEquals(BlockMeta.DISCARD_UNKNOWN, changed.block(KEY).meta());
        assertEquals(NBT.createInt(1), source.get(KEY));
        Snapshot restored = this.roundTrip(changed);
        assertFalse(restored.content().meta(KEY).keepUnknown());
        assertEquals(0, ((LazySnapshotData) restored.content()).decodedBlockCount());
        assertEquals(NBT.createInt(3), restored.data(KEY));
        SnapshotData replaced = changed.withBlocks(Map.of(KEY, new SnapshotBlock(BlockMeta.DEFAULT, NBT.createInt(4))));
        assertTrue(replaced.meta(KEY).keepUnknown());
        assertFalse(changed.meta(KEY).keepUnknown());
        assertEquals(BlockMeta.DEFAULT, EagerSnapshotData.fromTags(Map.of(KEY, NBT.createInt(1))).meta(KEY));
    }

    /**
     * 索引仅包含位置, 长度和独立 meta, 元信息版本及布尔字段接受严格类型检查.
     *
     * @throws IOException 当测试索引读取失败时
     */
    @Test
    void indexHasIndependentMetadataAndNoCompressorOrPolicyField() throws IOException {
        LinkedHashMap<String, BlockIndex> values = new LinkedHashMap<>();
        values.put(KEY.asString(), new BlockIndex(0, 20, 50, BlockMeta.DISCARD_UNKNOWN));
        CompoundTag tree = BlockIndexCodec.write(values);
        CompoundTag entry = tree.getCompound(KEY.asString());
        assertEquals(4, entry.size());
        assertFalse(entry.containsKey("c"));
        assertFalse(entry.containsKey("r"));
        assertFalse(entry.containsKey("v"));
        assertEquals(1, entry.getCompound("meta").getInt("version"));
        assertFalse(entry.getCompound("meta").getBoolean("keepUnknown"));
        assertEquals(values, BlockIndexCodec.read(tree));
        entry.getCompound("meta").putInt("keepUnknown", 1);
        assertEquals(InvalidReason.CORRUPTED, assertThrows(FormatException.class, () -> BlockIndexCodec.read(tree)).reason());
        entry.remove("meta");
        assertEquals(BlockMeta.DEFAULT, BlockIndexCodec.read(tree).get(KEY.asString()).meta());
    }

    /**
     * 元信息版本由自身管线检查, 当前版本直接返回, 未实现的未来版本明确拒绝.
     *
     * @throws IOException 当当前元信息读取失败时
     */
    @Test
    void metadataVersionChecksAreIndependentOfSnapshotVersion() throws IOException {
        CompoundTag tag = BlockMetaCodec.write(BlockMeta.DISCARD_UNKNOWN);
        Document document = BlockMetaCodec.toJson(BlockMeta.DISCARD_UNKNOWN);
        assertSame(tag, BlockMetaUpgradePipeline.upgrade(tag, 1));
        assertSame(document, BlockMetaUpgradePipeline.upgrade(document, 1));
        assertEquals(BlockMeta.DISCARD_UNKNOWN, BlockMetaCodec.read(tag));
        assertEquals(BlockMeta.DISCARD_UNKNOWN, BlockMetaCodec.read(document));
        assertThrows(FormatException.class, () -> BlockMetaUpgradePipeline.upgrade(tag, 0));
        tag.putInt("version", 2);
        document.put("version", 2);
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertThrows(FormatException.class, () -> BlockMetaCodec.read(tag)).reason());
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertThrows(FormatException.class, () -> BlockMetaCodec.read(document)).reason());
    }

    /**
     * 模拟升级后的元信息, 写回必须重建索引, 原始载荷仍逐字节复制.
     *
     * @throws IOException 当测试快照编解码失败时
     */
    @Test
    void upgradedMetadataRebuildsIndexWithoutReencodingPayload() throws IOException {
        Snapshot source = this.roundTrip(EagerSnapshotData.fromTags(Map.of(KEY, NBT.createString("opaque"))));
        LazySnapshotData original = (LazySnapshotData) source.content();
        RawBlock raw = source.content().raw(KEY);
        BlockIndex index = raw.index();
        LinkedHashMap<String, BlockIndex> upgradedIndex = new LinkedHashMap<>();
        upgradedIndex.put(KEY.asString(), new BlockIndex(index.offset(), index.length(), index.rawLength(), BlockMeta.DISCARD_UNKNOWN));
        LazySnapshotData upgraded = new LazySnapshotData(original.frameBytes(), original.frameOffset(), original.frameLength(), SnapshotFixtures.blockBase(original.frameBytes()), upgradedIndex, true);
        Snapshot restored = this.roundTrip(upgraded);
        assertFalse(restored.content().meta(KEY).keepUnknown());
        RawBlock copied = restored.content().raw(KEY);
        assertArrayEquals(Arrays.copyOfRange(raw.bytes(), (int) raw.offset(), (int) raw.offset() + 9 + index.length()),
                Arrays.copyOfRange(copied.bytes(), (int) copied.offset(), (int) copied.offset() + 9 + copied.index().length()));
        assertEquals(0, original.decodedBlockCount());
        assertEquals(0, upgraded.decodedBlockCount());
        assertEquals(0, ((LazySnapshotData) restored.content()).decodedBlockCount());
    }

    /**
     * 快照相等判断包含块元信息, 相同 Tag 的不同保留声明属于不同内容.
     */
    @Test
    void snapshotEqualityIncludesBlockMetadata() {
        Snapshot keep = new Snapshot(SnapshotFixtures.meta(), Map.of(KEY, NBT.createInt(1)));
        Snapshot discard = new Snapshot(keep.meta(), new EagerSnapshotData(Map.of(KEY, new SnapshotBlock(BlockMeta.DISCARD_UNKNOWN, NBT.createInt(1)))));
        assertNotEquals(keep, discard);
    }

    /**
     * 对数据体执行一次二进制往返.
     *
     * @param data 要写出的完整数据体
     * @return 尚未解析 payload 的快照
     * @throws IOException 当编码失败时
     */
    private Snapshot roundTrip(SnapshotData data) throws IOException {
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), data);
        return assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();
    }
}
