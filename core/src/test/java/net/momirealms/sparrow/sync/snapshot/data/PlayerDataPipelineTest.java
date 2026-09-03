package net.momirealms.sparrow.sync.snapshot.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataPipelineTest {
    private static final DataKey ALPHA = DataKey.of("test", "alpha");
    private static final DataKey BRAVO = DataKey.of("test", "bravo");
    private static final DataKey CHARLIE = DataKey.of("test", "charlie");

    private final QuietLogger console = new QuietLogger();
    private final SyncLogger logger = new SyncLogger(this.console);
    private final List<DataKey> applied = new ArrayList<>();
    private final List<DataKey> nativeApplied = new ArrayList<>();
    private final List<DataKey> nativeAttempts = new ArrayList<>();
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
        case "getName", "toString" -> "TestPlayer";
        case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000042");
        case "hashCode" -> 0;
        case "equals" -> proxy == args[0];
        default -> throw new UnsupportedOperationException(method.getName());
    });

    @Test
    void captureSkipsFailingNonCriticalType() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA, StorageFormat.STRUCTURED),
                new FakeType(BRAVO, StorageFormat.STRUCTURED).failingCapture()
        );

        PlayerDataPipeline.CaptureResult.Ready ready = assertInstanceOf(PlayerDataPipeline.CaptureResult.Ready.class, pipeline.capture(this.player));

        assertEquals(Set.of(ALPHA), ready.values().keySet());
        assertEquals(List.of(BRAVO), ready.skipped());
        assertTrue(this.console.warnings > 0);
    }

    @Test
    void captureFailsEntirelyWhenCriticalTypeFails() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA, StorageFormat.STRUCTURED),
                new FakeType(BRAVO, StorageFormat.BINARY, true, Set.of()).failingCapture()
        );

        PlayerDataPipeline.CaptureResult result = pipeline.capture(this.player);

        assertEquals(BRAVO, assertInstanceOf(PlayerDataPipeline.CaptureResult.Failed.class, result).key());
    }

    @Test
    void encodeSkipsFailingNonCriticalType() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA, StorageFormat.STRUCTURED),
                new FakeType(BRAVO, StorageFormat.STRUCTURED).failingEncode()
        );
        PlayerDataPipeline.CaptureResult.Ready captured = assertInstanceOf(PlayerDataPipeline.CaptureResult.Ready.class, pipeline.capture(this.player));

        PlayerDataPipeline.EncodeResult.Ready ready = assertInstanceOf(PlayerDataPipeline.EncodeResult.Ready.class, pipeline.encode(captured));

        assertEquals(Set.of(ALPHA), ready.data().keySet());
        assertEquals(List.of(BRAVO), ready.skipped());
        assertTrue(this.console.warnings > 0);
    }

    @Test
    void encodeFailsEntirelyWhenCriticalTypeFails() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA, StorageFormat.STRUCTURED),
                new FakeType(BRAVO, StorageFormat.BINARY, true, Set.of()).failingEncode()
        );
        PlayerDataPipeline.CaptureResult.Ready captured = assertInstanceOf(PlayerDataPipeline.CaptureResult.Ready.class, pipeline.capture(this.player));

        PlayerDataPipeline.EncodeResult result = pipeline.encode(captured);

        assertEquals(BRAVO, assertInstanceOf(PlayerDataPipeline.EncodeResult.Failed.class, result).key());
    }

    @Test
    void appliesFreshContextInTopologicalOrder() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(CHARLIE, StorageFormat.STRUCTURED, false, Set.of(BRAVO)),
                new FakeType(ALPHA, StorageFormat.STRUCTURED),
                new FakeType(BRAVO, StorageFormat.STRUCTURED, false, Set.of(ALPHA))
        );

        PlayerDataPipeline.ApplyResult.Success result = assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class,
                pipeline.apply(this.player, context(pipeline, snapshotWith(ALPHA, BRAVO, CHARLIE))));

        assertEquals(List.of(ALPHA, BRAVO, CHARLIE), this.applied);
        assertEquals(List.of(ALPHA, BRAVO, CHARLIE), result.applied());
    }

    @Test
    void criticalDecodeFailureFailsWholePrepare() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA, StorageFormat.STRUCTURED),
                new FakeType(BRAVO, StorageFormat.BINARY, true, Set.of()).failingDecode()
        );

        PlayerDataPipeline.PrepareResult result = pipeline.prepare(snapshotWith(ALPHA, BRAVO));

        assertEquals(BRAVO, assertInstanceOf(PlayerDataPipeline.PrepareResult.Failed.class, result).key());
    }

    @Test
    void nonCriticalDecodeFailureCanBeRecoveredByEventData() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new FakeType(ALPHA, StorageFormat.STRUCTURED),
                new FakeType(BRAVO, StorageFormat.STRUCTURED).failingDecode()
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
                new FakeType(ALPHA, StorageFormat.STRUCTURED),
                new FakeType(BRAVO, StorageFormat.STRUCTURED)
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
                new FakeType(ALPHA, StorageFormat.STRUCTURED),
                new FakeType(BRAVO, StorageFormat.BINARY, true, Set.of(ALPHA)).failingApply(),
                new FakeType(CHARLIE, StorageFormat.STRUCTURED, false, Set.of(BRAVO))
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
                new FakeType(ALPHA, StorageFormat.STRUCTURED).failingApply(),
                new FakeType(BRAVO, StorageFormat.STRUCTURED)
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
                new NativeFakeType(ALPHA, StorageFormat.STRUCTURED, false, Set.of()),
                new FakeType(BRAVO, StorageFormat.STRUCTURED)
        );
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, BRAVO));
        CompoundTag local = new CompoundTag();

        Optional<CompoundTag> resultTag = pipeline.applyNative(this.player.getUniqueId(), this.player.getName(), Optional.of(local), context);

        assertSame(local, resultTag.orElseThrow());
        assertEquals(List.of(ALPHA), this.nativeApplied);
        assertEquals(ALPHA.asString(), assertInstanceOf(StringTag.class, local.get(ALPHA.asString())).value());
        assertEquals(Set.of(BRAVO), context.pendingValues().keySet());

        Map<DataKey, Object> eventData = new LinkedHashMap<>(context.pendingValues());
        eventData.put(ALPHA, "must-be-ignored");
        context.acceptEventValues(eventData);
        PlayerDataPipeline.ApplyResult.Success applied = assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));

        assertEquals(List.of(ALPHA, BRAVO), applied.applied());
        assertEquals(List.of(BRAVO), this.applied);
    }

    @Test
    void nativeFalseStaysPendingWithoutFailure() {
        PlayerDataPipeline pipeline = this.createPipeline(new NativeFakeType(ALPHA, StorageFormat.STRUCTURED, false, Set.of()).unsupportedNative());
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA));
        CompoundTag local = new CompoundTag();
        local.putString("local", "kept");

        pipeline.applyNative(this.player.getUniqueId(), this.player.getName(), Optional.of(local), context);

        assertFalse(local.contains(ALPHA.asString()));
        assertEquals(Set.of(ALPHA), context.pendingValues().keySet());
        assertEquals(List.of(), context.failures());
        assertEquals(List.of(ALPHA), assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context)).applied());
    }

    @Test
    void everyNativeFailureFallsBackAndLaterNativeTypesContinue() {
        PlayerDataPipeline pipeline = this.createPipeline(
                new NativeFakeType(ALPHA, StorageFormat.BINARY, true, Set.of()).failingNative(),
                new NativeFakeType(BRAVO, StorageFormat.STRUCTURED, false, Set.of(ALPHA)).failingNative(),
                new NativeFakeType(CHARLIE, StorageFormat.STRUCTURED, false, Set.of(BRAVO))
        );
        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, BRAVO, CHARLIE));

        pipeline.applyNative(this.player.getUniqueId(), this.player.getName(), Optional.of(new CompoundTag()), context);

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
        PlayerDataPipeline joinOnly = this.createPipeline(new FakeType(ALPHA, StorageFormat.STRUCTURED));
        SnapshotApplyContext joinOnlyContext = context(joinOnly, snapshotWith(ALPHA));

        assertTrue(joinOnly.applyNative(this.player.getUniqueId(), this.player.getName(), Optional.empty(), joinOnlyContext).isEmpty());

        PlayerDataPipeline nativePipeline = this.createPipeline(new NativeFakeType(ALPHA, StorageFormat.STRUCTURED, false, Set.of()));
        SnapshotApplyContext nativeContext = context(nativePipeline, snapshotWith(ALPHA));
        CompoundTag root = nativePipeline.applyNative(this.player.getUniqueId(), this.player.getName(), Optional.empty(), nativeContext).orElseThrow();

        assertTrue(root.contains("DataVersion"));
        assertTrue(assertInstanceOf(CompoundTag.class, root.get("bukkit")).contains("firstPlayed"));
    }

    @Test
    void freezeIncludesTypesRegisteredByThirdParties() {
        FakeType thirdParty = new FakeType(BRAVO, StorageFormat.STRUCTURED);
        PlayerDataPipeline pipeline = this.createPipeline(thirdParty, new FakeType(ALPHA, StorageFormat.STRUCTURED));

        pipeline.apply(this.player, context(pipeline, snapshotWith(ALPHA, BRAVO)));

        assertTrue(this.applied.contains(BRAVO));
    }

    @Test
    void unregisteredSnapshotDataIsRetainedForNextSave() {
        PlayerDataPipeline pipeline = this.createPipeline(new FakeType(ALPHA, StorageFormat.STRUCTURED));
        DataKey unknown = DataKey.of("other", "unknown");

        SnapshotApplyContext context = context(pipeline, snapshotWith(ALPHA, unknown));

        assertEquals(Set.of(ALPHA), context.pendingValues().keySet());
        assertEquals(Set.of(unknown), context.passthrough().keySet());
    }

    private PlayerDataPipeline createPipeline(FakeType... types) {
        DataRegistry registry = new DataRegistry();
        for (FakeType type : types) {
            registry.register(type);
        }
        registry.freeze();
        PlayerDataPipeline pipeline = allocateWithoutConstructor(PlayerDataPipeline.class);
        setField(pipeline, "dataRegistry", registry);
        setField(pipeline, "logger", this.logger);
        return pipeline;
    }

    private static SnapshotApplyContext context(PlayerDataPipeline pipeline, Snapshot snapshot) {
        return assertInstanceOf(PlayerDataPipeline.PrepareResult.Ready.class, pipeline.prepare(snapshot)).context();
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

    private static <T> T allocateWithoutConstructor(Class<T> type) {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field field = unsafeType.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
            return type.cast(allocateInstance.invoke(unsafe, type));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
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
        private final StorageFormat storage;
        private final boolean critical;
        private final Set<DataKey> dependencies;
        private boolean captureFails;
        private boolean encodeFails;
        private boolean decodeFails;
        private boolean applyFails;

        private FakeType(DataKey key, StorageFormat storage) {
            this(key, storage, false, Set.of());
        }

        private FakeType(DataKey key, StorageFormat storage, boolean critical, Set<DataKey> dependencies) {
            this.key = key;
            this.storage = storage;
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
        @NotNull
        public StorageFormat storage() {
            return this.storage;
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
        public String capture(@NotNull Player player) {
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
        private boolean nativeSupported = true;
        private boolean nativeFails;

        private NativeFakeType(DataKey key, StorageFormat storage, boolean critical, Set<DataKey> dependencies) {
            super(key, storage, critical, dependencies);
        }

        private NativeFakeType unsupportedNative() {
            this.nativeSupported = false;
            return this;
        }

        private NativeFakeType failingNative() {
            this.nativeFails = true;
            return this;
        }

        @Override
        public boolean applyNative(@NotNull CompoundTag playerData, @NotNull String value) {
            PlayerDataPipelineTest.this.nativeAttempts.add(this.key());
            if (this.nativeFails) throw new IllegalStateException("native failed");
            if (!this.nativeSupported) return false;
            playerData.putString(this.key().asString(), value);
            PlayerDataPipelineTest.this.nativeApplied.add(this.key());
            return true;
        }
    }

    private static final class QuietLogger implements PluginLogger {
        private int warnings;

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            this.warnings++;
        }

        @Override
        public void warn(String message, Throwable throwable) {
            this.warnings++;
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }
    }
}
