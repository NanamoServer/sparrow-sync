package net.momirealms.sparrow.sync.snapshot.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.Connection;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataPipelineTest {
    private static final DataKey ALPHA = DataKey.of("test", "alpha");
    private static final DataKey BRAVO = DataKey.of("test", "bravo");
    private static final DataKey CHARLIE = DataKey.of("test", "charlie");

    private final SyncLogger logger = new SyncLogger(new QuietLogger());
    private final List<DataKey> applied = new ArrayList<>();
    private final List<DataKey> nativeApplied = new ArrayList<>();
    private final List<DataKey> nativeAttempts = new ArrayList<>();
    private final Connection connection = ConnectionFixture.create();
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
        case "getName", "toString" -> "TestPlayer";
        case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000042");
        case "hashCode" -> 0;
        case "equals" -> proxy == args[0];
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final PlayerSession session = new SessionManager(null).tryOpen(this.player.getUniqueId(), this.player.getName(), this.connection);

    @Test
    void captureSkipsFailingNonCriticalType() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA),
                new FakeType(BRAVO).failingCapture()
        );

        PlayerDataPipeline.CaptureResult.Ready ready = assertInstanceOf(PlayerDataPipeline.CaptureResult.Ready.class, pipeline.capture(this.player, CaptureMode.SYNC));

        assertEquals(Set.of(ALPHA), ready.values().keySet());
        assertEquals(List.of(BRAVO), ready.skipped());
    }

    @Test
    void encodeSkipsFailingNonCriticalType() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA),
                new FakeType(BRAVO).failingEncode()
        );
        PlayerDataPipeline.CaptureResult.Ready captured = assertInstanceOf(PlayerDataPipeline.CaptureResult.Ready.class, pipeline.capture(this.player, CaptureMode.SYNC));

        PlayerDataPipeline.EncodeResult.Ready ready = assertInstanceOf(PlayerDataPipeline.EncodeResult.Ready.class, pipeline.encode(captured));

        assertEquals(Set.of(ALPHA), ready.data().keySet());
        assertEquals(List.of(BRAVO), ready.skipped());
    }

    @Test
    void nonCriticalDecodeFailureCanBeRecoveredByEventData() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA),
                new FakeType(BRAVO).failingDecode()
        );
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, BRAVO));
        Map<DataKey, Object> eventData = new LinkedHashMap<>(context.pendingValues());
        eventData.put(BRAVO, "recovered");

        context.acceptEventValues(eventData);
        PlayerDataPipeline.ApplyResult.Success result = assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));

        assertEquals(List.of(ALPHA, BRAVO), result.applied());
        assertEquals(List.of(), result.skipped());
        assertEquals(List.of(SnapshotApplyContext.FailureStage.DECODE), result.failures().stream().map(SnapshotApplyContext.Failure::stage).toList());
    }

    @Test
    void eventRemovalMarksPendingTypeAsSkipped() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA),
                new FakeType(BRAVO)
        );
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, BRAVO));

        context.acceptEventValues(Map.of(ALPHA, "changed"));
        PlayerDataPipeline.ApplyResult.Success result = assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));

        assertEquals(List.of(ALPHA), result.applied());
        assertEquals(List.of(BRAVO), result.skipped());
    }

    @Test
    void criticalPlayerFailureAbortsRemainingTypes() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA),
                new FakeType(BRAVO, true, Set.of(ALPHA)).failingApply(),
                new FakeType(CHARLIE, false, Set.of(BRAVO))
        );

        PlayerDataPipeline.ApplyResult.Failure failure = assertInstanceOf(PlayerDataPipeline.ApplyResult.Failure.class,
                pipeline.apply(this.player, context(pipeline, snapshotWith(ALPHA, BRAVO, CHARLIE))));

        assertEquals(BRAVO, failure.failedKey());
        assertEquals(List.of(ALPHA), failure.appliedBefore());
        assertEquals(SnapshotApplyContext.FailureStage.PLAYER, failure.failures().getLast().stage());
        assertEquals(List.of(ALPHA), this.applied);
    }

    @Test
    void nonCriticalPlayerFailureIsSkipped() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA).failingApply(),
                new FakeType(BRAVO)
        );

        PlayerDataPipeline.ApplyResult.Success result = assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class,
                pipeline.apply(this.player, context(pipeline, snapshotWith(ALPHA, BRAVO))));

        assertEquals(List.of(BRAVO), result.applied());
        assertEquals(List.of(ALPHA), result.skipped());
        assertEquals(SnapshotApplyContext.FailureStage.PLAYER, result.failures().getFirst().stage());
    }

    @Test
    void nativeSuccessIsHiddenFromEventAndNotAppliedTwice() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new NativeFakeType(ALPHA, false, Set.of()),
                new FakeType(BRAVO)
        );
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, BRAVO));
        CompoundTag local = new CompoundTag();

        Optional<CompoundTag> resultTag = pipeline.applyNative(this.session, Optional.of(local), context);

        assertFalse(local.contains(ALPHA.asString()));
        assertEquals(List.of(ALPHA), this.nativeApplied);
        assertEquals(ALPHA.asString(), assertInstanceOf(StringTag.class, resultTag.orElseThrow().get(ALPHA.asString())).value());
        assertEquals(Set.of(BRAVO), context.pendingValues().keySet());

        Map<DataKey, Object> eventData = new LinkedHashMap<>(context.pendingValues());
        eventData.put(ALPHA, "must-be-ignored");
        context.acceptEventValues(eventData);
        PlayerDataPipeline.ApplyResult.Success applied = assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));

        assertEquals(List.of(ALPHA, BRAVO), applied.applied());
        assertEquals(List.of(BRAVO), this.applied);
    }

    @Test
    void nativeConversionPreservesUnknownDataAndDoesNotShareMutableTags() {
        PlayerDataPipeline pipeline = this.createPipeline(new NativeFakeType(ALPHA, false, Set.of()));
        CompoundTag nested = new CompoundTag();
        nested.putByteArray("bytes", new byte[]{1, 2, 3});
        nested.putIntArray("ints", new int[]{4, 5});
        nested.putLongArray("longs", new long[]{6, 7});
        nested.putString("marker", "local");
        CompoundTag local = new CompoundTag();
        local.put("unknown", nested);
        CompoundTag before = local.copy();

        CompoundTag result = pipeline.applyNative(this.session, Optional.of(local), context(pipeline, snapshotWith(ALPHA))).orElseThrow();

        assertEquals(before, local);
        assertEquals(nested, result.get("unknown"));
        CompoundTag resultNested = assertInstanceOf(CompoundTag.class, result.get("unknown"));
        resultNested.putString("marker", "changed");
        resultNested.getByteArray("bytes").orElseThrow()[0] = 9;
        resultNested.getIntArray("ints").orElseThrow()[0] = 9;
        resultNested.getLongArray("longs").orElseThrow()[0] = 9;
        assertEquals(before, local);
    }

    @Test
    void nativeNotAppliedStaysPendingWithoutFailure() {
        PlayerDataPipeline pipeline = this.createPipeline(new NativeFakeType(ALPHA, false, Set.of()).unsupportedNative());
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA));
        CompoundTag local = new CompoundTag();
        local.putString("local", "kept");

        assertSame(local, pipeline.applyNative(this.session, Optional.of(local), context).orElseThrow());

        assertFalse(local.contains(ALPHA.asString()));
        assertEquals(Set.of(ALPHA), context.pendingValues().keySet());
        assertEquals(List.of(), context.failures());
        assertEquals(List.of(ALPHA), assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context)).applied());
    }

    @Test
    void ineligibleExternalTypeStaysPendingWhilePlayerDataIsApplied() {
        NativeFakeType external = new NativeFakeType(ALPHA, false, Set.of()).externalNative();
        external.nativeEligibility = session -> {
            assertSame(this.session, session);
            assertSame(this.connection, session.connection());
            return false;
        };
        PlayerDataPipeline pipeline = this.createPipeline(external, new NativeFakeType(BRAVO, false, Set.of()));
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, BRAVO));
        Object pendingValue = context.pendingValues().get(ALPHA);

        CompoundTag playerData = pipeline.applyNative(this.session, Optional.empty(), context).orElseThrow();

        assertEquals(List.of(BRAVO), this.nativeAttempts);
        assertEquals(Set.of(ALPHA), context.pendingValues().keySet());
        assertSame(pendingValue, context.pendingValues().get(ALPHA));
        assertEquals(List.of(), context.failures());
        assertFalse(playerData.contains(ALPHA.asString()));
        assertTrue(playerData.contains(BRAVO.asString()));

        assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));
        assertEquals(List.of(ALPHA), this.applied);
    }

    @Test
    void eligibilityFailureFallsBackAndContinuesOtherNativeTypes() {
        NativeFakeType external = new NativeFakeType(ALPHA, false, Set.of()).externalNative();
        external.nativeEligibility = session -> {
            throw new LinkageError("legacy player field unavailable");
        };
        PlayerDataPipeline pipeline = this.createPipeline(external, new NativeFakeType(BRAVO, false, Set.of()));
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, BRAVO));

        pipeline.applyNative(this.session, Optional.empty(), context);

        assertEquals(List.of(BRAVO), this.nativeAttempts);
        assertEquals(Set.of(ALPHA), context.pendingValues().keySet());
        assertEquals(List.of(SnapshotApplyContext.FailureStage.NATIVE), context.failures().stream().map(SnapshotApplyContext.Failure::stage).toList());
        assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));
        assertEquals(List.of(ALPHA), this.applied);
    }

    @Test
    void externalNativeSuccessDoesNotPublishSyntheticPlayerData() {
        PlayerDataPipeline pipeline = this.createPipeline(new NativeFakeType(ALPHA, false, Set.of()).externalNative());
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA));

        Optional<CompoundTag> playerData = pipeline.applyNative(this.session, Optional.empty(), context);
        PlayerDataPipeline.ApplyResult.Success result = assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));

        assertTrue(playerData.isEmpty());
        assertEquals(List.of(ALPHA), this.nativeApplied);
        assertEquals(List.of(ALPHA), result.applied());
        assertEquals(List.of(), this.applied);
    }

    @Test
    void nativeHandoffsRunOnceInDependencyOrderAlongsidePlayerApply() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new NativeFakeType(ALPHA, false, Set.of()).withHandoff(player -> {
                    assertSame(this.player, player);
                    this.applied.add(ALPHA);
                }),
                new NativeFakeType(BRAVO, false, Set.of(ALPHA)).externalNative().withHandoff(player -> this.applied.add(BRAVO)),
                new FakeType(CHARLIE, false, Set.of(BRAVO))
        );
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, BRAVO, CHARLIE));

        Optional<CompoundTag> playerData = pipeline.applyNative(this.session, Optional.empty(), context);

        assertTrue(playerData.isPresent());
        assertEquals(List.of(), this.applied);
        assertEquals(Set.of(CHARLIE), context.pendingValues().keySet());
        assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));
        assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));
        assertEquals(List.of(ALPHA, BRAVO, CHARLIE), this.applied);
        assertNull(context.nativeHandoffAt(0));
        assertNull(context.nativeHandoffAt(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handoffFailureUsesItsDataTypeCriticality(boolean critical) {
        PlayerDataPipeline pipeline = this.createPipeline(
                new NativeFakeType(ALPHA, critical, Set.of()).externalNative().withHandoff(player -> {
                    throw new IllegalStateException("handoff failed");
                }),
                new FakeType(BRAVO, false, Set.of(ALPHA))
        );
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, BRAVO));
        assertTrue(pipeline.applyNative(this.session, Optional.empty(), context).isEmpty());

        PlayerDataPipeline.ApplyResult result = pipeline.apply(this.player, context);

        if (critical) {
            assertEquals(ALPHA, assertInstanceOf(PlayerDataPipeline.ApplyResult.Failure.class, result).failedKey());
            assertEquals(List.of(), this.applied);
        } else {
            assertEquals(List.of(ALPHA), assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, result).skipped());
            assertEquals(List.of(BRAVO), this.applied);
        }
        assertEquals(SnapshotApplyContext.FailureStage.PLAYER, context.failures().getFirst().stage());
        assertNull(context.nativeHandoffAt(0));
    }

    @Test
    void everyNativeFailureFallsBackAndLaterNativeTypesContinue() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new NativeFakeType(ALPHA, true, Set.of()).failingNative(),
                new NativeFakeType(BRAVO, false, Set.of(ALPHA)).failingNative(),
                new NativeFakeType(CHARLIE, false, Set.of(BRAVO))
        );
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, BRAVO, CHARLIE));

        pipeline.applyNative(this.session, Optional.of(new CompoundTag()), context);

        assertEquals(List.of(ALPHA, BRAVO, CHARLIE), this.nativeAttempts);
        assertEquals(List.of(CHARLIE), this.nativeApplied);
        assertEquals(Set.of(ALPHA, BRAVO), context.pendingValues().keySet());
        assertEquals(List.of(ALPHA, BRAVO), context.failures().stream().map(SnapshotApplyContext.Failure::key).toList());
        assertTrue(context.failures().stream().allMatch(failure -> failure.stage() == SnapshotApplyContext.FailureStage.NATIVE));

        PlayerDataPipeline.ApplyResult.Success result = assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));
        assertEquals(List.of(ALPHA, BRAVO, CHARLIE), result.applied());
        assertEquals(List.of(), result.skipped());
        assertEquals(List.of(ALPHA, BRAVO), this.applied);
    }

    @Test
    void syntheticPlayerDataOnlyExistsWhenANativeSlotWasWritten() {
        PlayerDataPipeline joinOnly = this.createPipeline(new FakeType(ALPHA));
        SnapshotApplyContext joinOnlyContext = context(joinOnly, snapshotWith(ALPHA));

        assertTrue(joinOnly.applyNative(this.session, Optional.empty(), joinOnlyContext).isEmpty());

        PlayerDataPipeline nativePipeline = this.createPipeline(new NativeFakeType(ALPHA, false, Set.of()));
        SnapshotApplyContext nativeContext = context(nativePipeline, snapshotWith(ALPHA));
        CompoundTag root = nativePipeline.applyNative(this.session, Optional.empty(), nativeContext).orElseThrow();

        assertTrue(root.contains("DataVersion"));
        assertTrue(assertInstanceOf(CompoundTag.class, root.get("bukkit")).contains("firstPlayed"));
    }

    @Test
    void freezeIncludesTypesRegisteredByThirdParties() {
        FakeType thirdParty = new FakeType(BRAVO);
        PlayerDataPipeline pipeline = this.createPipeline(thirdParty, new FakeType(ALPHA));

        pipeline.apply(this.player, context(pipeline, snapshotWith(ALPHA, BRAVO)));

        assertTrue(this.applied.contains(BRAVO));
    }

    @Test
    void unregisteredSnapshotDataIsRetainedForNextSave() {
        PlayerDataPipeline pipeline = this.createPipeline(new FakeType(ALPHA));
        DataKey unknown = DataKey.of("other", "unknown");

        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, unknown));

        assertEquals(Set.of(ALPHA), context.pendingValues().keySet());
        assertEquals(Set.of(unknown), context.passthrough().keys());
    }

    /** 全部类型都已注册时, 应用上下文复用无帧字节的空数据体. */
    @Test
    void knownTypesDoNotAllocateRetainedFrame() {
        PlayerDataPipeline pipeline = this.createPipeline(new FakeType(ALPHA));
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA));
        assertSame(EagerSnapshotData.EMPTY, context.passthrough());
        assertTrue(context.passthrough().keys().isEmpty());
    }

    private PlayerDataPipeline createPipeline(FakeType... types) {
        DataRegistry registry = new DataRegistry();
        for (FakeType type : types) {
            registry.register(type);
        }
        registry.freeze();
        PlayerDataPipeline pipeline = new PlayerDataPipeline(null);
        setField(pipeline, "dataRegistry", registry);
        setField(pipeline, "decoder", new SnapshotDecoder(registry));
        setField(pipeline, "binaryCodec", new BinarySnapshotCodec(CompressorRegistry.DEFLATE));
        setField(pipeline, "logger", this.logger);
        return pipeline;
    }

    private static SnapshotApplyContext context(PlayerDataPipeline pipeline, Snapshot snapshot) {
        return assertInstanceOf(PlayerDataPipeline.DecodeResult.Ready.class, pipeline.decode(snapshot)).context();
    }

    private static Snapshot snapshotWith(DataKey... keys) {
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        for (DataKey key : keys) {
            data.put(key, NBT.createString(key.asString()));
        }
        SnapshotMeta meta = SnapshotMeta.builder()
                .player(UUID.randomUUID())
                .timestamp(1L)
                .cause(SaveCause.DISCONNECT)
                .build();
        return new Snapshot(meta, data);
    }

    private static void setField(Object instance, String name, Object value) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private class FakeType implements PlayerDataType<String> {
        private final DataKey key;
        private final boolean critical;
        private final Set<DataKey> dependencies;
        private boolean captureFails;
        private boolean encodeFails;
        private boolean decodeFails;
        private boolean applyFails;

        private FakeType(DataKey key) {
            this(key, false, Set.of());
        }

        private FakeType(DataKey key, boolean critical, Set<DataKey> dependencies) {
            this.key = key;
            this.critical = critical;
            this.dependencies = Set.copyOf(dependencies);
        }

        private FakeType failingCapture() {
            this.captureFails = true;
            return this;
        }

        private FakeType failingEncode() {
            this.encodeFails = true;
            return this;
        }

        private FakeType failingDecode() {
            this.decodeFails = true;
            return this;
        }

        private FakeType failingApply() {
            this.applyFails = true;
            return this;
        }

        @Override
        @NotNull
        public DataKey key() {
            return this.key;
        }

        @Override
        public boolean critical() {
            return this.critical;
        }

        @Override
        @NotNull
        public Set<DataKey> dependencies() {
            return this.dependencies;
        }

        @Override
        @NotNull
        public String capture(@NotNull Player player, @NotNull CaptureMode mode) {
            if (this.captureFails) throw new IllegalStateException("capture of " + this.key + " failed");
            return this.key.asString();
        }

        @Override
        @NotNull
        public Tag encode(@NotNull String value) {
            if (this.encodeFails) throw new IllegalStateException("encode of " + this.key + " failed");
            return NBT.createString(value);
        }

        @Override
        @NotNull
        public String decode(@NotNull Tag data, int mcDataVersion) throws IOException {
            if (this.decodeFails) throw new IOException("corrupted");
            return data.getAsString();
        }

        @Override
        public void apply(@NotNull Player player, @NotNull String value) {
            if (this.applyFails) throw new IllegalStateException("apply failed");
            PlayerDataPipelineTest.this.applied.add(this.key);
        }
    }

    private final class NativeFakeType extends FakeType implements NativePlayerDataType<String> {
        private NativeApplyResult nativeResult = NativeApplyResult.APPLIED_PLAYER_DATA;
        private boolean nativeFails;
        private Predicate<PlayerSession> nativeEligibility = session -> true;

        private NativeFakeType(DataKey key, boolean critical, Set<DataKey> dependencies) {
            super(key, critical, dependencies);
        }

        private NativeFakeType unsupportedNative() {
            this.nativeResult = NativeApplyResult.NOT_APPLIED;
            return this;
        }

        private NativeFakeType externalNative() {
            this.nativeResult = NativeApplyResult.APPLIED_EXTERNAL;
            return this;
        }

        private NativeFakeType withHandoff(Consumer<Player> joinHandoff) {
            this.nativeResult = this.nativeResult.withHandoff(joinHandoff);
            return this;
        }

        private NativeFakeType failingNative() {
            this.nativeFails = true;
            return this;
        }

        @Override
        public boolean shouldApply(@NotNull PlayerSession session) {
            assertSame(PlayerDataPipelineTest.this.session, session);
            return this.nativeEligibility.test(session);
        }

        @Override
        @NotNull
        public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull net.momirealms.sparrow.nbt.CompoundTag playerData, @NotNull String value) {
            assertSame(PlayerDataPipelineTest.this.session, session);
            PlayerDataPipelineTest.this.nativeAttempts.add(this.key());
            if (this.nativeFails) throw new IllegalStateException("native failed");
            if (this.nativeResult.target() == NativeApplyResult.Target.NOT_APPLIED) return this.nativeResult;
            if (this.nativeResult.target() == NativeApplyResult.Target.APPLIED) playerData.putString(this.key().asString(), value);
            PlayerDataPipelineTest.this.nativeApplied.add(this.key());
            return this.nativeResult;
        }
    }

    private static final class QuietLogger implements PluginLogger {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void warn(String message, Throwable throwable) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }
    }
}
