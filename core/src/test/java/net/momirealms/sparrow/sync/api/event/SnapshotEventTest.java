package net.momirealms.sparrow.sync.api.event;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotEventTest {
    private static final DataKey INVENTORY = DataKey.sparrow("inventory");
    private static final DataKey HEALTH = DataKey.sparrow("health");
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000017");

    private final Player player = player();
    private final Snapshot snapshot = snapshot();

    @Test
    void snapshotSaveEventCarriesSnapshotAndCancellation() {
        CompletableFuture<SnapshotSaveResult> outcome = new CompletableFuture<>();
        CompletionStage<SnapshotSaveResult> completion = outcome.minimalCompletionStage();
        SnapshotSaveEvent event = new SnapshotSaveEvent("EventPlayer", this.snapshot, completion);

        assertEquals("EventPlayer", event.playerName());
        assertSame(this.snapshot, event.snapshot());
        assertSame(completion, event.completion());
        assertTrue(event.isAsynchronous());
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        assertSame(SnapshotSaveEvent.getHandlerList(), event.getHandlers());
        event.completion().toCompletableFuture().complete(SnapshotSaveResult.CANCELLED);
        assertFalse(outcome.isDone());
        outcome.complete(SnapshotSaveResult.CANCELLED);
        assertTrue(event.completion().toCompletableFuture().join() instanceof SnapshotSaveResult.Cancelled);
    }

    @Test
    void preApplyEventOwnsMutableDecodedData() {
        Map<DataKey, Object> source = new LinkedHashMap<>();
        source.put(INVENTORY, "before");

        PreApplyEvent event = new PreApplyEvent(this.player, this.snapshot, source);
        source.put(HEALTH, 20.0);
        event.decoded().put(INVENTORY, "after");

        assertSame(this.player, event.getPlayer());
        assertSame(this.snapshot, event.snapshot());
        assertNotSame(source, event.decoded());
        assertEquals(Map.of(INVENTORY, "after"), event.decoded());
        assertFalse(Cancellable.class.isAssignableFrom(PreApplyEvent.class));
        assertSame(PreApplyEvent.getHandlerList(), event.getHandlers());
    }

    @Test
    void syncCompleteEventCopiesResultLists() {
        List<DataKey> applied = new ArrayList<>(List.of(INVENTORY));
        List<DataKey> skipped = new ArrayList<>(List.of(HEALTH));

        SyncCompleteEvent event = new SyncCompleteEvent(this.player, this.snapshot, applied, skipped);
        applied.clear();
        skipped.clear();

        assertSame(this.player, event.getPlayer());
        assertSame(this.snapshot, event.snapshot());
        assertEquals(List.of(INVENTORY), event.applied());
        assertEquals(List.of(HEALTH), event.skipped());
        assertThrows(UnsupportedOperationException.class, () -> event.applied().add(HEALTH));
        assertThrows(UnsupportedOperationException.class, () -> event.skipped().add(INVENTORY));
        assertFalse(Cancellable.class.isAssignableFrom(SyncCompleteEvent.class));
        assertSame(SyncCompleteEvent.getHandlerList(), event.getHandlers());
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> PLAYER_ID;
            case "getName", "toString" -> "EventPlayer";
            case "hashCode" -> 17;
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static Snapshot snapshot() {
        SnapshotMeta meta = SnapshotMeta.builder()
                .player(PLAYER_ID)
                .timestamp(17L)
                .cause(SaveCause.DISCONNECT)
                .build();
        return new Snapshot(meta, Map.of(INVENTORY, NBT.createString("inventory")));
    }
}
