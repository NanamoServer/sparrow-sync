package net.momirealms.sparrow.sync.data;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.data.SnapshotApplier.ApplyResult;
import net.momirealms.sparrow.sync.data.SnapshotApplier.PreparedSnapshot;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistration;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotApplierTest {
    private static final DataKey ALPHA = DataKey.of("test", "alpha");
    private static final DataKey BRAVO = DataKey.of("test", "bravo");
    private static final DataKey CHARLIE = DataKey.of("test", "charlie");

    private final QuietLogger logger = new QuietLogger();
    private final List<DataKey> applied = new ArrayList<>();
    // 只响应 getName 的代理玩家, 其余任何调用直接失败, 顺带证明编排器不解引用玩家状态
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
        case "getName", "toString" -> "TestPlayer";
        case "hashCode" -> 0;
        case "equals" -> proxy == args[0];
        default -> throw new UnsupportedOperationException(method.getName());
    });

    @Test
    void captureSkipsFailingNonCriticalType() {
        // 非关键类型采集失败只跳过自己, 其余数据照常进快照
        FakeType alpha = new FakeType(DataRegistration.of(ALPHA, StorageFormat.STRUCTURED));
        FakeType bravo = new FakeType(DataRegistration.of(BRAVO, StorageFormat.STRUCTURED)).failingCapture();
        SnapshotApplier applier = createApplier(alpha, bravo);

        SnapshotApplier.CaptureResult result = applier.capture(this.player);

        SnapshotApplier.CaptureResult.Ready ready = assertInstanceOf(SnapshotApplier.CaptureResult.Ready.class, result);
        assertEquals(Set.of(ALPHA), ready.data().keySet());
        assertEquals(List.of(BRAVO), ready.skipped());
        assertTrue(this.logger.warnings > 0);
    }

    @Test
    void captureFailsEntirelyWhenCriticalTypeFails() {
        // 关键类型缺失的快照还原不了玩家, 这次不产出快照
        FakeType alpha = new FakeType(DataRegistration.of(ALPHA, StorageFormat.STRUCTURED));
        FakeType critical = new FakeType(DataRegistration.of(BRAVO, StorageFormat.BINARY, true, Set.of())).failingCapture();
        SnapshotApplier applier = createApplier(alpha, critical);

        SnapshotApplier.CaptureResult result = applier.capture(this.player);

        assertEquals(BRAVO, assertInstanceOf(SnapshotApplier.CaptureResult.Failed.class, result).key());
    }

    @Test
    void appliesInTopologicalOrder() {
        // bravo 依赖 alpha, charlie 依赖 bravo, 注册顺序故意打乱
        FakeType charlie = new FakeType(DataRegistration.of(CHARLIE, StorageFormat.STRUCTURED, false, Set.of(BRAVO)));
        FakeType alpha = new FakeType(DataRegistration.of(ALPHA, StorageFormat.STRUCTURED));
        FakeType bravo = new FakeType(DataRegistration.of(BRAVO, StorageFormat.STRUCTURED, false, Set.of(ALPHA)));
        SnapshotApplier applier = createApplier(charlie, alpha, bravo);

        PreparedSnapshot prepared = applier.prepare(snapshotWith(ALPHA, BRAVO, CHARLIE));
        ApplyResult result = applier.apply(this.player, assertInstanceOf(PreparedSnapshot.Ready.class, prepared));

        assertInstanceOf(ApplyResult.Success.class, result);
        assertEquals(List.of(ALPHA, BRAVO, CHARLIE), this.applied);
    }

    @Test
    void criticalDecodeFailureFailsWholePrepare() {
        FakeType alpha = new FakeType(DataRegistration.of(ALPHA, StorageFormat.STRUCTURED));
        FakeType critical = new FakeType(DataRegistration.of(BRAVO, StorageFormat.BINARY, true, Set.of())) {
            @Override
            @NotNull
            public String decode(@NotNull Tag data, int mcDataVersion) throws IOException {
                throw new IOException("corrupted");
            }
        };
        SnapshotApplier applier = createApplier(alpha, critical);

        PreparedSnapshot prepared = applier.prepare(snapshotWith(ALPHA, BRAVO));

        assertEquals(BRAVO, assertInstanceOf(PreparedSnapshot.Failed.class, prepared).key());
    }

    @Test
    void nonCriticalDecodeFailureIsSkipped() {
        FakeType alpha = new FakeType(DataRegistration.of(ALPHA, StorageFormat.STRUCTURED));
        FakeType flaky = new FakeType(DataRegistration.of(BRAVO, StorageFormat.STRUCTURED)) {
            @Override
            @NotNull
            public String decode(@NotNull Tag data, int mcDataVersion) throws IOException {
                throw new IOException("corrupted");
            }
        };
        SnapshotApplier applier = createApplier(alpha, flaky);

        PreparedSnapshot.Ready prepared = assertInstanceOf(PreparedSnapshot.Ready.class, applier.prepare(snapshotWith(ALPHA, BRAVO)));
        ApplyResult.Success result = assertInstanceOf(ApplyResult.Success.class, applier.apply(this.player, prepared));

        assertEquals(List.of(ALPHA), result.applied());
        assertEquals(List.of(BRAVO), result.skipped());
        assertEquals(1, this.logger.warnings);
    }

    @Test
    void criticalApplyFailureAbortsRemainingTypes() {
        FakeType alpha = new FakeType(DataRegistration.of(ALPHA, StorageFormat.STRUCTURED));
        FakeType exploding = new FakeType(DataRegistration.of(BRAVO, StorageFormat.BINARY, true, Set.of(ALPHA))) {
            @Override
            public void apply(@NotNull Player player, @NotNull String value) {
                throw new IllegalStateException("apply failed");
            }
        };
        FakeType charlie = new FakeType(DataRegistration.of(CHARLIE, StorageFormat.STRUCTURED, false, Set.of(BRAVO)));
        SnapshotApplier applier = createApplier(alpha, exploding, charlie);

        PreparedSnapshot.Ready prepared = assertInstanceOf(PreparedSnapshot.Ready.class, applier.prepare(snapshotWith(ALPHA, BRAVO, CHARLIE)));
        ApplyResult result = applier.apply(this.player, prepared);

        ApplyResult.Failure failure = assertInstanceOf(ApplyResult.Failure.class, result);
        assertEquals(BRAVO, failure.failedKey());
        assertEquals(List.of(ALPHA), failure.appliedBefore());
        assertEquals(List.of(ALPHA), this.applied);
    }

    @Test
    void nonCriticalApplyFailureIsSkipped() {
        FakeType alpha = new FakeType(DataRegistration.of(ALPHA, StorageFormat.STRUCTURED)) {
            @Override
            public void apply(@NotNull Player player, @NotNull String value) {
                throw new IllegalStateException("apply failed");
            }
        };
        FakeType bravo = new FakeType(DataRegistration.of(BRAVO, StorageFormat.STRUCTURED));
        SnapshotApplier applier = createApplier(alpha, bravo);

        ApplyResult.Success result = assertInstanceOf(ApplyResult.Success.class,
                applier.apply(this.player, assertInstanceOf(PreparedSnapshot.Ready.class, applier.prepare(snapshotWith(ALPHA, BRAVO)))));

        assertEquals(List.of(BRAVO), result.applied());
        assertEquals(List.of(ALPHA), result.skipped());
    }

    @Test
    void typesPreRegisteredInRegistryAreHarvested() {
        // 模拟第三方在 onLoad 期注册的类型: 不在 builtin 集合里, 经注册表汇入装配
        DataRegistry registry = new DataRegistry();
        FakeType thirdParty = new FakeType(DataRegistration.of(BRAVO, StorageFormat.STRUCTURED));
        registry.register(thirdParty);
        FakeType builtin = new FakeType(DataRegistration.of(ALPHA, StorageFormat.STRUCTURED));
        registry.register(builtin);
        SnapshotApplier applier = new SnapshotApplier(registry, this.logger);

        PreparedSnapshot.Ready prepared = assertInstanceOf(PreparedSnapshot.Ready.class, applier.prepare(snapshotWith(ALPHA, BRAVO)));
        applier.apply(this.player, prepared);

        assertTrue(this.applied.contains(BRAVO));
        assertTrue(registry.frozen());
    }

    @Test
    void unregisteredSnapshotDataIsIgnored() {
        FakeType alpha = new FakeType(DataRegistration.of(ALPHA, StorageFormat.STRUCTURED));
        SnapshotApplier applier = createApplier(alpha);

        PreparedSnapshot.Ready prepared = assertInstanceOf(PreparedSnapshot.Ready.class,
                applier.prepare(snapshotWith(ALPHA, DataKey.of("other", "unknown"))));

        assertEquals(1, prepared.values().size());
        assertTrue(prepared.values().containsKey(ALPHA));
    }

    private SnapshotApplier createApplier(FakeType... types) {
        DataRegistry registry = new DataRegistry();
        for (FakeType type : types) {
            registry.register(type);
        }
        return new SnapshotApplier(registry, this.logger);
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

    // 不经线程断言的假类型, 声明委托纯声明 record, apply 记录调用顺序; 编排器测试不依赖 Bukkit 运行时
    private class FakeType implements PlayerDataType<String> {
        private final DataRegistration declaration;
        private boolean captureFails;

        private FakeType(DataRegistration declaration) {
            this.declaration = declaration;
        }

        private FakeType failingCapture() {
            this.captureFails = true;
            return this;
        }

        @Override
        @NotNull
        public DataKey key() {
            return this.declaration.key();
        }

        @Override
        @NotNull
        public StorageFormat storage() {
            return this.declaration.storage();
        }

        @Override
        public boolean critical() {
            return this.declaration.critical();
        }

        @Override
        @NotNull
        public Set<DataKey> dependencies() {
            return this.declaration.dependencies();
        }

        @Override
        @NotNull
        public Tag capture(@NotNull Player player) {
            if (this.captureFails) {
                throw new IllegalStateException("capture of " + this.declaration.key() + " failed");
            }
            return NBT.createString(this.declaration.key().asString());
        }

        @Override
        @NotNull
        public String decode(@NotNull Tag data, int mcDataVersion) throws IOException {
            return data.getAsString();
        }

        @Override
        public void apply(@NotNull Player player, @NotNull String value) {
            applied.add(this.declaration.key());
        }
    }

    private static final class QuietLogger implements PluginLogger {
        int warnings;

        @Override
        public void info(String s) {
        }

        @Override
        public void warn(String s) {
            this.warnings++;
        }

        @Override
        public void warn(String s, Throwable t) {
            this.warnings++;
        }

        @Override
        public void error(String s) {
        }

        @Override
        public void error(String s, Throwable t) {
        }
    }
}
