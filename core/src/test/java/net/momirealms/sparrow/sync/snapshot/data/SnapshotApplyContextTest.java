package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnapshotApplyContextTest {
    private static final DataKey FIRST = DataKey.of("test", "first");
    private static final DataKey SECOND = DataKey.of("test", "second");
    private static final DataKey UNKNOWN = DataKey.of("other", "unknown");

    @Test
    void eventCanOnlyReplacePendingOrRecoverDecodeSkippedSlots() {
        DataRegistry registry = registry();
        SnapshotApplyContext context = new SnapshotApplyContext(registry, Map.of(UNKNOWN, NBT.createString("unknown")));
        context.decoded(registry.slot(FIRST), "first");
        context.decodeSkipped(registry.slot(SECOND), new IllegalStateException("corrupted"));
        context.appliedNative(registry.slot(FIRST));
        Map<DataKey, Object> eventData = new LinkedHashMap<>();
        eventData.put(FIRST, "must-be-ignored");
        eventData.put(SECOND, "recovered");
        eventData.put(UNKNOWN, "must-be-ignored");

        context.acceptEventValues(eventData);

        assertEquals(Map.of(SECOND, "recovered"), context.pendingValues());
        assertEquals(Map.of(UNKNOWN, NBT.createString("unknown")), context.passthrough());
        assertEquals(1, context.failures().size());
        assertEquals(SnapshotApplyContext.FailureStage.DECODE, context.failures().getFirst().stage());
    }

    private static DataRegistry registry() {
        DataRegistry registry = new DataRegistry();
        registry.register(new FakeType(FIRST));
        registry.register(new FakeType(SECOND));
        registry.freeze();
        return registry;
    }

    private record FakeType(@NotNull DataKey key) implements PlayerDataType<String> {

        @Override
        @NotNull
        public StorageFormat storage() {
            return StorageFormat.STRUCTURED;
        }

        @Override
        @NotNull
        public String capture(@NotNull Player player) {
            return this.key.asString();
        }

        @Override
        @NotNull
        public Tag encode(@NotNull String value) {
            return NBT.createString(value);
        }

        @Override
        @NotNull
        public String decode(@NotNull Tag data, int mcDataVersion) {
            return data.getAsString();
        }

        @Override
        public void apply(@NotNull Player player, @NotNull String value) {
        }
    }
}
