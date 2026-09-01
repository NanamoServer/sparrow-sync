package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.event.SnapshotSaveEvent;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.session.SnapshotService.SnapshotSaveOutcome;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SnapshotServiceReentrantSaveTest {

    @Test
    void captureAndSaveRejectsRequestWhileSaveEventIsBeingDispatched() throws ReflectiveOperationException {
        UUID playerId = UUID.randomUUID();
        Player player = player(playerId);
        RecordingLogger console = new RecordingLogger();
        SnapshotService service = new SnapshotService(null, new DataRegistry(), null, null, new SyncLogger(console));
        Snapshot snapshot = new Snapshot(
                SnapshotMeta.builder().player(playerId).cause(SaveCause.INTERVAL).build(),
                Map.of()
        );
        SnapshotSaveEvent event = new SnapshotSaveEvent(player, snapshot, new CompletableFuture<SnapshotSaveOutcome>().minimalCompletionStage());
        ThreadLocal<SnapshotSaveEvent> dispatchContext = dispatchContext(service);

        dispatchContext.set(event);
        try {
            SnapshotSaveOutcome outcome = service.captureAndSave(player, SaveCause.COMMAND).join();

            assertInstanceOf(SnapshotSaveOutcome.ReentrantRejected.class, outcome);
            assertEquals(1, console.warnings);
        } finally {
            dispatchContext.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<SnapshotSaveEvent> dispatchContext(SnapshotService service) throws ReflectiveOperationException {
        Field field = SnapshotService.class.getDeclaredField("dispatchingSaveEvent");
        field.setAccessible(true);
        return (ThreadLocal<SnapshotSaveEvent>) field.get(service);
    }

    private static Player player(UUID playerId) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "getName", "toString" -> "ReentrantPlayer";
            case "hashCode" -> playerId.hashCode();
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static final class RecordingLogger implements PluginLogger {
        private int warnings;

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
