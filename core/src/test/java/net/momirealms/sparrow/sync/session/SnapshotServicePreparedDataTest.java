package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.event.PreApplyEvent;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplier;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

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
        Map<DataKey, Object> values = new LinkedHashMap<>();
        values.put(FIRST, "first");
        values.put(SECOND, "second");
        SnapshotApplier.PreparedSnapshot.Ready before = new SnapshotApplier.PreparedSnapshot.Ready(values, List.of(RECOVERED));
        PreApplyEvent event = new PreApplyEvent(player(), snapshot(), before.values());
        event.decoded().remove(FIRST);
        event.decoded().put(SECOND, null);
        event.decoded().put(RECOVERED, "recovered");
        event.decoded().put(UNKNOWN, "unknown");

        SnapshotApplier.PreparedSnapshot.Ready after = SnapshotService.preparedAfter(event, before);

        assertEquals(Map.of(RECOVERED, "recovered"), after.values());
        assertEquals(List.of(FIRST, SECOND), after.skipped());
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
        SnapshotMeta meta = SnapshotMeta.builder()
                .player(PLAYER_ID)
                .timestamp(18L)
                .cause(SaveCause.DISCONNECT)
                .build();
        return new Snapshot(meta, Map.of(FIRST, NBT.createString("first")));
    }
}
