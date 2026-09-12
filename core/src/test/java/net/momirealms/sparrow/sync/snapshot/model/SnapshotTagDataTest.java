package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证直接持有 Tag 的数据体, 子集与覆盖保持顺序, 引用和快照内容语义. */
class SnapshotTagDataTest {
    private static final DataKey FIRST = DataKey.of("test", "first"); // 首个类型, 用于检查替换时的位置
    private static final DataKey SECOND = DataKey.of("test", "second"); // 未修改的类型, 用于检查引用复用
    private static final DataKey ADDED = DataKey.of("test", "added"); // 覆盖操作新增的类型

    /** Eager 的全部数据视图保持只读, 重复访问直接复用同一 Map 和 Tag. */
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

    /**
     * 子集上的连续覆盖保留旧视图, 合并结果经过二进制往返后保持顺序和值.
     *
     * @throws IOException 当测试快照编解码失败时
     */
    @Test
    void subsetAndOverlayPreserveTagsAcrossBinaryRoundTrip() throws IOException {
        Map<DataKey, Tag> original = new LinkedHashMap<>();
        original.put(FIRST, NBT.createInt(1));
        original.put(SECOND, NBT.createInt(2));
        SnapshotData source = new EagerSnapshotData(original);
        SnapshotData subset = source.select(FIRST::equals);
        Tag replacement = NBT.createString("replacement");
        Map<DataKey, Tag> updates = new LinkedHashMap<>();
        updates.put(FIRST, replacement);
        updates.put(ADDED, NBT.createInt(3));
        SnapshotData changed = subset.with(updates);
        updates.clear();
        SnapshotData changedAgain = changed.with(FIRST, NBT.createInt(4));
        assertEquals(List.of(FIRST, ADDED), new ArrayList<>(changedAgain.keys()));
        assertSame(replacement, changed.get(FIRST));
        assertEquals(NBT.createInt(1), subset.get(FIRST));
        assertNull(subset.get(SECOND));
        assertEquals(NBT.createInt(2), source.get(SECOND));
        assertSame(changedAgain, changedAgain.with(Map.of()));
        BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0);
        Snapshot expected = new Snapshot(SnapshotFixtures.meta(), changedAgain);
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(codec.encode(expected))).snapshot();
        assertEquals(0, ((LazySnapshotData) restored.content()).decodedBlockCount());
        assertEquals(List.of(FIRST, ADDED), new ArrayList<>(restored.keys()));
        assertEquals(expected, restored);
    }

    /** Snapshot 的相等和哈希同时取决于快照元数据及各类型 Tag, 与具体数据体实现无关. */
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
