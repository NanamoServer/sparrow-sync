package net.momirealms.sparrow.sync.storage;

import com.mongodb.client.MongoClients;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationSource;
import net.momirealms.sparrow.sync.compatibility.migration.SnapshotMigration;
import net.momirealms.sparrow.sync.map.data.MapArchiveRecord;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.map.cache.MapCache;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotDump;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.storage.mongo.MongoStorageProvider;
import net.momirealms.sparrow.sync.storage.mysql.MysqlStorageProvider;
import net.momirealms.sparrow.sync.storage.postgresql.PostgresStorageProvider;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StorageDumpIntegrationTest {
    @TempDir Path directory;
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE);
    private final SyncLogger logger = new SyncLogger(new QuietLogger());
    private final PlayerSerialExecutor serial = new PlayerSerialExecutor(this.logger, 2);
    private final List<StorageProvider> providers = new ArrayList<>();
    private final List<Runnable> cleanup = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(strings = {"mongo", "mysql", "postgres"})
    void migrationZipReplaysWithoutSourceAndPreservesUnrelatedRecords(String kind) throws Exception {
        requireEnvironment(kind);
        StorageProvider target = this.open(kind);
        Snapshot unrelated = SnapshotFixtures.snapshot();
        assertTrue(target.importSnapshot(unrelated).join().result().stored());
        SnapshotFiles files = new SnapshotFiles(this.directory, this.codec);
        SnapshotDump importer = new SnapshotDump(target, files, this.codec, record -> CompletableFuture.completedFuture(null));
        UUID player = UUID.randomUUID();
        MigrationSource source = new MigrationSource() {
            @Override
            public String id() { return "fixture"; }
            @Override
            public void read(Sink sink) throws Exception {
                sink.accept(new PlayerData(player, new StoredUser(player, "Migrated", 123), 456L, 4189, SnapshotFixtures.snapshot().allData()));
            }
        };
        SnapshotMigration.Result migrated = new SnapshotMigration(files, this.codec, importer, "source-server").migrate("migration.zip", source, 999);
        assertNull(migrated.failure());
        assertNull(migrated.imported().failure());
        Snapshot first = target.latestSnapshot(player).join().orElseThrow();
        assertEquals(SaveCause.MIGRATION, first.meta().cause());
        assertEquals(456, first.meta().timestamp());
        assertEquals(SnapshotFixtures.snapshot().allData(), first.allData());
        List<Snapshot> once = target.scanSnapshots(Long.MAX_VALUE, null, 100).join();
        assertEquals(2, once.size());
        assertNull(importer.importFile("migration.zip").failure());
        assertNull(importer.importFile("migration.zip").failure());
        assertEquals(once, target.scanSnapshots(Long.MAX_VALUE, null, 100).join());
        assertEquals(first, target.latestSnapshot(player).join().orElseThrow());
        assertTrue(once.contains(unrelated));
    }

    @ParameterizedTest
    @CsvSource({"mongo,mongo", "mysql,mysql", "postgres,postgres", "mongo,mysql", "mysql,mongo", "mongo,postgres", "postgres,mongo", "mysql,postgres", "postgres,mysql"})
    void roundTripAcrossBackends(String sourceKind, String targetKind) throws Exception {
        requireEnvironment(sourceKind);
        requireEnvironment(targetKind);
        StorageProvider source = this.open(sourceKind);
        StorageProvider target = this.open(targetKind);
        UUID player = UUID.randomUUID();
        Map<UUID, Snapshot> expected = new HashMap<>();
        for (int i = 0; i < 12; i++) {
            Snapshot snapshot = new Snapshot(new SnapshotMeta(UUID.randomUUID(), player, 100 + i, SaveCause.COMMAND, i % 2 == 0, "origin", 4189), SnapshotFixtures.snapshot().allData());
            assertTrue(source.importSnapshot(snapshot).join().result().stored());
            if (i < 10) expected.put(snapshot.meta().id(), snapshot);
        }
        Snapshot first = expected.values().iterator().next();
        Snapshot changed = new Snapshot(new SnapshotMeta(first.meta().id(), UUID.randomUUID(), 9999, SaveCause.API, !first.meta().pinned(), "other", 4189), Map.of());
        assertTrue(target.importSnapshot(changed).join().result().stored());
        List<StoredUser> users = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            StoredUser user = new StoredUser(UUID.randomUUID(), "Alice", 1000 + i);
            users.add(user);
            source.importUser(user).join();
        }
        MapData data = mapData(7);
        MapArchiveRecord map = new MapArchiveRecord(new MapIdentity(new MapSource("origin", 3), -8), 4189, 54321, data.encode());
        source.maps().importMap(map).join();
        source.maps().importSequence(1500).join();
        target.maps().importMap(new MapArchiveRecord(map.identity(), 4189, 99999, mapData(1).encode())).join();
        SnapshotFiles files = new SnapshotFiles(this.directory, this.codec);
        SnapshotDump exporter = new SnapshotDump(source, files, this.codec, record -> CompletableFuture.completedFuture(null));
        SnapshotDump.Result exported = exporter.dump("transfer.zip", 110);
        assertNull(exported.failure(), () -> String.valueOf(exported.failure()));
        assertEquals(10, exported.snapshots());
        SnapshotDump importer = new SnapshotDump(target, files, this.codec, record -> CompletableFuture.completedFuture(null));
        SnapshotDump.Result imported = importer.importFile("transfer.zip");
        assertNull(imported.failure(), () -> String.valueOf(imported.failure()));
        assertEquals(10, imported.snapshots());
        assertEquals(9, imported.users());
        assertEquals(1, imported.maps());
        Map<UUID, Snapshot> actual = new HashMap<>();
        UUID after = null;
        while (true) {
            List<Snapshot> batch = target.scanSnapshots(Long.MAX_VALUE, after, 3).join();
            if (batch.isEmpty()) break;
            for (Snapshot snapshot : batch) actual.put(snapshot.meta().id(), snapshot);
            after = batch.getLast().meta().id();
        }
        assertEquals(expected, actual);
        assertEquals(first, target.snapshot(first.meta().id()).join().orElseThrow());
        assertEquals(users.getLast().player(), target.lookupUser("Alice").join().orElseThrow());
        assertEquals(users.size(), target.scanUsers(null, 100).join().size());
        assertTrue(target.scanUsers(null, 100).join().containsAll(users));
        MapArchiveRecord copied = target.maps().scan(0, 10).join().getFirst();
        assertEquals(map.identity(), copied.identity());
        assertEquals(map.updatedAt(), copied.updatedAt());
        assertArrayEquals(map.data(), copied.data());
        assertEquals(data, target.maps().find(-8).join().orElseThrow().data());
        assertEquals(1500L, target.maps().sequence().join());
        assertNull(importer.importFile("transfer.zip").failure());
        assertEquals(-1501, target.maps().register(new MapSource("new-origin", 1), data).join().identity().globalId());
    }

    @ParameterizedTest
    @CsvSource({"mongo", "mysql", "postgres"})
    void singleImportOverwritesAllFieldsWithoutChangingNormalSaveSemantics(String kind) throws Exception {
        requireEnvironment(kind);
        StorageProvider storage = this.open(kind);
        Snapshot original = SnapshotFixtures.snapshot();
        assertTrue(storage.saveSnapshot(original).join().stored());
        Snapshot replacement = new Snapshot(new SnapshotMeta(original.meta().id(), UUID.randomUUID(), 1, SaveCause.API, true, "replacement", 4189), Map.of());
        assertEquals(StorageProvider.SaveResult.DUPLICATE, storage.saveSnapshot(replacement).join());
        assertEquals(original, storage.snapshot(original.meta().id()).join().orElseThrow());
        assertTrue(storage.importSnapshot(replacement).join().result().stored());
        assertEquals(replacement, storage.snapshot(original.meta().id()).join().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mongo", "mysql", "postgres"})
    void mapImportOnlyOverwritesTheSameGlobalId(String kind) throws Exception {
        requireEnvironment(kind);
        var maps = this.open(kind).maps();
        MapSource firstSource = new MapSource("original", 1);
        MapArchiveRecord original = new MapArchiveRecord(new MapIdentity(firstSource, -1), 4189, 100, mapData(1).encode());
        maps.importMap(original).join();
        MapArchiveRecord changed = new MapArchiveRecord(new MapIdentity(new MapSource("replacement", 2), -1), 4190, 200, mapData(2).encode());
        maps.importMap(changed).join();
        maps.importMap(changed).join();
        assertEquals(1, maps.scan(0, 10).join().size());
        assertMapRecord(changed, maps.scan(0, 10).join().getFirst());
        assertEquals(1L, maps.sequence().join());
        assertEquals(-2, maps.register(firstSource, mapData(3)).join().identity().globalId());

        MapArchiveRecord conflicting = new MapArchiveRecord(new MapIdentity(changed.identity().source(), -3), 4191, 300, mapData(4).encode());
        assertThrows(CompletionException.class, () -> maps.importMap(conflicting).join());
        assertTrue(maps.find(-3).join().isEmpty());
        assertMapRecord(changed, maps.scan(0, 10).join().getFirst());
        MapArchiveRecord occupied = maps.scan(0, 10).join().getLast();
        MapArchiveRecord collisionOnBothKeys = new MapArchiveRecord(new MapIdentity(firstSource, -1), 4192, 400, mapData(5).encode());
        assertThrows(CompletionException.class, () -> maps.importMap(collisionOnBothKeys).join());
        assertMapRecord(changed, maps.scan(0, 10).join().getFirst());
        assertMapRecord(occupied, maps.scan(0, 10).join().getLast());
        // SQL 的地图写入和序列推进处于同一个事务.
        if (!kind.equals("mongo")) assertEquals(2L, maps.sequence().join());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mongo", "mysql", "postgres"})
    void conflictingMapImportDoesNotCountOrPublishTheRejectedIdentity(String kind) throws Exception {
        requireEnvironment(kind);
        StorageProvider source = this.open(kind);
        StorageProvider target = this.open(kind);
        MapArchiveRecord accepted = new MapArchiveRecord(new MapIdentity(new MapSource("free", 1), -1), 4189, 200, mapData(2).encode());
        MapSource occupiedSource = new MapSource("occupied", 2);
        MapArchiveRecord conflict = new MapArchiveRecord(new MapIdentity(occupiedSource, -3), 4189, 300, mapData(3).encode());
        source.maps().importMap(accepted).join();
        source.maps().importMap(conflict).join();
        target.maps().importMap(new MapArchiveRecord(new MapIdentity(new MapSource("previous", 1), -1), 4189, 100, mapData(1).encode())).join();
        MapArchiveRecord occupied = new MapArchiveRecord(new MapIdentity(occupiedSource, -2), 4189, 150, mapData(4).encode());
        target.maps().importMap(occupied).join();

        Map<Integer, StoredMap> cache = new HashMap<>();
        StoredMap existingCache = target.maps().find(-2).join().orElseThrow();
        cache.put(-2, existingCache);
        List<Integer> published = new ArrayList<>();
        MapCache shared = (MapCache) Proxy.newProxyInstance(MapCache.class.getClassLoader(), new Class<?>[]{MapCache.class}, (instance, method, args) -> {
            assertEquals("publish", method.getName());
            StoredMap map = (StoredMap) args[0];
            assertEquals(map, target.maps().find(map.identity().globalId()).join().orElseThrow());
            cache.put(map.identity().globalId(), map);
            published.add(map.identity().globalId());
            return CompletableFuture.completedFuture(null);
        });
        MapSyncService sync = NmsPlayerFixture.allocate(MapSyncService.class);
        NmsPlayerFixture.set(MapSyncService.class, sync, "shared", shared);
        SnapshotFiles files = new SnapshotFiles(this.directory, this.codec);
        SnapshotDump exporter = new SnapshotDump(source, files, this.codec, record -> CompletableFuture.completedFuture(null));
        assertNull(exporter.dump("maps.zip", Long.MAX_VALUE).failure());
        SnapshotDump.Result result = new SnapshotDump(target, files, this.codec, sync::importedMap).importFile("maps.zip");
        assertNotNull(result.failure());
        assertEquals("map -3", result.current());
        assertEquals(1, result.maps());
        assertEquals(List.of(-1), published);
        assertEquals(2, cache.size());
        assertSame(existingCache, cache.get(-2));
        assertFalse(cache.containsKey(-3));
        assertTrue(target.maps().find(-3).join().isEmpty());
        assertMapRecord(accepted, target.maps().scan(0, 10).join().getFirst());
        assertMapRecord(occupied, target.maps().scan(0, 10).join().getLast());
    }

    private static void assertMapRecord(MapArchiveRecord expected, MapArchiveRecord actual) {
        assertEquals(expected.identity(), actual.identity());
        assertEquals(expected.dataVersion(), actual.dataVersion());
        assertEquals(expected.updatedAt(), actual.updatedAt());
        assertArrayEquals(expected.data(), actual.data());
    }

    private StorageProvider open(String kind) throws Exception {
        String name = "sparrow_dump_" + UUID.randomUUID().toString().replace("-", "");
        StorageProvider storage;
        if (kind.equals("mongo")) {
            storage = new MongoStorageProvider(new PluginConfig.MongoOptions("mongodb://localhost:27017", name, "", "", "admin", "it_"), new DocumentSnapshotCodec(this.codec), this.serial, Runnable::run, this.logger);
            this.cleanup.add(() -> {
                try (var client = MongoClients.create("mongodb://localhost:27017")) {
                    client.getDatabase(name).drop();
                }
            });
        } else {
            boolean mysql = kind.equals("mysql");
            String env = mysql ? "SPARROW_TEST_MYSQL_" : "SPARROW_TEST_POSTGRESQL_";
            String url = System.getenv(env + "URL");
            String username = System.getenv().getOrDefault(env + "USERNAME", mysql ? "root" : "postgres");
            String password = System.getenv().getOrDefault(env + "PASSWORD", "");
            Jdbi admin = Jdbi.create(url, username, password);
            if (mysql) {
                admin.useHandle(handle -> handle.execute("CREATE DATABASE `" + name + "`"));
                this.cleanup.add(() -> admin.useHandle(handle -> handle.execute("DROP DATABASE `" + name + "`")));
                int question = url.indexOf('?');
                String address = question < 0 ? url : url.substring(0, question);
                url = address.substring(0, address.lastIndexOf('/') + 1) + name + (question < 0 ? "" : url.substring(question));
                PluginConfig.MysqlOptions options = new PluginConfig.MysqlOptions();
                configure(options, url, username, password);
                storage = new MysqlStorageProvider(options, this.codec, this.serial, Runnable::run, this.logger);
            } else {
                admin.useHandle(handle -> handle.execute("CREATE SCHEMA " + name));
                this.cleanup.add(() -> admin.useHandle(handle -> handle.execute("DROP SCHEMA " + name + " CASCADE")));
                url += (url.contains("?") ? "&" : "?") + "currentSchema=" + name;
                PluginConfig.PostgresOptions options = new PluginConfig.PostgresOptions();
                configure(options, url, username, password);
                storage = new PostgresStorageProvider(options, this.codec, this.serial, Runnable::run, this.logger);
            }
        }
        this.providers.add(storage);
        storage.initialize();
        return storage;
    }

    private static void requireEnvironment(String kind) {
        if (kind.equals("mongo")) return;
        String name = kind.equals("mysql") ? "SPARROW_TEST_MYSQL_URL" : "SPARROW_TEST_POSTGRESQL_URL";
        Assumptions.assumeTrue(System.getenv(name) != null, name + " is not configured");
    }

    private static void configure(Object options, String url, String username, String password) throws Exception {
        for (var entry : Map.of("url", url, "username", username, "password", password, "tablePrefix", "it_").entrySet()) {
            Field field = options.getClass().getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(options, entry.getValue());
        }
    }

    private static MapData mapData(int color) {
        var tag = NBT.createCompound();
        tag.putString("dimension", "minecraft:overworld");
        byte[] colors = new byte[MapData.PIXEL_COUNT];
        colors[0] = (byte) color;
        tag.putByteArray("colors", colors);
        return new MapData(4189, tag);
    }

    @AfterEach
    void cleanup() {
        this.serial.shutdown(5, TimeUnit.SECONDS);
        for (StorageProvider provider : this.providers) provider.shutdown();
        for (Runnable action : this.cleanup) action.run();
    }

    private static final class QuietLogger implements PluginLogger {
        public void info(String message) {}
        public void warn(String message) {}
        public void warn(String message, Throwable failure) {}
        public void error(String message) {}
        public void error(String message, Throwable failure) {}
    }
}
