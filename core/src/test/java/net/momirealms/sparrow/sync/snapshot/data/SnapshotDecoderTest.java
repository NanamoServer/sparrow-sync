package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证正式加载与预览共用转换实现时的失败规则和请求所有权. */
@ExtendWith(PluginConfigExtension.class)
class SnapshotDecoderTest {
    private static final DataKey FIRST = DataKey.of("test", "first"); // 固定排序中较早的类型
    private static final DataKey LAST = DataKey.of("test", "last"); // 用于观察是否执行后续解码
    private static final DataKey UNKNOWN = DataKey.of("external", "unknown"); // 本服未注册类型

    /** 正式加载遇到关键类型损坏立即停止, 同一正文的预览仍能读取其他选择项. */
    @Test
    void criticalFailureStopsApplyButSelectedPreviewContinues() {
        List<DataKey> calls = new ArrayList<>();
        DataRegistry registry = registry(new TestType(FIRST, true, true, calls), new TestType(LAST, false, false, calls));
        SnapshotDecoder decoder = new SnapshotDecoder(registry);
        Snapshot snapshot = snapshot();

        DecodedSnapshotData applying = decoder.decodeForApply(snapshot);
        assertEquals(FIRST, applying.criticalFailure());
        assertEquals(List.of(FIRST), calls);
        assertNull(applying.value(LAST));

        calls.clear();
        DecodedSnapshotData preview = decoder.decodeSelected(snapshot, type -> true);
        assertNull(preview.criticalFailure());
        assertInstanceOf(IOException.class, preview.failure(FIRST));
        assertEquals(List.of(FIRST, LAST), calls);
        assertNotNull(preview.value(LAST));
    }

    /** 未选择的已注册类型和未知类型都不调用解码, 原 Snapshot 的 Tag 保持原样. */
    @Test
    void selectionLeavesOtherTypesAndOriginalTagsUntouched() {
        List<DataKey> calls = new ArrayList<>();
        DataRegistry registry = registry(new TestType(FIRST, true, true, calls), new TestType(LAST, false, false, calls));
        Snapshot original = snapshot();
        Map<DataKey, Tag> raw = original.allData();

        DecodedSnapshotData preview = new SnapshotDecoder(registry).decodeSelected(original, type -> type.key().equals(LAST));

        assertEquals(List.of(LAST), calls);
        assertNull(preview.value(FIRST));
        assertNull(preview.failure(FIRST));
        assertNull(preview.value(UNKNOWN));
        assertSame(raw, original.allData());
        assertEquals("external", original.data(UNKNOWN).getAsString());
    }

    /** 应用消费一份请求的值缓冲时, 独立预览的值和未知原数据继续有效. */
    @Test
    @SuppressWarnings("unchecked")
    void applicationOwnsItsValuesWithoutConsumingAnotherPreview() {
        DataRegistry registry = registry(new TestType(FIRST, false, false, new ArrayList<>()), new TestType(LAST, false, false, new ArrayList<>()));
        SnapshotDecoder decoder = new SnapshotDecoder(registry);
        Snapshot original = snapshot();
        DecodedSnapshotData applying = decoder.decodeForApply(original);
        DecodedSnapshotData preview = decoder.decodeSelected(original, type -> true);
        List<String> applyValue = (List<String>) applying.value(FIRST);
        List<String> previewValue = (List<String>) preview.value(FIRST);
        assertNotSame(applyValue, previewValue);

        SnapshotApplyContext context = applying.intoApplyContext(applying.passthrough());
        assertSame(applyValue, context.takePending(FIRST));
        applyValue.add("changed by apply");

        assertEquals(List.of("decoded"), previewValue);
        assertSame(original.data(UNKNOWN), context.passthrough().get(UNKNOWN));
        assertEquals("first", original.data(FIRST).getAsString());
    }

    /** 非关键解码失败进入可由事件补回的 Context 状态. */
    @Test
    void nonCriticalFailureCanBeRecoveredAfterContextCreation() {
        DataRegistry registry = registry(new TestType(FIRST, false, true, new ArrayList<>()), new TestType(LAST, false, false, new ArrayList<>()));
        DecodedSnapshotData decoded = new SnapshotDecoder(registry).decodeForApply(snapshot());
        SnapshotApplyContext context = decoded.intoApplyContext(decoded.passthrough());
        assertEquals(List.of(FIRST), context.skipped());

        context.acceptEventValues(Map.of(FIRST, List.of("recovered"), LAST, context.pendingValues().get(LAST)));

        assertEquals(List.of("recovered"), context.pendingValues().get(FIRST));
        assertTrue(context.skipped().isEmpty());
        assertEquals(SnapshotApplyContext.FailureStage.DECODE, context.failures().getFirst().stage());
    }

    /**
     * 破坏关键类型的数据块, 验证读取失败会记录在该类型的结果中.
     * 用于应用快照时应停止读取后续类型; 用于预览时应继续读取其他选中的类型.
     *
     * @throws Exception 当测试快照编解码或读取已解析块数失败时
     */
    @Test
    void lazyBlockFailureUsesTheSameCriticalAndPreviewBoundaries() throws Exception {
        List<DataKey> calls = new ArrayList<>();
        DataRegistry registry = registry(new TestType(FIRST, true, false, calls), new TestType(LAST, false, false, calls));
        BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.NONE);
        byte[] bytes = codec.encode(snapshot());
        Snapshot located = assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(bytes)).snapshot();
        bytes[(int) located.content().raw(FIRST).offset() + 13] ^= 1;
        Snapshot applying = assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(bytes)).snapshot();
        SnapshotDecoder decoder = new SnapshotDecoder(registry);
        DecodedSnapshotData failed = decoder.decodeForApply(applying);
        assertEquals(FIRST, failed.criticalFailure());
        assertInstanceOf(UncheckedIOException.class, failed.failure(FIRST));
        assertTrue(calls.isEmpty());
        Snapshot previewing = assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(bytes)).snapshot();
        DecodedSnapshotData preview = decoder.decodeSelected(previewing, type -> true);
        assertInstanceOf(UncheckedIOException.class, preview.failure(FIRST));
        assertNotNull(preview.value(LAST));
        assertEquals(List.of(LAST), calls);
        assertEquals(1, SnapshotFixtures.decodedBlockCount(previewing));
    }
    /**
     * 注册本次测试需要的数据类型, 冻结注册表以确定后续的解码顺序.
     *
     * @param types 本次测试使用的数据类型
     * @return 完成注册并已冻结的注册表
     */
    @NotNull
    private static DataRegistry registry(TestType @NotNull ... types) {
        DataRegistry registry = new DataRegistry();
        for (int i = 0; i < types.length; i++) {
            registry.register(types[i]);
        }
        registry.freeze();
        return registry;
    }

    /**
     * 创建同时包含已注册与未知类型的正文.
     *
     * @return 每次测试独立的快照
     */
    @NotNull
    private static Snapshot snapshot() {
        return new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1, SaveCause.COMMAND, false, "test", 0),
                Map.of(FIRST, NBT.createString("first"), LAST, NBT.createString("last"), UNKNOWN, NBT.createString("external")));
    }

    /**
     * 记录真实解码调用并返回独立可变值, 其他玩家操作禁止在这些测试中执行.
     *
     * @param key 类型标识
     * @param critical 正式加载是否视为关键类型
     * @param broken 是否模拟正文类型损坏
     * @param calls 本次测试的调用顺序
     */
    private record TestType(@NotNull DataKey key, boolean critical, boolean broken, @NotNull List<DataKey> calls) implements PlayerDataType<List<String>> {
        /** {@inheritDoc} */
        @Override
        @NotNull
        public List<String> capture(@NotNull Player player, @NotNull CaptureMode mode) {
            throw new AssertionError("decode must not capture");
        }

        /** {@inheritDoc} */
        @Override
        @NotNull
        public Tag encode(@NotNull List<String> value) {
            throw new AssertionError("decode must not encode");
        }

        /** {@inheritDoc} */
        @Override
        @NotNull
        public List<String> decode(@NotNull Tag data, int version) throws IOException {
            this.calls.add(this.key);
            if (this.broken) {
                throw new IOException("invalid type content");
            }
            return new ArrayList<>(List.of("decoded"));
        }

        /** {@inheritDoc} */
        @Override
        public void apply(@NotNull Player player, @NotNull List<String> value) {
            throw new AssertionError("decode must not apply");
        }
    }
}
