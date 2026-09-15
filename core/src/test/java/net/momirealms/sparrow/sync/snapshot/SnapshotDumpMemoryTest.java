package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.test.NoopSnapshotCache;
import net.momirealms.sparrow.sync.test.SnapshotFileTestLogger;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.map.MapStorage;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotDumpMemoryTest {
    @TempDir Path directory;

    @Test
    void streamsOneHundredTwentyEightMiBWithoutRetainingTheDataset() throws Exception {
        int total = 512;
        int payloadSize = 256 * 1024;
        AtomicInteger imported = new AtomicInteger();
        MapStorage maps = proxy(MapStorage.class, (instance, method, args) -> switch (method.getName()) {
            case "scan" -> CompletableFuture.completedFuture(List.of());
            case "sequence" -> CompletableFuture.completedFuture(0L);
            case "importSequence" -> CompletableFuture.completedFuture(null);
            default -> throw new AssertionError(method.getName());
        });
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> switch (method.getName()) {
            case "maps" -> maps;
            case "scanUsers" -> CompletableFuture.completedFuture(List.of());
            case "scanSnapshots" -> {
                long after = args[1] == null ? 0 : ((UUID) args[1]).getLeastSignificantBits();
                int limit = (int) args[2];
                assertTrue(limit <= 16);
                List<Snapshot> batch = new ArrayList<>();
                for (long i = after + 1; i <= Math.min(after + limit, total); i++) {
                    byte[] payload = new byte[payloadSize];
                    new Random(i).nextBytes(payload);
                    batch.add(new Snapshot(new SnapshotMeta(new UUID(0, i), new UUID(1, 1), i, SaveCause.COMMAND, false, "source", 4189),
                            Map.of(DataKey.of("unknown", "payload"), NBT.createByteArray(payload))));
                }
                yield CompletableFuture.completedFuture(batch);
            }
            case "importSnapshot" -> {
                imported.incrementAndGet();
                yield CompletableFuture.completedFuture(new StorageProvider.SaveOutcome(StorageProvider.SaveResult.SAVED, null));
            }
            default -> throw new AssertionError(method.getName());
        });
        BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.NONE);
        SnapshotDump dump = new SnapshotDump(storage, new SnapshotFiles(this.directory, codec, new SnapshotFileTestLogger()), codec, map -> CompletableFuture.completedFuture(null), new NoopSnapshotCache());
        SnapshotDump.Result exported = dump.dump("large.zip", 1000);
        assertNull(exported.failure(), () -> String.valueOf(exported.failure()));
        assertEquals(total, exported.snapshots());
        assertTrue(Files.size(exported.file()) > 120L * 1024 * 1024);
        SnapshotDump.Result restored = dump.importFile("large.zip");
        assertNull(restored.failure(), () -> String.valueOf(restored.failure()));
        assertEquals(total, imported.get());
        assertEquals(total, restored.snapshots());
        System.out.println("Streaming dataset bytes=" + (long) total * payloadSize + ", max heap bytes=" + Runtime.getRuntime().maxMemory());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
