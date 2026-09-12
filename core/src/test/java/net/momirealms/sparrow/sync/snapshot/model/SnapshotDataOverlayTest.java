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

/** 验证 with 只替换指定类型, 保留原对象, 并在保存时直接复制其他类型的数据块. */
class SnapshotDataOverlayTest {
    private static final DataKey FIRST = DataKey.of("test", "first"); // 原快照中第一个类型, 测试会替换它的值
    private static final DataKey SECOND = DataKey.of("test", "second"); // 原快照中第二个类型, 用来检查未修改的数据块是否保持原样
    private static final DataKey ADDED = DataKey.of("test", "added"); // 原快照中不存在的类型, 用来检查追加后是否排在末尾

    private final SnapshotDataCodec dataCodec = new SnapshotDataCodec(CompressorRegistry.NONE); // 读写独立数据帧
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.NONE); // 不压缩, 便于直接修改测试数据并比较保存前后的字节

    /**
     * 连续调用 with 替换和追加类型, 验证原对象和先前返回的对象仍能读到各自的值.
     * 未修改的类型仍能取得原始数据块, 读取时与原对象共用同一个缓存 Tag.
     *
     * @throws IOException 当测试快照编解码失败时
     */
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

    /**
     * 提取完整快照内的数据帧保存到数据库, 再与修改后的元数据组合成完整快照.
     * 验证两次编码都保留原索引和数据块的全部字节, 且没有将数据块解析为 Tag.
     *
     * @throws IOException 当测试快照编解码失败时
     */
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
        assertArrayEquals(indexAndBlocks(original), indexAndBlocks(dataFrame));
        assertArrayEquals(indexAndBlocks(original), indexAndBlocks(output));
        assertEquals(0, lazy.decodedBlockCount());
        assertEquals(0, ((LazySnapshotData) fromDataFrame).decodedBlockCount());
        assertTrue(assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(output)).snapshot().meta().pinned());
    }

    /**
     * 修改第一个类型后保存快照, 验证新值能正常读回, 第二个类型的损坏字节仍保持原样.
     * 保存时不应读取第二个块; 显式读取该类型的值时才会报告损坏.
     *
     * @throws IOException 当测试快照编解码失败时
     */
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

    /** 验证直接持有 Tag 的 EagerSnapshotData 也能通过 with 替换和追加值, 原对象和传入的 Map 保持不变. */
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

    /**
     * 批量覆盖保留原位置并复制输入 Map, 连续覆盖仍能复用未修改块.
     *
     * @throws IOException 当测试快照编解码失败时
     */
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

    /**
     * 子集隐藏未选中的键, 选中类型保留原始块, 空子集释放来源引用.
     *
     * @throws IOException 当测试快照编解码失败时
     */
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

    /**
     * 创建按 FIRST, SECOND 排列的快照, 编码后再读回, 使两个类型都保留为尚未解析的数据块.
     *
     * @return content 为 LazySnapshotData 的快照, FIRST 的值为字符串 original, SECOND 的值为整数 2
     * @throws IOException 当测试快照编码失败时
     */
    private Snapshot source() throws IOException {
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        values.put(FIRST, NBT.createString("original"));
        values.put(SECOND, NBT.createInt(2));
        return assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(new Snapshot(SnapshotFixtures.meta(), values)))).snapshot();
    }

    /**
     * 复制从索引起点到帧末尾的字节, 供测试比较修改元数据前后的索引和数据块.
     *
     * @param frame 独立数据帧或包含数据帧的完整快照
     * @return 索引和所有数据块的字节副本, 不含帧头及元数据
     */
    private static byte[] indexAndBlocks(byte[] frame) {
        int offset = SnapshotFixtures.dataOffset(frame) + 11;
        return Arrays.copyOfRange(frame, offset, frame.length);
    }

    /**
     * 复制指定数据块的全部字节, 包括保存 CRC 的 13 字节块头和后面的 NBT 数据.
     *
     * @param block 指明原数组, 块起点及长度的原始数据块
     * @return 该块的独立字节副本, 供测试比较修改快照前后是否一致
     */
    private static byte[] blockBytes(RawBlock block) {
        return Arrays.copyOfRange(block.bytes(), (int) block.offset(), (int) block.end());
    }
}
