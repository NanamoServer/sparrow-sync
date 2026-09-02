package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.event.PreApplyEvent;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplier;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnapshotServicePreparedDataTest {
    private static final DataKey FIRST = DataKey.of("test", "first");
    private static final DataKey SECOND = DataKey.of("test", "second");
    private static final DataKey RECOVERED = DataKey.of("test", "recovered");
    private static final DataKey UNKNOWN = DataKey.of("other", "unknown");
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000018");

    @Test
    void eventChangesKeepRegisteredValuesAndReportRemovedValuesAsSkipped() {
        SnapshotApplier applier = createApplier();
        SnapshotApplier.PreparedSnapshot.Ready before = (SnapshotApplier.PreparedSnapshot.Ready) applier.prepare(snapshot());
        PreApplyEvent event = new PreApplyEvent(player(), snapshot(), before.values());
        event.decoded().remove(FIRST);
        event.decoded().put(SECOND, null);
        event.decoded().put(RECOVERED, "recovered");
        event.decoded().put(UNKNOWN, "unknown");

        SnapshotApplier.PreparedSnapshot.Ready after = applier.afterEvent(event.decoded(), before);

        assertEquals(Map.of(RECOVERED, "recovered"), after.values());
        assertEquals(List.of(FIRST, SECOND), after.skipped());
        assertEquals(Map.of(UNKNOWN, NBT.createString("unknown")), after.passthrough());
    }

    @Test
    void capturedValuesOverrideRetainedValuesWithTheSameKey() {
        Map<DataKey, Tag> passthrough = new LinkedHashMap<>();
        passthrough.put(UNKNOWN, NBT.createString("unknown"));
        passthrough.put(FIRST, NBT.createString("old"));
        Map<DataKey, Tag> captured = Map.of(FIRST, NBT.createString("new"));

        Map<DataKey, Tag> merged = SnapshotService.mergeData(passthrough, captured);

        assertEquals("unknown", merged.get(UNKNOWN).getAsString());
        assertEquals("new", merged.get(FIRST).getAsString());
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> PLAYER_ID;
            case "getName", "toString" -> "PreparedPlayer";
            case "hashCode" -> 18;
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static Snapshot snapshot() {
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        data.put(FIRST, NBT.createString("first"));
        data.put(SECOND, NBT.createString("second"));
        data.put(RECOVERED, NBT.createString("corrupted"));
        data.put(UNKNOWN, NBT.createString("unknown"));
        SnapshotMeta meta = SnapshotMeta.builder()
                .player(PLAYER_ID)
                .timestamp(18L)
                .cause(SaveCause.DISCONNECT)
                .build();
        return new Snapshot(meta, data);
    }

    private static SnapshotApplier createApplier() {
        DataRegistry registry = new DataRegistry();
        registry.register(new FakeType(FIRST, false));
        registry.register(new FakeType(SECOND, false));
        registry.register(new FakeType(RECOVERED, true));
        SnapshotApplier applier = new SnapshotApplier(registry, new SyncLogger(new QuietLogger()));
        registry.freeze();
        return applier;
    }

    private record FakeType(DataKey key, boolean decodeFails) implements PlayerDataType<String> {

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
        public String decode(@NotNull Tag data, int mcDataVersion) throws IOException {
            if (this.decodeFails) throw new IOException("corrupted");
            return data.getAsString();
        }

        @Override
        public void apply(@NotNull Player player, @NotNull String value) {
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
