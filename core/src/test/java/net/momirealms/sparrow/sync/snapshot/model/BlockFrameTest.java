package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖分块容器的字节布局, 惰性缓存和损坏边界, 直接修改帧来模拟存储损坏. */
class BlockFrameTest {
    private final SnapshotDataCodec dataCodec = new SnapshotDataCodec(CompressorRegistry.NONE); // 读写独立数据帧
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE); // 固定写入算法

    /**
     * 每轮仅破坏一个 payload, 校验全部其他类型仍可读取.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void eachDamagedBlockIsIsolated() throws IOException {
        Snapshot source = SnapshotFixtures.snapshot();
        byte[] original = this.codec.encode(source);
        for (Map.Entry<String, BlockIndex> item : entries(original).entrySet()) {
            byte[] damaged = original.clone();
            damaged[base(damaged) + item.getValue().offset() + 9] ^= 1;
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

    /**
     * 在每个块内截断, 已完整保留的块仍可读, 后续块分别报告自己的名字.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void truncationReportsIndividualKeys() throws IOException {
        Snapshot source = SnapshotFixtures.snapshot();
        byte[] original = this.codec.encode(source);
        LinkedHashMap<String, BlockIndex> entries = entries(original);
        for (BlockIndex cut : entries.values()) {
            int length = base(original) + cut.offset() + 9 + cut.length() / 2;
            Snapshot restored = this.valid(Arrays.copyOf(original, length));
            for (Map.Entry<String, BlockIndex> item : entries.entrySet()) {
                DataKey key = DataKey.parse(item.getKey());
                if (base(original) + item.getValue().offset() + 9 + item.getValue().length() <= length) {
                    assertEquals(source.data(key), restored.data(key));
                } else {
                    assertBlockFailure(restored, key, InvalidReason.CORRUPTED);
                }
            }
        }
    }

    /**
     * 索引损坏会使寻址失去依据, 整份容器应在读取块前拒绝.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void indexChecksumRejectsWholeFrame() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        bytes[SnapshotFixtures.dataOffset(bytes) + 11] ^= 1;
        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(bytes));
        assertEquals(InvalidReason.CORRUPTED, invalid.reason());
        assertTrue(invalid.detail().contains("index"));
    }

    /**
     * 完整快照内的数据帧也检查版本, 过早或未来版本在解块前拒绝.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void unsupportedDataVersionsAreRejected() throws IOException {
        for (int version : new int[]{0, 99}) {
            byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
            bytes[SnapshotFixtures.dataOffset(bytes) + 2] = (byte) version;
            assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(bytes)).reason());
        }
    }

    /**
     * 读取键集合不解块, 并发取同一类型也只缓存一个 Tag 实例.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
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

    /**
     * 陌生类型仍按原顺序保存, 索引只描述块的位置和编码信息.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
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

    /**
     * 空索引没有数据块, 完整快照和数据库数据帧均可往返.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
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
        assertEquals('S', framed[0]);
        assertEquals('D', framed[1]);
        LazySnapshotData restored = assertInstanceOf(LazySnapshotData.class, this.dataCodec.decode(framed));
        assertEquals(data.keySet(), restored.keys());
        assertEquals(0, restored.decodedBlockCount());
        assertEquals(data, restored.all());
        assertEquals(1, restored.decodedBlockCount());
        assertSame(restored.get(key), restored.get(key));
        assertEquals(InvalidReason.BAD_MAGIC, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(framed)).reason());
    }

    /**
     * 数据帧入口拒绝含元数据的完整快照, 让两种容器的用途保持明确.
     *
     * @throws IOException 当测试快照编码失败时
     */
    @Test
    void dataFrameReaderRejectsSnapshotContainer() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        FormatException failure = assertThrows(FormatException.class, () -> this.dataCodec.decode(bytes));
        assertEquals(InvalidReason.BAD_MAGIC, failure.reason());
    }

    /**
     * 单块头的未知算法只在取该类型时报告.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void unknownCompressorIsLocalToBlock() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        var item = entries(bytes).entrySet().iterator().next();
        bytes[base(bytes) + item.getValue().offset()] = 99;
        assertBlockFailure(this.valid(bytes), DataKey.parse(item.getKey()), InvalidReason.UNSUPPORTED_COMPRESSION);
    }

    /**
     * 三种内置压缩器都可以还原单块, 阈值恰好相等时开始压缩.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void blockRoundTripAndThreshold() throws IOException {
        String key = "other:value";
        Tag value = NBT.createString("data".repeat(100));
        CompoundTag tree = NBT.createCompound();
        tree.put(key, value);
        int rawLength = NBT.toBytes(tree, false).length;
        for (CompressorRegistry compressor : CompressorRegistry.values()) {
            byte[] block = BlockCodec.encode(key, value, compressor, rawLength);
            BlockIndex entry = new BlockIndex(0, block.length - 9, rawLength);
            assertEquals(value, BlockCodec.decode(block, 0, key, entry));
            assertEquals(compressor.id(), block[0]);
            assertEquals(CompressorRegistry.NONE.id(), BlockCodec.encode(key, value, compressor, rawLength + 1)[0]);
        }
    }

    /**
     * 长度不一致和整数溢出都应作为指定块损坏报告.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void blockLengthsAndOffsetsAreChecked() throws IOException {
        String key = "other:value";
        byte[] block = BlockCodec.encode(key, NBT.createInt(3), CompressorRegistry.NONE, 256);
        int raw = ByteBuffer.wrap(block).getInt(1);
        assertEquals(InvalidReason.CORRUPTED, assertThrows(FormatException.class, () -> BlockCodec.decode(block, 0, key,
                new BlockIndex(0, block.length - 9, raw + 1))).reason());
        assertEquals(InvalidReason.CORRUPTED, assertThrows(FormatException.class, () -> BlockCodec.decode(block, 14, key,
                new BlockIndex(Integer.MAX_VALUE, Integer.MAX_VALUE, raw))).reason());
    }

    /**
     * CRC 合法也必须校验块的 NBT 形状, 类型名和实际解压长度.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
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
            byte[] block = ByteBuffer.allocate(9 + raw.length).put((byte) 0).putInt(raw.length).putInt((int) crc.getValue()).put(raw).array();
            BlockIndex entry = new BlockIndex(0, raw.length, raw.length);
            assertEquals(InvalidReason.CORRUPTED, assertThrows(FormatException.class, () -> BlockCodec.decode(block, 0, key, entry)).reason());
        }
    }

    /**
     * 索引字段必须具有指定 NBT 类型, 负长度不能进入惰性数据体.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void indexRoundTripAndValidation() throws IOException {
        LinkedHashMap<String, BlockIndex> values = new LinkedHashMap<>();
        values.put("other:z", new BlockIndex(0, 12, 12));
        values.put("other:a", new BlockIndex(21, 15, 30));
        assertEquals(Set.of("o", "l", "n"), BlockIndexCodec.write(values).getCompound("other:z").keySet());
        assertEquals(values, BlockIndexCodec.read(BlockIndexCodec.write(values)));
        assertEquals(new ArrayList<>(values.keySet()), new ArrayList<>(BlockIndexCodec.read(BlockIndexCodec.write(values)).keySet()));
        for (String field : List.of("o", "l", "n")) {
            CompoundTag index = BlockIndexCodec.write(values);
            index.getCompound("other:z").remove(field);
            assertThrows(FormatException.class, () -> BlockIndexCodec.read(index));
        }
        for (String field : List.of("o", "l", "n")) {
            CompoundTag index = BlockIndexCodec.write(values);
            index.getCompound("other:z").putInt(field, -1);
            assertThrows(FormatException.class, () -> BlockIndexCodec.read(index));
        }
    }

    /**
     * u32 段长超出帧容量时应拒绝, 不能按负数或溢出后的短段处理.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void unsignedIndexLengthIsChecked() throws IOException {
        byte[] bytes = this.codec.encode(SnapshotFixtures.snapshot());
        ByteBuffer.wrap(bytes).putInt(SnapshotFixtures.dataOffset(bytes) + 3, -1);
        assertEquals(InvalidReason.CORRUPTED, assertInstanceOf(DecodedSnapshot.Invalid.class, this.codec.decode(bytes)).reason());
    }

    /**
     * 读取已通过容器检查的快照, 方便测试单块失败.
     * @param bytes 待解码帧
     * @return 有效快照
     */
    private Snapshot valid(byte[] bytes) {
        return assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(bytes)).snapshot();
    }

    /**
     * 从固定头计算块区起点, 测试不依赖生产代码的私有帧解析器.
     * @param bytes 帧字节
     * @return 块区绝对偏移
     */
    private static int base(byte[] bytes) {
        return SnapshotFixtures.blockBase(bytes);
    }

    /**
     * 读取帧内索引以定位待损坏的块.
     * @param bytes 有效帧
     * @return 类型与索引条目
     * @throws IOException 当测试输入无法解析时
     */
    private static LinkedHashMap<String, BlockIndex> entries(byte[] bytes) throws IOException {
        int dataOffset = SnapshotFixtures.dataOffset(bytes);
        int indexLength = ByteBuffer.wrap(bytes).getInt(dataOffset + 3);
        CompoundTag tree = (CompoundTag) NBT.readUnnamedTag(new DataInputStream(new ByteArrayInputStream(bytes, dataOffset + 11, indexLength)), false);
        return BlockIndexCodec.read(tree);
    }

    /**
     * 核对块异常的包装, 分类及具体类型名.
     * @param snapshot 惰性快照
     * @param key 损坏类型
     * @param reason 预期分类
     */
    private static void assertBlockFailure(Snapshot snapshot, DataKey key, InvalidReason reason) {
        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> snapshot.data(key));
        FormatException cause = assertInstanceOf(FormatException.class, failure.getCause());
        assertEquals(reason, cause.reason());
        assertTrue(cause.getMessage().contains(key.asString()));
    }
}
