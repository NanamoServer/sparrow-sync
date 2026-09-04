package net.momirealms.sparrow.sync.snapshot.data.type;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.PlayerAdvancementsProxy;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.AdvancementValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.Advancements;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementsDataTypeTest {

    /** 初始化 1.21.8 代理描述与测试所需的原版注册表. */
    @BeforeAll
    static void bootstrapRegistries() {
        // final setter 和进度字段访问必须先绑定到本测试使用的 NMS 版本
        BukkitProxy.init("1.21.8", List.of("paper"));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void parallelArrayFormatRoundTripsDetachedCriterionCompletionTimes() throws IOException {
        Instant first = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        Instant second = first.plusMillis(125);
        Object firstId = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        Object secondId = IdentifierProxy.INSTANCE.tryParse("example:custom");
        Advancements expected = new Advancements(new AdvancementValue[]{
                new AdvancementValue(firstId, new String[]{"tick", "second"}, new Instant[]{first, second}, false),
                new AdvancementValue(secondId, new String[]{"complete"}, new Instant[]{second}, true)
        });
        AdvancementsDataType type = new AdvancementsDataType();

        Tag encoded = type.encode(expected);
        Advancements decoded = type.decode(encoded, 0);

        assertEquals(firstId, decoded.values()[0].id());
        assertArrayEquals(new String[]{"tick", "second"}, decoded.values()[0].criteria());
        assertArrayEquals(new Instant[]{first, second}, decoded.values()[0].obtained());
        assertFalse(decoded.values()[0].done());
        assertEquals(secondId, decoded.values()[1].id());
        assertTrue(decoded.values()[1].done());

        CompoundTag root = assertInstanceOf(CompoundTag.class, encoded);
        ListTag ids = assertInstanceOf(ListTag.class, root.get("ids"));
        ListTag criteria = assertInstanceOf(ListTag.class, root.get("criteria"));
        assertEquals("minecraft:adventure/root", ids.getString(0));
        assertEquals("example:custom", ids.getString(1));
        assertEquals("tick", criteria.getString(0));
        assertEquals("complete", criteria.getString(2));
        assertArrayEquals(new int[]{2, 1}, root.getIntArray("counts"));
        assertArrayEquals(new long[]{first.toEpochMilli(), second.toEpochMilli(), second.toEpochMilli()}, root.getLongArray("obtained"));
        assertArrayEquals(new byte[]{0, 1}, root.getByteArray("done"));
    }

    @Test
    void advancementProxiesBindRequiredMembers() {
        assertNotNull(AdvancementHolderProxy.INSTANCE);
        assertNotNull(AdvancementProgressProxy.INSTANCE);
        assertTrue(AdvancementProgressProxy.INSTANCE.getCriteria(new AdvancementProgress()).isEmpty());
    }

    /** 验证生成的 final setter 能替换真实 PlayerAdvancements 字段, 且 getter 看到同一对象身份. */
    @Test
    void progressChangedFinalFieldCanBeReplaced() throws Exception {
        // 跳过构造器可隔离服务器, 存档和 advancement manager 初始化
        PlayerAdvancements advancements = allocateWithoutConstructor(PlayerAdvancements.class);
        Field field = PlayerAdvancements.class.getDeclaredField("progressChanged");
        field.setAccessible(true);
        field.set(advancements, new HashSet<>());
        Set<Object> replacement = new HashSet<>();

        // 通过生成代理执行与生产路径相同的 final 字段写入
        PlayerAdvancementsProxy.INSTANCE.setProgressChanged(advancements, replacement);

        // 身份相等是生产代码启用跟踪 wrapper 的硬闸门
        assertSame(replacement, PlayerAdvancementsProxy.INSTANCE.getProgressChanged(advancements));
    }

    /** 验证 Map 换代后旧 ID 槽位保持稳定, holder 替换生效, 删除项留下空槽. */
    @Test
    void advancementSlotsKeepIdsStableAcrossReloads() throws Exception {
        // 编译只有 firstId 的首个布局
        Object firstId = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        Object secondId = IdentifierProxy.INSTANCE.tryParse("example:second");
        AdvancementHolder first = holder(firstId);
        AdvancementHolder replacement = holder(firstId);
        AdvancementHolder second = holder(secondId);
        AtomicReference<Map<?, ?>> source = new AtomicReference<>(Map.of(firstId, first));
        AdvancementSlots slots = new AdvancementSlots(source::get);

        AdvancementSlots.Layout initial = slots.current();
        assertNotNull(initial);
        int firstSlot = initial.slot(firstId);

        // reload 用新 holder 替换旧 ID, 并在末尾追加一个新 ID
        source.set(Map.of(firstId, replacement, secondId, second));
        AdvancementSlots.Layout reloaded = slots.current();

        assertNotNull(reloaded);
        assertEquals(firstSlot, reloaded.slot(firstId));
        assertSame(replacement, reloaded.holder(firstSlot));
        assertNotEquals(firstSlot, reloaded.slot(secondId));

        // 删除 firstId 后保留其历史槽位, 在线玩家的旧候选位不会指向其他 ID
        source.set(Map.of(secondId, second));
        AdvancementSlots.Layout removed = slots.current();

        assertNotNull(removed);
        assertNull(removed.holder(firstSlot));
        assertEquals(reloaded.slot(secondId), removed.slot(secondId));
    }

    /** 验证 wrapper 保持原 dirty Set 行为, 并让真实进度候选跨 clear 和 revoke 单调保留. */
    @Test
    void trackingSetKeepsDirtyDelegateAndRetainsProgressCandidates() throws Exception {
        // progressed 有真实进度, pending 只有空的 AdvancementProgress
        Object progressedId = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        Object pendingId = IdentifierProxy.INSTANCE.tryParse("example:pending");
        AdvancementHolder progressedHolder = holder(progressedId);
        AdvancementHolder pendingHolder = holder(pendingId);
        AdvancementProgress progressed = progress("complete", Instant.now());
        AdvancementProgress pending = progress("pending", null);
        Map<Object, Object> progress = new LinkedHashMap<>();
        progress.put(progressedHolder, progressed);
        progress.put(pendingHolder, pending);
        Set<Object> dirty = new HashSet<>();
        dirty.add(progressedHolder);
        Map<Object, Object> advancements = Map.of(progressedId, progressedHolder, pendingId, pendingHolder);
        AdvancementSlots slots = new AdvancementSlots(() -> advancements);
        AdvancementProgressChangedWrapperSet tracking = new AdvancementProgressChangedWrapperSet(dirty, progress, slots);

        // 构造时从原 dirty Set 播种 progressed, visibility-only 的 pending 不进入候选
        assertEquals(1, BitSet.valueOf(tracking.candidates()).cardinality());
        tracking.add(pendingHolder);
        assertEquals(1, BitSet.valueOf(tracking.candidates()).cardinality());
        assertTrue(dirty.contains(pendingHolder));

        // NMS flush 清空共享 dirty Set, 历史候选仍然存在
        tracking.clear();
        assertTrue(dirty.isEmpty());
        assertTrue(tracking.complete());
        assertEquals(1, BitSet.valueOf(tracking.candidates()).cardinality());

        // 最后一个 criterion 被撤销后仍保留候选, 后续 apply 才能识别远端缺失并维持撤销
        CriterionProgress criterion = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(progressed).get("complete");
        CriterionProgressProxy.INSTANCE.setObtained(criterion, null);
        tracking.add(progressedHolder);
        assertEquals(1, BitSet.valueOf(tracking.candidates()).cardinality());
    }

    /** 验证 sparse capture 与完整 Map 扫描产出相同的 ID, criterion 和完成时间. */
    @Test
    void sparseCaptureMatchesDenseProgressScan() throws Exception {
        // 同时准备一个有进度项目和一个无进度项目, 候选中只应出现前者
        Object progressedId = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        Object pendingId = IdentifierProxy.INSTANCE.tryParse("example:pending");
        AdvancementHolder progressedHolder = holder(progressedId);
        AdvancementHolder pendingHolder = holder(pendingId);
        AdvancementProgress progressed = progress("complete", Instant.now().truncatedTo(ChronoUnit.MILLIS));
        Map<Object, Object> progress = new LinkedHashMap<>();
        progress.put(progressedHolder, progressed);
        progress.put(pendingHolder, progress("pending", null));
        Set<Object> dirty = new HashSet<>();
        dirty.add(progressedHolder);
        Map<Object, Object> advancements = Map.of(progressedId, progressedHolder, pendingId, pendingHolder);
        AdvancementSlots slots = new AdvancementSlots(() -> advancements);
        AdvancementProgressChangedWrapperSet tracking = new AdvancementProgressChangedWrapperSet(dirty, progress, slots);

        // 对同一份 NMS 状态分别执行参考算法和稀疏算法
        Advancements dense = AdvancementsDataType.captureDense(progress);
        Advancements sparse = AdvancementsDataType.captureSparse(progress, tracking, slots.current());

        // 比较持久化可见字段, 排除仅靠数量相等掩盖内容错误
        assertEquals(1, dense.values().length);
        assertEquals(dense.values()[0].id(), sparse.values()[0].id());
        assertArrayEquals(dense.values()[0].criteria(), sparse.values()[0].criteria());
        assertArrayEquals(dense.values()[0].obtained(), sparse.values()[0].obtained());
    }

    /** 验证本服无法识别的远端 advancement 会随下一份快照继续传递. */
    @Test
    void unknownAdvancementSurvivesApplyAndNextServerCapture() throws Exception {
        Object localId = IdentifierProxy.INSTANCE.tryParse("ce:b");
        Object unknownId = IdentifierProxy.INSTANCE.tryParse("ce:a");
        AdvancementHolder localHolder = holder(localId);
        Instant obtained = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AdvancementProgress localProgress = progress("done", null);
        Map<Object, Object> progress = new LinkedHashMap<>();
        progress.put(localHolder, localProgress);
        Map<Object, Object> advancements = Map.of(localId, localHolder);
        AdvancementSlots slots = new AdvancementSlots(() -> advancements);
        AdvancementProgressChangedWrapperSet tracking = new AdvancementProgressChangedWrapperSet(new HashSet<>(), progress, slots);
        PlayerAdvancements playerAdvancements = allocateWithoutConstructor(PlayerAdvancements.class);
        setField(PlayerAdvancements.class, playerAdvancements, "progress", progress);
        setField(PlayerAdvancements.class, playerAdvancements, "progressChanged", tracking);
        ServerPlayer handle = allocateWithoutConstructor(ServerPlayer.class);
        setField(ServerPlayer.class, handle, "advancements", playerAdvancements);
        CraftPlayer player = allocateWithoutConstructor(CraftPlayer.class);
        setField(CraftEntity.class, player, "entity", handle);
        AdvancementsDataType type = new AdvancementsDataType();
        setField(AdvancementsDataType.class, type, "advancementSlots", slots);
        AdvancementValue unknown = new AdvancementValue(unknownId, new String[]{"done"}, new Instant[]{obtained}, true);

        type.apply(player, new Advancements(new AdvancementValue[]{unknown}));
        CriterionProgress localCriterion = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(localProgress).get("done");
        CriterionProgressProxy.INSTANCE.setObtained(localCriterion, obtained);
        tracking.add(localHolder);
        Advancements captured = type.capture(player);
        Advancements forwarded = type.decode(type.encode(captured), 0);

        Set<Object> ids = new HashSet<>();
        for (AdvancementValue value : forwarded.values()) ids.add(value.id());
        assertEquals(Set.of(localId, unknownId), ids);
    }

    /** 验证 Native JSON 只接收当前服务器能够加载的 advancement ID. */
    @Test
    void nativeJsonEligibilityRejectsUnknownAdvancementIds() throws Exception {
        Object localId = IdentifierProxy.INSTANCE.tryParse("ce:b");
        Object unknownId = IdentifierProxy.INSTANCE.tryParse("ce:a");
        AdvancementHolder localHolder = holder(localId);
        Map<Object, Object> advancements = Map.of(localId, localHolder);
        AdvancementSlots.Layout layout = new AdvancementSlots(() -> advancements).current();
        Method containsUnknown = AdvancementsDataType.class.getDeclaredMethod("containsUnknown", AdvancementValue[].class, AdvancementSlots.Layout.class);
        containsUnknown.setAccessible(true);

        assertFalse((boolean) containsUnknown.invoke(null, new AdvancementValue[]{new AdvancementValue(localId, new String[0], new Instant[0], false)}, layout));
        assertTrue((boolean) containsUnknown.invoke(null, new AdvancementValue[]{new AdvancementValue(unknownId, new String[0], new Instant[0], false)}, layout));
    }

    /** 验证玩家线程扩展候选位图时, 并发 capture 副本不会破坏或永久丢失已写入槽位. */
    @Test
    void candidateSnapshotsDoNotLoseBitsWhileTheWriterGrowsTheArray() throws Exception {
        // 130 个槽位会跨越三个 long word, 覆盖 BitSet 扩容边界
        int count = 130;
        Map<Object, Object> progress = new LinkedHashMap<>();
        Map<Object, Object> advancements = new LinkedHashMap<>();
        AdvancementHolder[] holders = new AdvancementHolder[count];
        for (int i = 0; i < count; i++) {
            Object id = IdentifierProxy.INSTANCE.tryParse("example:test_" + i);
            AdvancementHolder holder = holder(id);
            holders[i] = holder;
            progress.put(holder, progress("complete", Instant.now()));
            advancements.put(id, holder);
        }
        AdvancementSlots slots = new AdvancementSlots(() -> advancements);
        AdvancementProgressChangedWrapperSet tracking = new AdvancementProgressChangedWrapperSet(new HashSet<>(), progress, slots);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // writer 模拟玩家线程 grant, 当前线程持续模拟异步 capture 复制
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < holders.length; i++) tracking.add(holders[i]);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        while (writer.isAlive()) tracking.candidates();
        writer.join();

        // writer 正常结束后所有永久候选都必须可见
        assertNull(failure.get());
        assertEquals(count, BitSet.valueOf(tracking.candidates()).cardinality());
    }

    /** 验证布局来源在编译期间持续换代时, tracker 会标记候选不完整. */
    @Test
    void unstableLayoutMarksTrackingCandidatesIncomplete() throws Exception {
        // Supplier 每次返回新 Map 身份, 两次布局编译都无法取得稳定 generation
        Object id = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        AdvancementHolder holder = holder(id);
        Map<Object, Object> progress = Map.of(holder, progress("complete", Instant.now()));
        Set<Object> dirty = new HashSet<>();
        dirty.add(holder);
        AdvancementSlots slots = new AdvancementSlots(() -> Map.of(id, holder));

        AdvancementProgressChangedWrapperSet tracking = new AdvancementProgressChangedWrapperSet(dirty, progress, slots);

        assertFalse(tracking.complete());
    }

    @Test
    void captureProgressKeepsOnlyObtainedCriteriaAndDefersIdLookup() throws Exception {
        assertNull(captureProgress(new Object(), new AdvancementProgress()));

        Object id = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        AdvancementHolder holder = (AdvancementHolder) AdvancementHolder.class.getDeclaredConstructors()[0].newInstance(id, null);
        AdvancementProgress progress = new AdvancementProgress();
        progress.update(AdvancementRequirements.allOf(List.of("complete", "pending")));
        Instant obtained = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CriterionProgress complete = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(progress).get("complete");
        CriterionProgressProxy.INSTANCE.setObtained(complete, obtained);

        AdvancementValue captured = captureProgress(holder, progress);

        assertNotNull(captured);
        assertEquals(id, captured.id());
        assertArrayEquals(new String[]{"complete"}, captured.criteria());
        assertArrayEquals(new Instant[]{obtained}, captured.obtained());
        assertFalse(captured.done());
    }

    @Test
    void decodeRejectsMisalignedParallelArrays() {
        CompoundTag root = NBT.createCompound();
        ListTag ids = NBT.createList();
        ids.add(NBT.createString("minecraft:adventure/root"));
        ListTag criteria = NBT.createList();
        criteria.add(NBT.createString("tick"));
        root.put("ids", ids);
        root.put("criteria", criteria);
        root.putIntArray("counts", new int[]{2});
        root.putLongArray("obtained", new long[]{Instant.now().toEpochMilli()});
        root.putByteArray("done", new byte[]{0});

        assertThrows(IOException.class, () -> new AdvancementsDataType().decode(root, 0));
    }

    @Test
    void criterionObtainedCanBePatchedDirectly() {
        Instant original = Instant.now().minusSeconds(60);
        Instant replacement = Instant.now();
        CriterionProgress progress = new CriterionProgress(original);

        CriterionProgressProxy.INSTANCE.setObtained(progress, replacement);
        assertEquals(replacement, progress.getObtained());
        CriterionProgressProxy.INSTANCE.setObtained(progress, null);
        assertNull(progress.getObtained());
        assertFalse(progress.isDone());
    }

    @Test
    void nativeJsonUsesVanillaProgressShape() throws Exception {
        Instant obtained = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        Object id = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        Advancements value = new Advancements(new AdvancementValue[]{
                new AdvancementValue(id, new String[]{"tick"}, new Instant[]{obtained}, false)
        });

        JsonObject root = JsonParser.parseString(new String(encodeNativeJson(value), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<Object, AdvancementProgress> decoded = vanillaCodec().parse(JsonOps.INSTANCE, root).getOrThrow();
        AdvancementProgress progress = decoded.get(id);
        CriterionProgress criterion = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(progress).get("tick");

        assertTrue(root.has("DataVersion"));
        assertTrue(root.getAsJsonObject("minecraft:adventure/root").getAsJsonObject("criteria").has("tick"));
        assertFalse(root.getAsJsonObject("minecraft:adventure/root").get("done").getAsBoolean());
        assertEquals(obtained, criterion.getObtained());
    }

    @Test
    void emptyNativeJsonIsAcceptedByVanillaDataFixWrapper() throws Exception {
        JsonObject root = JsonParser.parseString(new String(encodeNativeJson(new Advancements(new AdvancementValue[0])), StandardCharsets.UTF_8)).getAsJsonObject();

        assertTrue(vanillaCodec().parse(JsonOps.INSTANCE, root).getOrThrow().isEmpty());
        assertTrue(root.has("DataVersion"));
    }

    private static AdvancementValue captureProgress(Object advancement, AdvancementProgress progress) throws Exception {
        Method method = AdvancementsDataType.class.getDeclaredMethod("captureProgress", Object.class, AdvancementProgress.class);
        method.setAccessible(true);
        return (AdvancementValue) method.invoke(null, advancement, progress);
    }

    /**
     * 创建只用于 ID 与对象身份测试的 AdvancementHolder.
     *
     * @param id holder 的资源标识
     * @return 不含 Advancement value 的测试 holder
     * @throws Exception 当当前 NMS 构造器描述与测试假设不一致时
     */
    private static AdvancementHolder holder(Object id) throws Exception {
        return (AdvancementHolder) AdvancementHolder.class.getDeclaredConstructors()[0].newInstance(id, null);
    }

    /**
     * 分配不执行构造器的 NMS 实例, 供 final 字段代理测试隔离服务器依赖.
     *
     * @param type 要分配的 NMS 类型
     * @param <T> 实例类型
     * @return 未执行构造器的实例
     * @throws Exception 当 Unsafe 或目标类型不可访问时
     */
    private static <T> T allocateWithoutConstructor(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    /** 把测试状态装入跳过构造器创建的 NMS 或 CraftBukkit 对象. */
    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 创建只含一个 criterion 的 AdvancementProgress.
     *
     * @param criterionName criterion 名称
     * @param obtained 完成时间, null 表示尚未取得
     * @return 与指定完成状态一致的进度对象
     */
    private static AdvancementProgress progress(String criterionName, Instant obtained) {
        AdvancementProgress progress = new AdvancementProgress();
        progress.update(AdvancementRequirements.allOf(List.of(criterionName)));
        if (obtained != null) {
            CriterionProgress criterion = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(progress).get(criterionName);
            CriterionProgressProxy.INSTANCE.setObtained(criterion, obtained);
        }
        return progress;
    }

    private static byte[] encodeNativeJson(Advancements value) throws Exception {
        Method method = AdvancementsDataType.class.getDeclaredMethod("encodeNativeJson", Advancements.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, value);
    }

    private static Codec<Map<Object, AdvancementProgress>> vanillaCodec() {
        Codec<Map<Object, AdvancementProgress>> codec = Codec.unboundedMap(IdentifierProxy.INSTANCE.getCodec(), AdvancementProgress.CODEC);
        return DataFixTypes.ADVANCEMENTS.wrapCodec(codec, DataFixers.getDataFixer(), 1343);
    }
}
