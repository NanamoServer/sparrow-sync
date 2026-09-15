package net.momirealms.sparrow.sync.snapshot.data.type;

import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotDecoder;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.AdvancementValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.Advancements;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementsDataTypeTest {
    private static Field pluginConfigField;
    private static Object previousPluginConfig;

    @BeforeAll
    static void bootstrapRegistries() throws ReflectiveOperationException {
        BukkitProxy.init("1.21.8", List.of("paper"));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        pluginConfigField = PluginConfig.class.getDeclaredField("config");
        pluginConfigField.setAccessible(true);
        previousPluginConfig = pluginConfigField.get(null);
        pluginConfigField.set(null, new PluginConfig.ConfigDefinition());
    }

    @AfterAll
    static void restorePluginConfig() throws IllegalAccessException {
        pluginConfigField.set(null, previousPluginConfig);
    }

    @Test
    void parallelArrayFormatRoundTripsDetachedCriterionCompletionTimes() throws IOException {
        Instant first = Instant.parse("2026-09-05T00:00:00.987654321Z");
        Instant second = first.plusMillis(125);
        Object firstId = IdentifierProxy.INSTANCE.tryParse("minecraft:adventure/root");
        Object secondId = IdentifierProxy.INSTANCE.tryParse("example:custom");
        Advancements expected = new Advancements(new AdvancementValue[]{
                new AdvancementValue(firstId, new String[]{"tick", "second"}, new Instant[]{first, second}, false),
                new AdvancementValue(secondId, new String[]{"complete"}, new Instant[]{second}, true)
        });
        AdvancementsDataType type = new AdvancementsDataType();

        Tag encoded = type.encode(expected);
        Advancements decoded = type.decode(encoded);

        assertEquals(firstId, decoded.values()[0].id());
        assertArrayEquals(new String[]{"tick", "second"}, decoded.values()[0].criteria());
        assertArrayEquals(new Instant[]{first.truncatedTo(ChronoUnit.SECONDS), second.truncatedTo(ChronoUnit.SECONDS)}, decoded.values()[0].obtained());
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
        assertArrayEquals(new long[]{first.getEpochSecond() * 1000, second.getEpochSecond() * 1000, second.getEpochSecond() * 1000}, root.getLongArray("obtained"));
        assertArrayEquals(new byte[]{0, 1}, root.getByteArray("done"));
    }

    @Test
    void decodeNormalizesLegacyMillisecondsWithoutChangingTheStoredUnit() throws IOException {
        Object id = IdentifierProxy.INSTANCE.tryParse("example:legacy");
        AdvancementsDataType type = new AdvancementsDataType();
        Advancements value = new Advancements(new AdvancementValue[]{
                new AdvancementValue(id, new String[]{"first", "second"}, new Instant[]{Instant.EPOCH, Instant.EPOCH}, true)
        });
        CompoundTag encoded = (CompoundTag) type.encode(value);
        encoded.putLongArray("obtained", new long[]{1999, -1});

        Advancements decoded = type.decode(encoded);

        assertArrayEquals(new Instant[]{Instant.ofEpochSecond(1), Instant.ofEpochSecond(-1)}, decoded.values()[0].obtained());
    }

    @Test
    void playerApplyIgnoresSubsecondDifferencesWithoutDirtyingOrFlushing() throws Exception {
        Object id = IdentifierProxy.INSTANCE.tryParse("example:precision");
        AdvancementHolder holder = applicableHolder(id);
        Instant original = Instant.parse("2026-09-05T00:00:00.123456789Z");
        AdvancementProgress progress = progress("done", original);
        Map<Object, Object> values = new LinkedHashMap<>(Map.of(holder, progress));
        Map<Object, Object> registry = Map.of(id, holder);
        PlayerFixture fixture = playerFixture(values, new AdvancementSlots(() -> registry));
        Advancements before = fixture.type.capture(fixture.player, CaptureMode.SYNC);
        fixture.tracking.clear();
        AdvancementValue target = new AdvancementValue(id, new String[]{"done"}, new Instant[]{original.plusMillis(500)}, true);

        fixture.type.apply(fixture.player, new Advancements(new AdvancementValue[]{target}));

        assertSame(original, progress.getCriterion("done").getObtained());
        assertTrue(fixture.tracking.isEmpty());
        assertSame(before, fixture.type.capture(fixture.player, CaptureMode.SYNC));
    }

    @Test
    void playerApplyWithDifferentSecondsUpdatesTheCaptureCache() throws Exception {
        Object id = IdentifierProxy.INSTANCE.tryParse("example:precision");
        AdvancementHolder holder = applicableHolder(id);
        Instant original = Instant.parse("2026-09-05T00:00:00Z");
        AdvancementProgress progress = progress("done", original);
        Map<Object, Object> values = new LinkedHashMap<>(Map.of(holder, progress));
        Map<Object, Object> registry = Map.of(id, holder);
        PlayerFixture fixture = playerFixture(values, new AdvancementSlots(() -> registry));
        Advancements before = fixture.type.capture(fixture.player, CaptureMode.SYNC);
        Instant expected = original.plusSeconds(5).plusNanos(123456789);
        AdvancementValue target = new AdvancementValue(id, new String[]{"done"}, new Instant[]{expected}, true);

        fixture.type.apply(fixture.player, new Advancements(new AdvancementValue[]{target}));
        Advancements after = fixture.type.capture(fixture.player, CaptureMode.SYNC);

        assertEquals(expected.truncatedTo(ChronoUnit.SECONDS), progress.getCriterion("done").getObtained());
        assertArrayEquals(new Instant[]{expected.truncatedTo(ChronoUnit.SECONDS)}, after.values()[0].obtained());
        assertArrayEquals(new Instant[]{original}, before.values()[0].obtained());
    }

    @Test
    void advancementSlotsKeepIdsStableAcrossReloads() throws Exception {
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

        source.set(Map.of(firstId, replacement, secondId, second));
        AdvancementSlots.Layout reloaded = slots.current();

        assertNotNull(reloaded);
        assertEquals(firstSlot, reloaded.slot(firstId));
        assertSame(replacement, reloaded.holder(firstSlot));
        assertNotEquals(firstSlot, reloaded.slot(secondId));

        source.set(Map.of(secondId, second));
        AdvancementSlots.Layout removed = slots.current();

        assertNotNull(removed);
        assertNull(removed.holder(firstSlot));
        assertEquals(reloaded.slot(secondId), removed.slot(secondId));
    }

    @Test
    void trackingSetKeepsDirtyDelegateAndRetainsProgressCandidates() throws Exception {
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

        assertEquals(1, BitSet.valueOf(tracking.candidates()).cardinality());
        tracking.add(pendingHolder);
        assertEquals(1, BitSet.valueOf(tracking.candidates()).cardinality());
        assertTrue(dirty.contains(pendingHolder));

        tracking.clear();
        assertTrue(dirty.isEmpty());
        assertTrue(tracking.complete());
        assertEquals(1, BitSet.valueOf(tracking.candidates()).cardinality());

        CriterionProgress criterion = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(progressed).get("complete");
        CriterionProgressProxy.INSTANCE.setObtained(criterion, null);
        tracking.add(progressedHolder);
        assertEquals(1, BitSet.valueOf(tracking.candidates()).cardinality());
    }

    @Test
    void sparseCaptureMatchesDenseProgressScan() throws Exception {
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

        Advancements dense = AdvancementsDataType.captureDense(progress);
        Advancements sparse = AdvancementsDataType.captureSparse(progress, tracking.candidates(), slots.current());

        assertEquals(1, dense.values().length);
        assertEquals(dense.values()[0].id(), sparse.values()[0].id());
        assertArrayEquals(dense.values()[0].criteria(), sparse.values()[0].criteria());
        assertArrayEquals(dense.values()[0].obtained(), sparse.values()[0].obtained());
    }

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
        Advancements captured = type.capture(player, CaptureMode.SYNC);
        Advancements forwarded = type.decode(type.encode(captured));

        Set<Object> ids = new HashSet<>();
        for (AdvancementValue value : forwarded.values()) ids.add(value.id());
        assertEquals(Set.of(localId, unknownId), ids);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialRetentionCoexistsWithUnknownRawBlocks(boolean nativeHandoff) throws Exception {
        Object localId = IdentifierProxy.INSTANCE.tryParse("ce:local");
        Object unknownId = IdentifierProxy.INSTANCE.tryParse("ce:remote");
        AdvancementHolder localHolder = holder(localId);
        AdvancementProgress localProgress = progress("done", null);
        Map<Object, Object> progress = new LinkedHashMap<>();
        Map<Object, Object> advancements = Map.of(localId, localHolder);
        AdvancementSlots slots = new AdvancementSlots(() -> advancements);
        PlayerFixture fixture = playerFixture(progress, slots);
        Instant obtained = Instant.parse("2026-09-12T00:00:00Z");
        AdvancementValue unknown = new AdvancementValue(unknownId, new String[]{"done"}, new Instant[]{obtained}, true);
        DataKey external = DataKey.of("external", "book");
        BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0);
        Snapshot original = new Snapshot(SnapshotFixtures.meta(), Map.of(
                AdvancementsDataType.ADVANCEMENTS, fixture.type.encode(new Advancements(new AdvancementValue[]{unknown})),
                external, NBT.createString("opaque")));
        Snapshot source = assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(codec.encode(original))).snapshot();
        DataRegistry registry = new DataRegistry();
        registry.register(fixture.type);
        registry.freeze();
        Advancements decoded = (Advancements) new SnapshotDecoder(registry).decodeForApply(source).value(AdvancementsDataType.ADVANCEMENTS);
        if (nativeHandoff) {
            AdvancementSlots.Layout layout = slots.current();
            AdvancementsDataType.NativeEncoding nativeData = AdvancementsDataType.encodeNativeJson(decoded, layout);
            nativeHandoff(fixture.type, layout, decoded, nativeData.unknown()).accept(fixture.player);
        } else {
            fixture.type.apply(fixture.player, decoded);
        }
        progress.put(localHolder, localProgress);
        CriterionProgress criterion = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(localProgress).get("done");
        CriterionProgressProxy.INSTANCE.setObtained(criterion, obtained.plusSeconds(10));
        fixture.tracking.add(localHolder);
        Advancements captured = fixture.type.capture(fixture.player, CaptureMode.SYNC);
        SnapshotDataCodec dataCodec = new SnapshotDataCodec(CompressorRegistry.DEFLATE, 0);
        SnapshotData retained = dataCodec.decode(dataCodec.encode(source.content().select(external::equals)));
        Snapshot outgoing = new Snapshot(source.meta(), retained.with(Map.of(AdvancementsDataType.ADVANCEMENTS, fixture.type.encode(captured))));
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, codec.decode(codec.encode(outgoing))).snapshot();
        var before = source.content().raw(external);
        var after = restored.content().raw(external);
        assertArrayEquals(Arrays.copyOfRange(before.bytes(), (int) before.offset(), (int) before.end()),
                Arrays.copyOfRange(after.bytes(), (int) after.offset(), (int) after.end()));
        Advancements forwarded = fixture.type.decode(restored.data(AdvancementsDataType.ADVANCEMENTS));
        assertEquals(2, forwarded.values().length);
        assertEquals(obtained, findValue(forwarded, unknownId).obtained()[0]);
        assertEquals(obtained.plusSeconds(10), findValue(forwarded, localId).obtained()[0]);
        assertEquals(1, SnapshotFixtures.decodedBlockCount(source));
        assertEquals(0, SnapshotFixtures.decodedBlockCount(new Snapshot(source.meta(), retained)));
        assertEquals(1, SnapshotFixtures.decodedBlockCount(restored));
    }

    @Test
    void playerApplyDiscardsUnknownAdvancementsWhenRetentionIsDisabled() throws Exception {
        Object localId = IdentifierProxy.INSTANCE.tryParse("ce:b");
        Object unknownId = IdentifierProxy.INSTANCE.tryParse("ce:a");
        AdvancementHolder localHolder = holder(localId);
        Map<Object, Object> progress = new LinkedHashMap<>();
        progress.put(localHolder, progress("done", null));
        Map<Object, Object> advancements = Map.of(localId, localHolder);
        AdvancementSlots slots = new AdvancementSlots(() -> advancements);
        PlayerFixture fixture = playerFixture(progress, slots);
        AdvancementValue unknown = new AdvancementValue(unknownId, new String[]{"done"}, new Instant[]{Instant.now()}, true);
        setKeepUnknownAdvancements(false);

        try {
            fixture.type.apply(fixture.player, new Advancements(new AdvancementValue[]{unknown}));
            assertEquals(0, fixture.type.capture(fixture.player, CaptureMode.SYNC).values().length);
        } finally {
            setKeepUnknownAdvancements(true);
        }

        assertEquals(0, fixture.tracking.retainedUnknown().length);
    }

    @Test
    void nativeHandoffSeedsTrackerWithoutPlayerApply() throws Exception {
        Object localId = IdentifierProxy.INSTANCE.tryParse("ce:b");
        Object unknownId = IdentifierProxy.INSTANCE.tryParse("ce:a");
        AdvancementHolder localHolder = holder(localId);
        Map<Object, Object> progress = new LinkedHashMap<>();
        progress.put(localHolder, progress("done", null));
        Map<Object, Object> advancements = Map.of(localId, localHolder);
        AdvancementSlots slots = new AdvancementSlots(() -> advancements);
        PlayerFixture fixture = playerFixture(progress, slots);
        AdvancementValue unknown = new AdvancementValue(unknownId, new String[]{"done"}, new Instant[]{Instant.now()}, true);
        Advancements snapshot = new Advancements(new AdvancementValue[]{unknown});
        AdvancementSlots.Layout layout = slots.current();

        nativeHandoff(fixture.type, layout, snapshot, new AdvancementValue[]{unknown}).accept(fixture.player);

        assertArrayEquals(new AdvancementValue[]{unknown}, fixture.tracking.retainedUnknown());
        assertSame(unknown, fixture.type.capture(fixture.player, CaptureMode.SYNC).values()[0]);
    }

    @Test
    void nativeHandoffFallsBackWhenAdvancementLayoutChanges() throws Exception {
        Object firstId = IdentifierProxy.INSTANCE.tryParse("ce:a");
        Object secondId = IdentifierProxy.INSTANCE.tryParse("ce:b");
        AdvancementHolder firstHolder = holder(firstId);
        AdvancementHolder secondHolder = holder(secondId);
        AtomicReference<Map<?, ?>> source = new AtomicReference<>(Map.of(firstId, firstHolder));
        AdvancementSlots slots = new AdvancementSlots(source::get);
        AdvancementSlots.Layout initial = slots.current();
        Advancements snapshot = new Advancements(new AdvancementValue[]{new AdvancementValue(firstId, new String[]{"done"}, new Instant[]{Instant.now()}, true)});
        PlayerFixture fixture = playerFixture(new LinkedHashMap<>(), slots);
        Consumer<Player> handoff = nativeHandoff(fixture.type, initial, snapshot, new AdvancementValue[0]);
        source.set(Map.of(secondId, secondHolder));

        handoff.accept(fixture.player);

        assertArrayEquals(snapshot.values(), fixture.tracking.retainedUnknown());
    }

    @Test
    void nativeJsonPartitionsUnknownAdvancementIds() throws Exception {
        Object localId = IdentifierProxy.INSTANCE.tryParse("ce:b");
        Object unknownId = IdentifierProxy.INSTANCE.tryParse("ce:a");
        AdvancementHolder localHolder = holder(localId);
        Map<Object, Object> advancements = Map.of(localId, localHolder);
        AdvancementSlots.Layout layout = new AdvancementSlots(() -> advancements).current();
        AdvancementValue local = new AdvancementValue(localId, new String[0], new Instant[0], false);
        AdvancementValue unknown = new AdvancementValue(unknownId, new String[0], new Instant[0], false);

        AdvancementsDataType.NativeEncoding encoded = AdvancementsDataType.encodeNativeJson(new Advancements(new AdvancementValue[]{local, unknown}), layout);
        JsonObject root = JsonParser.parseString(new String(encoded.json(), StandardCharsets.UTF_8)).getAsJsonObject();

        assertTrue(root.has(localId.toString()));
        assertFalse(root.has(unknownId.toString()));
        assertArrayEquals(new AdvancementValue[]{unknown}, encoded.unknown());
    }

    @Test
    void nativeJsonKeepsEverySnapshotValueWhenUnknownRetentionIsDisabled() {
        CountingId localId = new CountingId("ce:b");
        CountingId unknownId = new CountingId("ce:a");
        AdvancementValue local = new AdvancementValue(localId, new String[0], new Instant[0], false);
        AdvancementValue unknown = new AdvancementValue(unknownId, new String[0], new Instant[0], false);

        AdvancementsDataType.NativeEncoding encoded = AdvancementsDataType.encodeNativeJson(new Advancements(new AdvancementValue[]{local, unknown}), null);
        JsonObject root = JsonParser.parseString(new String(encoded.json(), StandardCharsets.UTF_8)).getAsJsonObject();

        assertTrue(root.has(localId.toString()));
        assertTrue(root.has(unknownId.toString()));
        assertEquals(0, encoded.unknown().length);
        assertEquals(0, localId.hashCalls + unknownId.hashCalls);
    }

    @Test
    void retainedUnknownDoesNotShadowProgressCreatedAfterReload() throws Exception {
        Object id = IdentifierProxy.INSTANCE.tryParse("ce:unknown");
        AdvancementHolder holder = holder(id);
        Instant remoteTime = Instant.now().minusSeconds(60);
        Instant localTime = remoteTime.plusSeconds(10);
        AtomicReference<Map<?, ?>> source = new AtomicReference<>(Map.of());
        AdvancementSlots slots = new AdvancementSlots(source::get);
        Map<Object, Object> progress = new LinkedHashMap<>();
        PlayerFixture fixture = playerFixture(progress, slots);
        AdvancementValue unknown = new AdvancementValue(id, new String[]{"done"}, new Instant[]{remoteTime}, true);
        AdvancementValue[] retained = new AdvancementValue[]{unknown};
        fixture.tracking.retainedUnknown(retained);
        assertSame(unknown, fixture.type.capture(fixture.player, CaptureMode.SYNC).values()[0]);

        source.set(Map.of(id, holder));
        AdvancementProgress local = progress("done", null);
        progress.put(holder, local);
        assertSame(unknown, fixture.type.capture(fixture.player, CaptureMode.SYNC).values()[0]);
        CriterionProgress criterion = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(local).get("done");
        CriterionProgressProxy.INSTANCE.setObtained(criterion, localTime);
        fixture.tracking.add(holder);

        Advancements captured = fixture.type.capture(fixture.player, CaptureMode.SYNC);

        assertEquals(1, captured.values().length);
        assertArrayEquals(new Instant[]{localTime.truncatedTo(ChronoUnit.SECONDS)}, captured.values()[0].obtained());
        assertSame(retained, fixture.tracking.retainedUnknown());

        CriterionProgressProxy.INSTANCE.setObtained(criterion, null);
        fixture.tracking.add(holder);
        assertEquals(0, fixture.type.capture(fixture.player, CaptureMode.SYNC).values().length);
        assertSame(retained, fixture.tracking.retainedUnknown());
    }

    @Test
    void cachedCaptureReusesUnchangedValuesAndRefreshesOnlyDirtySlots() throws Exception {
        Object firstId = IdentifierProxy.INSTANCE.tryParse("example:first");
        Object secondId = IdentifierProxy.INSTANCE.tryParse("example:second");
        AdvancementHolder firstHolder = holder(firstId);
        AdvancementHolder secondHolder = holder(secondId);
        Instant original = Instant.parse("2026-09-05T00:00:00Z");
        ObservedProgress first = new ObservedProgress(original);
        ObservedProgress second = new ObservedProgress(original);
        Map<Object, Object> progress = new LinkedHashMap<>(Map.of(firstHolder, first, secondHolder, second));
        Map<Object, Object> registry = Map.of(firstId, firstHolder, secondId, secondHolder);
        PlayerFixture fixture = playerFixture(progress, new AdvancementSlots(() -> registry));

        Advancements before = fixture.type.capture(fixture.player, CaptureMode.SYNC);
        fixture.player.getHandle().getAdvancements().flushDirty(fixture.player.getHandle(), false);
        assertTrue(fixture.tracking.isEmpty());
        assertSame(before, fixture.type.capture(fixture.player, CaptureMode.SYNC));
        assertEquals(1, first.captures);
        assertEquals(1, second.captures);

        CriterionProgressProxy.INSTANCE.setObtained(first.getCriterion("done"), original.plusSeconds(5));
        fixture.tracking.add(firstHolder);
        fixture.player.getHandle().getAdvancements().flushDirty(fixture.player.getHandle(), false);
        Advancements after = fixture.type.capture(fixture.player, CaptureMode.SYNC);

        assertEquals(2, first.captures);
        assertEquals(1, second.captures);
        assertNotSame(before, after);
        assertSame(findValue(before, secondId), findValue(after, secondId));
        assertArrayEquals(new Instant[]{original}, findValue(before, firstId).obtained());
        assertArrayEquals(new Instant[]{original.plusSeconds(5)}, findValue(after, firstId).obtained());
    }

    @Test
    void cachedCaptureObservesLastRevokeAfterVanillaClearsDirtySet() throws Exception {
        Object id = IdentifierProxy.INSTANCE.tryParse("example:revoked");
        AdvancementHolder holder = holder(id);
        Instant original = Instant.parse("2026-09-05T00:00:00Z");
        AdvancementProgress progress = progress("done", original);
        Map<Object, Object> values = new LinkedHashMap<>(Map.of(holder, progress));
        Map<Object, Object> registry = Map.of(id, holder);
        PlayerFixture fixture = playerFixture(values, new AdvancementSlots(() -> registry));
        Advancements before = fixture.type.capture(fixture.player, CaptureMode.SYNC);

        CriterionProgressProxy.INSTANCE.setObtained(progress.getCriterion("done"), null);
        fixture.tracking.add(holder);
        fixture.tracking.clear();

        assertEquals(0, fixture.type.capture(fixture.player, CaptureMode.SYNC).values().length);
        assertArrayEquals(new Instant[]{original}, before.values()[0].obtained());

        CriterionProgressProxy.INSTANCE.setObtained(progress.getCriterion("done"), original.plusSeconds(1));
        fixture.tracking.add(holder);
        assertArrayEquals(new Instant[]{original.plusSeconds(1)}, fixture.type.capture(fixture.player, CaptureMode.SYNC).values()[0].obtained());
    }

    @Test
    void cachedCaptureRebuildsAgainstReplacementHoldersAndDropsDeletedSlots() throws Exception {
        Object firstId = IdentifierProxy.INSTANCE.tryParse("example:first");
        Object secondId = IdentifierProxy.INSTANCE.tryParse("example:second");
        AdvancementHolder first = holder(firstId);
        AdvancementHolder replacement = holder(firstId);
        AdvancementHolder second = holder(secondId);
        Instant original = Instant.parse("2026-09-05T00:00:00Z");
        AtomicReference<Map<?, ?>> registry = new AtomicReference<>(Map.of(firstId, first));
        Map<Object, Object> progress = new LinkedHashMap<>(Map.of(first, progress("done", original)));
        PlayerFixture fixture = playerFixture(progress, new AdvancementSlots(registry::get));
        Advancements before = fixture.type.capture(fixture.player, CaptureMode.SYNC);

        registry.set(Map.of(firstId, replacement, secondId, second));
        progress.clear();
        fixture.tracking.clear();
        progress.put(replacement, progress("done", original.plusSeconds(1)));
        progress.put(second, progress("done", original.plusSeconds(2)));
        fixture.tracking.add(replacement);
        fixture.tracking.add(second);
        Advancements reloaded = fixture.type.capture(fixture.player, CaptureMode.SYNC);

        assertEquals(2, reloaded.values().length);
        assertArrayEquals(new Instant[]{original.plusSeconds(1)}, findValue(reloaded, firstId).obtained());
        assertArrayEquals(new Instant[]{original}, before.values()[0].obtained());

        registry.set(Map.of(secondId, second));
        progress.remove(replacement);
        Advancements removed = fixture.type.capture(fixture.player, CaptureMode.SYNC);
        assertEquals(1, removed.values().length);
        assertEquals(secondId, removed.values()[0].id());
    }

    @Test
    void cachedCaptureDropsReloadedProgressEvenWhenTheRegistryMapIsUnchanged() throws Exception {
        Object id = IdentifierProxy.INSTANCE.tryParse("example:reload");
        AdvancementHolder holder = holder(id);
        Map<Object, Object> progress = new LinkedHashMap<>(Map.of(holder, progress("done", Instant.now())));
        Map<Object, Object> registry = Map.of(id, holder);
        PlayerFixture fixture = playerFixture(progress, new AdvancementSlots(() -> registry));
        assertEquals(1, fixture.type.capture(fixture.player, CaptureMode.SYNC).values().length);

        progress.clear();
        fixture.tracking.clear();
        progress.put(holder, progress("done", null));

        assertEquals(0, fixture.type.capture(fixture.player, CaptureMode.SYNC).values().length);
    }

    @Test
    void failedCacheRefreshKeepsDirtySlotsForTheNextCapture() throws Exception {
        Object id = IdentifierProxy.INSTANCE.tryParse("example:retry");
        AdvancementHolder holder = holder(id);
        Instant original = Instant.parse("2026-09-05T00:00:00Z");
        ObservedProgress progress = new ObservedProgress(original);
        Map<Object, Object> values = new LinkedHashMap<>(Map.of(holder, progress));
        Map<Object, Object> registry = Map.of(id, holder);
        PlayerFixture fixture = playerFixture(values, new AdvancementSlots(() -> registry));
        Advancements before = fixture.type.capture(fixture.player, CaptureMode.SYNC);
        CriterionProgressProxy.INSTANCE.setObtained(progress.getCriterion("done"), original.plusSeconds(1));
        fixture.tracking.add(holder);
        progress.onCapture = () -> { throw new IllegalStateException("capture failed"); };

        assertThrows(IllegalStateException.class, () -> fixture.type.capture(fixture.player, CaptureMode.SYNC));
        progress.onCapture = null;
        Advancements recovered = fixture.type.capture(fixture.player, CaptureMode.SYNC);

        assertArrayEquals(new Instant[]{original.plusSeconds(1)}, recovered.values()[0].obtained());
        assertArrayEquals(new Instant[]{original}, before.values()[0].obtained());
    }

    @Test
    void overlappingCaptureDoesNotWaitOrConsumeChangesArrivingDuringRefresh() throws Exception {
        Object firstId = IdentifierProxy.INSTANCE.tryParse("example:first");
        Object secondId = IdentifierProxy.INSTANCE.tryParse("example:second");
        AdvancementHolder firstHolder = holder(firstId);
        AdvancementHolder secondHolder = holder(secondId);
        Instant original = Instant.parse("2026-09-05T00:00:00Z");
        ObservedProgress first = new ObservedProgress(original);
        AdvancementProgress second = progress("done", null);
        Map<Object, Object> values = new LinkedHashMap<>(Map.of(firstHolder, first, secondHolder, second));
        Map<Object, Object> registry = Map.of(firstId, firstHolder, secondId, secondHolder);
        PlayerFixture fixture = playerFixture(values, new AdvancementSlots(() -> registry));
        fixture.type.capture(fixture.player, CaptureMode.SYNC);
        CriterionProgressProxy.INSTANCE.setObtained(first.getCriterion("done"), original.plusSeconds(1));
        fixture.tracking.add(firstHolder);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blocked = new AtomicBoolean();
        first.onCapture = () -> {
            if (blocked.compareAndSet(false, true)) {
                reading.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("capture was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        };

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var refreshing = workers.submit(() -> fixture.type.capture(fixture.player, CaptureMode.SYNC));
            try {
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                CriterionProgressProxy.INSTANCE.setObtained(second.getCriterion("done"), original.plusSeconds(2));
                fixture.tracking.add(secondHolder);
                fixture.tracking.clear();
                Advancements overlapping = workers.submit(() -> fixture.type.capture(fixture.player, CaptureMode.SYNC)).get(2, TimeUnit.SECONDS);
                assertEquals(2, overlapping.values().length);
            } finally {
                release.countDown();
            }
            refreshing.get(5, TimeUnit.SECONDS);
        }

        Advancements after = fixture.type.capture(fixture.player, CaptureMode.SYNC);
        assertEquals(2, after.values().length);
        assertArrayEquals(new Instant[]{original.plusSeconds(2)}, findValue(after, secondId).obtained());
    }

    @Test
    void incrementalCaptureMatchesDenseReferenceAcrossGrantsAndRevokes() throws Exception {
        int size = 130;
        AdvancementHolder[] holders = new AdvancementHolder[size];
        AdvancementProgress[] progresses = new AdvancementProgress[size];
        Map<Object, Object> progress = new LinkedHashMap<>();
        Map<Object, Object> registry = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            Object id = IdentifierProxy.INSTANCE.tryParse("example:sequence_" + i);
            holders[i] = holder(id);
            progresses[i] = new AdvancementProgress();
            progresses[i].update(AdvancementRequirements.allOf(List.of("first", "second")));
            progress.put(holders[i], progresses[i]);
            registry.put(id, holders[i]);
        }
        PlayerFixture fixture = playerFixture(progress, new AdvancementSlots(() -> registry));
        assertEquals(0, fixture.type.capture(fixture.player, CaptureMode.SYNC).values().length);
        Random random = new Random(12345);
        Instant start = Instant.parse("2026-09-05T00:00:00Z");
        for (int step = 0; step < 400; step++) {
            int slot = random.nextInt(size);
            String criterion = random.nextBoolean() ? "first" : "second";
            Instant time = random.nextBoolean() ? start.plusMillis(step * 123L) : null;
            CriterionProgressProxy.INSTANCE.setObtained(progresses[slot].getCriterion(criterion), time);
            fixture.tracking.add(holders[slot]);
            fixture.tracking.clear();

            Advancements expected = AdvancementsDataType.captureDense(progress);
            Advancements captured = fixture.type.capture(fixture.player, CaptureMode.SYNC);
            assertEquals(expected.values().length, captured.values().length);
            for (int i = 0; i < expected.values().length; i++) {
                AdvancementValue reference = expected.values()[i];
                AdvancementValue actual = captured.values()[i];
                assertEquals(reference.id(), actual.id());
                assertArrayEquals(reference.criteria(), actual.criteria());
                assertArrayEquals(reference.obtained(), actual.obtained());
                assertEquals(reference.done(), actual.done());
            }
        }
    }

    @Test
    void cachedCaptureMergesTheCurrentUnknownSetAndRetentionSetting() throws Exception {
        Object localId = IdentifierProxy.INSTANCE.tryParse("example:local");
        Object unknownId = IdentifierProxy.INSTANCE.tryParse("example:unknown");
        AdvancementHolder holder = holder(localId);
        Instant obtained = Instant.parse("2026-09-05T00:00:00Z");
        Map<Object, Object> progress = new LinkedHashMap<>(Map.of(holder, progress("done", obtained)));
        Map<Object, Object> registry = Map.of(localId, holder);
        PlayerFixture fixture = playerFixture(progress, new AdvancementSlots(() -> registry));
        Advancements known = fixture.type.capture(fixture.player, CaptureMode.SYNC);
        AdvancementValue unknown = new AdvancementValue(unknownId, new String[]{"done"}, new Instant[]{obtained}, true);
        fixture.tracking.retainedUnknown(new AdvancementValue[]{unknown});

        assertEquals(2, fixture.type.capture(fixture.player, CaptureMode.SYNC).values().length);
        setKeepUnknownAdvancements(false);
        try {
            assertSame(known, fixture.type.capture(fixture.player, CaptureMode.SYNC));
        } finally {
            setKeepUnknownAdvancements(true);
        }
        assertSame(unknown, findValue(fixture.type.capture(fixture.player, CaptureMode.SYNC), unknownId));
        fixture.tracking.retainedUnknown(new AdvancementValue[0]);
        assertSame(known, fixture.type.capture(fixture.player, CaptureMode.SYNC));
    }

    @Test
    void candidateSnapshotsDoNotLoseBitsWhileTheWriterGrowsTheArray() throws Exception {
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

        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < holders.length; i++) tracking.add(holders[i]);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        while (writer.isAlive()) tracking.candidates();
        writer.join();

        assertNull(failure.get());
        assertEquals(count, BitSet.valueOf(tracking.candidates()).cardinality());
    }

    @Test
    void unstableLayoutMarksTrackingCandidatesIncomplete() throws Exception {
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
        assertArrayEquals(new Instant[]{obtained.truncatedTo(ChronoUnit.SECONDS)}, captured.obtained());
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

        assertThrows(IOException.class, () -> new AdvancementsDataType().decode(root));
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

    private static AdvancementHolder holder(Object id) throws Exception {
        return (AdvancementHolder) AdvancementHolder.class.getDeclaredConstructors()[0].newInstance(id, null);
    }

    private static AdvancementHolder applicableHolder(Object id) throws Exception {
        Advancement advancement = Advancement.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}}}")).getOrThrow();
        return (AdvancementHolder) AdvancementHolder.class.getDeclaredConstructors()[0].newInstance(id, advancement);
    }

    private static <T> T allocateWithoutConstructor(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setKeepUnknownAdvancements(boolean value) throws ReflectiveOperationException {
        PluginConfig.AdvancementsOptions options = PluginConfig.synchronization$advancements();
        Field keepUnknown = PluginConfig.AdvancementsOptions.class.getDeclaredField("keepUnknownAdvancements");
        keepUnknown.setAccessible(true);
        keepUnknown.setBoolean(options, value);
    }

    private static AdvancementProgress progress(String criterionName, Instant obtained) {
        AdvancementProgress progress = new AdvancementProgress();
        progress.update(AdvancementRequirements.allOf(List.of(criterionName)));
        if (obtained != null) {
            CriterionProgress criterion = (CriterionProgress) AdvancementProgressProxy.INSTANCE.getCriteria(progress).get(criterionName);
            CriterionProgressProxy.INSTANCE.setObtained(criterion, obtained);
        }
        return progress;
    }

    private static byte[] encodeNativeJson(Advancements value) {
        return AdvancementsDataType.encodeNativeJson(value, null).json();
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Player> nativeHandoff(AdvancementsDataType type, AdvancementSlots.Layout layout, Advancements snapshot, AdvancementValue[] unknown) throws Exception {
        Method method = AdvancementsDataType.class.getDeclaredMethod("nativeHandoff", AdvancementSlots.Layout.class, Advancements.class, AdvancementValue[].class);
        method.setAccessible(true);
        return (Consumer<Player>) method.invoke(type, layout, snapshot, unknown);
    }

    private static PlayerFixture playerFixture(Map<Object, Object> progress, AdvancementSlots slots) throws Exception {
        AdvancementProgressChangedWrapperSet tracking = new AdvancementProgressChangedWrapperSet(new HashSet<>(progress.keySet()), progress, slots);
        PlayerAdvancements advancements = allocateWithoutConstructor(PlayerAdvancements.class);
        setField(PlayerAdvancements.class, advancements, "progress", progress);
        setField(PlayerAdvancements.class, advancements, "progressChanged", tracking);
        setField(PlayerAdvancements.class, advancements, "rootsToUpdate", new HashSet<>());
        setField(PlayerAdvancements.class, advancements, "visible", new HashSet<>());
        ServerPlayer handle = allocateWithoutConstructor(ServerPlayer.class);
        setField(ServerPlayer.class, handle, "advancements", advancements);
        CraftPlayer player = allocateWithoutConstructor(CraftPlayer.class);
        setField(CraftEntity.class, player, "entity", handle);
        AdvancementsDataType type = new AdvancementsDataType();
        setField(AdvancementsDataType.class, type, "advancementSlots", slots);
        return new PlayerFixture(player, type, tracking);
    }

    private record PlayerFixture(CraftPlayer player, AdvancementsDataType type, AdvancementProgressChangedWrapperSet tracking) {
    }

    private static AdvancementValue findValue(Advancements value, Object id) {
        AdvancementValue[] values = value.values();
        for (int i = 0; i < values.length; i++) {
            if (values[i].id().equals(id)) return values[i];
        }
        throw new AssertionError("missing advancement " + id);
    }

    private static final class ObservedProgress extends AdvancementProgress {
        private int captures;
        private Runnable onCapture;

        private ObservedProgress(Instant obtained) {
            this.update(AdvancementRequirements.allOf(List.of("done")));
            CriterionProgressProxy.INSTANCE.setObtained(this.getCriterion("done"), obtained);
        }

        @Override
        public boolean isDone() {
            this.captures++;
            if (this.onCapture != null) {
                this.onCapture.run();
            }
            return super.isDone();
        }
    }

    private static Codec<Map<Object, AdvancementProgress>> vanillaCodec() {
        Codec<Map<Object, AdvancementProgress>> codec = Codec.unboundedMap(IdentifierProxy.INSTANCE.getCodec(), AdvancementProgress.CODEC);
        return DataFixTypes.ADVANCEMENTS.wrapCodec(codec, DataFixers.getDataFixer(), 1343);
    }

    private static final class CountingId {
        private final String value;
        private int hashCalls;

        private CountingId(String value) {
            this.value = value;
        }

        @Override
        public int hashCode() {
            this.hashCalls++;
            return this.value.hashCode();
        }

        @Override
        public String toString() {
            return this.value;
        }
    }
}
