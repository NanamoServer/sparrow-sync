package net.momirealms.sparrow.sync.storage.postgresql;

import com.zaxxer.hikari.HikariDataSource;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.session.SnapshotStash;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.storage.postgresql.upgrade.PostgresSchemaMigration;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SPARROW_TEST_POSTGRESQL_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresStorageProviderTest {
    private final SyncLogger logger = new SyncLogger(new QuietLogger());
    private final BinarySnapshotCodec binary = new BinarySnapshotCodec(CompressorRegistry.DEFLATE);
    private final List<PostgresStorageProvider> providers = new ArrayList<>();
    private Jdbi admin;
    private String schema;
    private String url;
    private String username;
    private String password;
    private String prefix;
    private PlayerSerialExecutor serial;
    @TempDir Path stashDirectory;

    @BeforeAll
    void connect() {
        String configured = System.getenv("SPARROW_TEST_POSTGRESQL_URL");
        this.username = System.getenv().getOrDefault("SPARROW_TEST_POSTGRESQL_USERNAME", "postgres");
        this.password = System.getenv().getOrDefault("SPARROW_TEST_POSTGRESQL_PASSWORD", "");
        this.admin = Jdbi.create(configured, this.username, this.password);
        this.schema = "sparrow_pg_it_" + UUID.randomUUID().toString().replace("-", "");
        this.admin.useHandle(handle -> handle.execute("CREATE SCHEMA " + this.schema));
        this.url = configured + (configured.contains("?") ? "&" : "?") + "currentSchema=" + this.schema;
        System.out.println("PostgreSQL integration server: " + this.admin.withHandle(handle -> handle.createQuery("SELECT version()").mapTo(String.class).one()));
    }

    @BeforeEach
    void prepare() {
        this.prefix = "it_" + UUID.randomUUID().toString().replace("-", "") + "_";
        this.serial = new PlayerSerialExecutor(this.logger, 4);
    }

    @AfterEach
    void closeProviders() {
        this.serial.shutdown(10, TimeUnit.SECONDS);
        for (int i = 0; i < this.providers.size(); i++) {
            this.providers.get(i).shutdown();
        }
        this.providers.clear();
    }

    @AfterAll
    void dropSchema() {
        if (this.admin != null && this.schema != null) {
            this.admin.useHandle(handle -> handle.execute("DROP SCHEMA " + this.schema + " CASCADE"));
        }
    }

    @Test
    void initializesNativeColumnsAndRoundTripsUnknownData() throws Exception {
        PostgresStorageProvider storage = this.open();
        int tables = storage.jdbi().withHandle(handle -> handle.createQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name LIKE :pattern").bind("pattern", this.prefix + "%").mapTo(Integer.class).one());
        assertEquals(4, tables);
        assertEquals(List.of("uuid", "uuid", "int8", "varchar", "bool", "varchar", "int4", "int4", "bytea"),
                storage.jdbi().withHandle(handle -> handle.createQuery("SELECT udt_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = :name ORDER BY ordinal_position")
                        .bind("name", this.prefix + "snapshots").mapTo(String.class).list()));
        Snapshot snapshot = snapshot(UUID.randomUUID(), 100, false);
        assertEquals(SaveResult.SAVED, storage.saveSnapshot(snapshot).join());
        assertEquals(snapshot, storage.snapshot(snapshot.meta().id()).join().orElseThrow());
        assertEquals(snapshot, storage.latestSnapshot(snapshot.meta().player()).join().orElseThrow());
        assertEquals(1L, this.meta(storage, "schema"));
    }

    @Test
    void metadataRemainsReadableWhenPayloadIsCorrupted() throws Exception {
        PostgresStorageProvider storage = this.open();
        Snapshot snapshot = snapshot(UUID.randomUUID(), 100, false);
        storage.saveSnapshot(snapshot).join();
        storage.jdbi().useHandle(handle -> handle.createUpdate("UPDATE " + this.table("snapshots") + " SET format = -1, data = :data").bind("data", new byte[]{1}).execute());
        assertEquals(List.of(snapshot.meta()), storage.listSnapshots(snapshot.meta().player()).join());
        assertThrows(CompletionException.class, () -> storage.snapshot(snapshot.meta().id()).join());
    }

    @Test
    void queriesFilterAndOrderUuidBytesAtEqualTimestamps() throws Exception {
        PostgresStorageProvider storage = this.open();
        UUID player = UUID.randomUUID();
        Snapshot low = withId(snapshot(player, 200, false), UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Snapshot high = withId(snapshot(player, 200, true), UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        Snapshot older = snapshot(player, 100, false);
        storage.saveSnapshot(older).join();
        storage.saveSnapshot(high).join();
        storage.saveSnapshot(low).join();
        storage.saveSnapshot(snapshot(UUID.randomUUID(), 999, false)).join();
        assertEquals(high, storage.latestSnapshot(player).join().orElseThrow());
        assertEquals(List.of(high.meta(), low.meta()), storage.listSnapshots(SnapshotQuery.of(player).between(200, 200)).join());
        assertEquals(List.of(high.meta()), storage.listSnapshots(SnapshotQuery.of(player).withPinned(SnapshotQuery.PinFilter.PINNED).withLimit(1)).join());
        assertEquals(List.of(low.meta()), storage.listSnapshots(SnapshotQuery.of(player).withPinned(SnapshotQuery.PinFilter.UNPINNED).withLimit(1)).join());
    }

    @Test
    void duplicateIdsKeepOriginalDataAndOlderSavesReportTheirOrder() throws Exception {
        PostgresStorageProvider storage = this.open();
        UUID player = UUID.randomUUID();
        Snapshot latest = snapshot(player, 200, false);
        assertEquals(SaveResult.SAVED, storage.saveSnapshot(latest).join());
        assertEquals(SaveResult.SAVED_OUT_OF_ORDER, storage.saveSnapshot(snapshot(player, 100, false)).join());
        assertEquals(SaveResult.DUPLICATE, storage.saveSnapshot(withId(snapshot(player, 300, true), latest.meta().id())).join());
        assertEquals(latest, storage.snapshot(latest.meta().id()).join().orElseThrow());
    }

    @Test
    void pinDeleteAndRotationPreservePinnedAndForeignRecords() throws Exception {
        PostgresStorageProvider storage = this.open();
        UUID player = UUID.randomUUID();
        Snapshot pinned = snapshot(player, 1, true);
        Snapshot foreign = snapshot(UUID.randomUUID(), 1, false);
        storage.saveSnapshot(pinned).join();
        storage.saveSnapshot(foreign).join();
        for (int i = 2; i <= 5; i++) {
            storage.saveSnapshot(snapshot(player, i, false)).join();
        }
        assertEquals(2, storage.rotate(player, 2).join());
        assertEquals(3, storage.listSnapshots(player).join().size());
        assertFalse(storage.setPinned(pinned.meta().id(), true).join());
        assertTrue(storage.setPinned(pinned.meta().id(), false).join());
        assertFalse(storage.setPinned(pinned.meta().id(), false).join());
        assertEquals(3, storage.rotate(player, 0).join());
        assertTrue(storage.snapshot(foreign.meta().id()).join().isPresent());
        assertTrue(storage.deleteSnapshot(foreign.meta().id()).join());
        assertFalse(storage.deleteSnapshot(foreign.meta().id()).join());
    }

    @Test
    void namesPreserveCaseUnicodeSqlCharactersAndTrailingSpaces() throws Exception {
        PostgresStorageProvider storage = this.open();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        storage.ensureUser(first, "玩家😀'_;%").join();
        assertEquals(first, storage.lookupUser("玩家😀'_;%").join().orElseThrow());
        storage.ensureUser(first, "Name").join();
        storage.ensureUser(second, "Name ").join();
        assertEquals(first, storage.lookupUser("Name").join().orElseThrow());
        assertEquals(second, storage.lookupUser("Name ").join().orElseThrow());
        assertTrue(storage.lookupUser("name").join().isEmpty());
        assertTrue(storage.lookupUser("玩家😀'_;%").join().isEmpty());
        assertThrows(CompletionException.class, () -> storage.ensureUser(first, "x".repeat(65)).join());
        assertThrows(CompletionException.class, () -> storage.ensureUser(first, "a\0b").join());
        assertEquals(first, storage.lookupUser("Name").join().orElseThrow());
    }

    @Test
    void lookupBreaksSameNameAndTimestampTiesByUuid() throws Exception {
        PostgresStorageProvider storage = this.open();
        UUID low = new UUID(0, 1);
        UUID high = new UUID(-1, -1);
        storage.ensureUser(low, "same").join();
        storage.ensureUser(high, "same").join();
        storage.jdbi().useHandle(handle -> handle.execute("UPDATE " + this.table("users") + " SET last_seen = 100"));
        assertEquals(high, storage.lookupUser("same").join().orElseThrow());
    }

    @Test
    void preservesSubmissionOrderWhenEncodingCompletesBackwards() throws Exception {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        PostgresStorageProvider storage = this.provider(this.prefix, jobs::addLast);
        storage.initialize();
        UUID player = UUID.randomUUID();
        CompletableFuture<SaveResult> first = storage.saveSnapshot(snapshot(player, 1, false));
        CompletableFuture<SaveResult> second = storage.saveSnapshot(snapshot(player, 2, false));
        CompletableFuture<Integer> rotated = storage.rotate(player, 1);
        jobs.removeLast().run();
        assertFalse(second.isDone());
        jobs.removeFirst().run();
        assertEquals(SaveResult.SAVED, first.get(5, TimeUnit.SECONDS));
        assertEquals(SaveResult.SAVED, second.get(5, TimeUnit.SECONDS));
        assertEquals(1, rotated.get(5, TimeUnit.SECONDS));
    }

    @Test
    void foreignUniqueViolationIsMalformedInsteadOfDuplicate() throws Exception {
        PostgresStorageProvider storage = this.open();
        storage.jdbi().useHandle(handle -> handle.execute("CREATE UNIQUE INDEX extra_unique ON " + this.table("snapshots") + " (server)"));
        storage.saveSnapshot(snapshot(UUID.randomUUID(), 1, false)).join();
        assertEquals(SaveResult.REJECTED_MALFORMED, storage.saveSnapshot(snapshot(UUID.randomUUID(), 2, false)).join());
    }

    @Test
    void realLockTimeoutRetainsCauseAndCanRetry() throws Exception {
        PostgresStorageProvider storage = this.open();
        storage.jdbi().setSqlLogger(new SqlLogger() {
            @Override
            public void logBeforeExecution(StatementContext context) {
                try (var statement = context.getConnection().createStatement()) {
                    statement.execute("SET lock_timeout = '100ms'");
                } catch (SQLException exception) {
                    throw new UnableToExecuteStatementException(exception, context);
                }
            }
        });
        Snapshot snapshot = snapshot(UUID.randomUUID(), 1, false);
        try (Handle locked = storage.jdbi().open()) {
            locked.begin();
            locked.execute("LOCK TABLE " + this.table("snapshots") + " IN ACCESS EXCLUSIVE MODE");
            var outcome = storage.saveSnapshotOutcome(snapshot).get(5, TimeUnit.SECONDS);
            assertEquals(SaveResult.RETRY_LATER, outcome.result());
            assertEquals("55P03", PostgresFailureClassifier.sqlCause(outcome.failure()).getSQLState());
            locked.rollback();
        }
        assertEquals(SaveResult.SAVED, storage.saveSnapshot(snapshot).join());
    }

    @Test
    void failedOrderCheckDoesNotEraseCommittedSave() throws Exception {
        PostgresStorageProvider storage = this.open();
        AtomicBoolean failed = new AtomicBoolean();
        storage.jdbi().setSqlLogger(new SqlLogger() {
            @Override
            public void logBeforeExecution(StatementContext context) {
                if (context.getRawSql().startsWith("SELECT \"id\", \"ts\", \"server\"") && failed.compareAndSet(false, true)) {
                    throw new UnableToExecuteStatementException(new SQLTransientConnectionException("temporary failure", "08006"), context);
                }
            }
        });
        Snapshot snapshot = snapshot(UUID.randomUUID(), 1, false);
        assertEquals(SaveResult.SAVED, storage.saveSnapshot(snapshot).join());
        assertTrue(failed.get());
        assertEquals(snapshot, storage.snapshot(snapshot.meta().id()).join().orElseThrow());
    }

    @Test
    void rejectsNulMetadataAndOversizedPayloadWithoutStoring() throws Exception {
        PostgresStorageProvider storage = this.open();
        Snapshot valid = snapshot(UUID.randomUUID(), 1, false);
        SnapshotMeta meta = valid.meta();
        Snapshot invalid = new Snapshot(new SnapshotMeta(meta.id(), meta.player(), meta.timestamp(), meta.cause(), false, "a\0b", meta.mcDataVersion()), valid.data());
        assertEquals(SaveResult.REJECTED_MALFORMED, storage.saveSnapshot(invalid).join());
        byte[] bytes = new byte[16 * 1024 * 1024];
        new Random(1).nextBytes(bytes);
        Snapshot oversized = new Snapshot(meta, Map.of(DataKey.of("test", "large"), NBT.createByteArray(bytes)));
        assertEquals(SaveResult.REJECTED_OVERSIZED, storage.saveSnapshot(oversized).join());
        assertTrue(storage.snapshot(meta.id()).join().isEmpty());
    }

    @Test
    void sameSourceRacesReturnOneMapAndDifferentSourcesHaveUniqueIds() throws Exception {
        PostgresStorageProvider first = this.open();
        PostgresStorageProvider second = this.open();
        List<CompletableFuture<StoredMap>> same = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            same.add((i % 2 == 0 ? first : second).maps().register(new MapSource("origin", 1), mapData(i)));
        }
        HashSet<Integer> ids = new HashSet<>();
        StoredMap winner = same.getFirst().join();
        for (int i = 0; i < same.size(); i++) {
            assertEquals(winner, same.get(i).join());
        }
        List<CompletableFuture<StoredMap>> different = new ArrayList<>();
        for (int i = 2; i < 26; i++) {
            different.add((i % 2 == 0 ? first : second).maps().register(new MapSource("origin", i), mapData(i)));
        }
        for (int i = 0; i < different.size(); i++) {
            assertTrue(ids.add(different.get(i).join().identity().globalId()));
        }
    }

    @Test
    void mapUpdateChecksIdentityAndPreservesOriginalOnFailure() throws Exception {
        PostgresStorageProvider storage = this.open();
        StoredMap map = storage.maps().register(new MapSource("world", 10), mapData(1)).join();
        MapIdentity wrong = new MapIdentity(new MapSource("other", 10), map.identity().globalId());
        assertThrows(CompletionException.class, () -> storage.maps().update(wrong, mapData(2)).join());
        assertEquals(map, storage.maps().find(map.identity().globalId()).join().orElseThrow());
        storage.maps().update(map.identity(), mapData(3)).join();
        storage.maps().update(map.identity(), mapData(3)).join();
        assertEquals(mapData(3), storage.maps().find(map.identity().globalId()).join().orElseThrow().data());
        boolean timestampPresent = storage.jdbi().withHandle(handle -> handle.createQuery("SELECT updated_at > 0 FROM " + this.table("maps")).mapTo(Boolean.class).one());
        assertTrue(timestampPresent);
    }

    @Test
    void mapSequenceStopsAtMinimumIntAndDoesNotResetOnRestart() throws Exception {
        PostgresStorageProvider storage = this.open();
        storage.jdbi().useHandle(handle -> handle.execute("UPDATE " + this.table("meta") + " SET value = 2147483647 WHERE id = 'maps'"));
        StoredMap last = storage.maps().register(new MapSource("world", 1), mapData(1)).join();
        assertEquals(Integer.MIN_VALUE, last.identity().globalId());
        assertThrows(CompletionException.class, () -> storage.maps().register(new MapSource("world", 2), mapData(2)).join());
        PostgresStorageProvider reopened = this.open();
        assertEquals(2147483648L, this.meta(reopened, "maps"));
        assertEquals(last, reopened.maps().register(last.identity().source(), mapData(9)).join());
    }

    @Test
    void pendingStashRestoresThroughTheExistingStorageContract() throws Exception {
        PostgresStorageProvider storage = this.open();
        Snapshot snapshot = snapshot(UUID.randomUUID(), 1, false);
        SnapshotStash stash = new SnapshotStash(this.stashDirectory, this.binary, this.logger);
        stash.stash(snapshot, "player", SaveResult.RETRY_LATER);
        stash.restorePending(storage);
        assertEquals(snapshot, storage.snapshot(snapshot.meta().id()).join().orElseThrow());
    }

    @Test
    void concurrentStartupAndDifferentPrefixesKeepIndependentTablesAndIndexes() throws Exception {
        PostgresStorageProvider first = this.provider(this.prefix, ForkJoinPool.commonPool());
        PostgresStorageProvider second = this.provider(this.prefix, ForkJoinPool.commonPool());
        CompletableFuture.allOf(CompletableFuture.runAsync(first::initialize), CompletableFuture.runAsync(second::initialize)).get(15, TimeUnit.SECONDS);
        assertEquals(1L, this.meta(first, "schema"));
        PostgresStorageProvider separate = this.provider("p".repeat(40), ForkJoinPool.commonPool());
        separate.initialize();
        Snapshot snapshot = snapshot(UUID.randomUUID(), 1, false);
        first.saveSnapshot(snapshot).join();
        assertTrue(separate.snapshot(snapshot.meta().id()).join().isEmpty());
    }

    @Test
    void initializationFailureRollsBackDdlAndCanRetry() {
        Jdbi direct = Jdbi.create(this.url, this.username, this.password);
        PostgresSchemaMigrator failed = new PostgresSchemaMigrator(this.logger, 1, (handle, prefix) -> {
            PostgresSchema.initialize(handle, prefix);
            throw new IllegalStateException("injected initialization failure");
        }, List.of());
        assertThrows(IllegalStateException.class, () -> failed.migrate(direct, this.prefix));
        int tables = direct.withHandle(handle -> handle.createQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name LIKE :pattern").bind("pattern", this.prefix + "%").mapTo(Integer.class).one());
        assertEquals(0, tables);
        new PostgresSchemaMigrator(this.logger, 1, PostgresSchema::initialize, List.of()).migrate(direct, this.prefix);
    }

    @Test
    void failedUpgradeRollsBackDataAndDdlAndOlderCodeRejectsNewerSchema() throws Exception {
        PostgresStorageProvider storage = this.open();
        Snapshot snapshot = snapshot(UUID.randomUUID(), 1, false);
        storage.saveSnapshot(snapshot).join();
        AtomicBoolean fail = new AtomicBoolean(true);
        PostgresSchemaMigration migration = new PostgresSchemaMigration() {
            @Override public int targetVersion() { return 2; }
            @Override public void migrate(Handle handle, String prefix) {
                handle.execute("ALTER TABLE \"" + prefix + "snapshots\" ADD COLUMN note TEXT");
                handle.execute("UPDATE \"" + prefix + "snapshots\" SET note = 'upgraded'");
                if (fail.get()) throw new IllegalStateException("injected upgrade failure");
            }
        };
        PostgresSchemaMigrator migrator = new PostgresSchemaMigrator(this.logger, 2, (handle, prefix) -> fail("existing schema should upgrade"), List.of(migration));
        assertThrows(IllegalStateException.class, () -> migrator.migrate(storage.jdbi(), this.prefix));
        assertEquals(1L, this.meta(storage, "schema"));
        assertEquals(snapshot, storage.snapshot(snapshot.meta().id()).join().orElseThrow());
        fail.set(false);
        migrator.migrate(storage.jdbi(), this.prefix);
        assertEquals(2L, this.meta(storage, "schema"));
        assertEquals("upgraded", storage.jdbi().withHandle(handle -> handle.createQuery("SELECT note FROM " + this.table("snapshots")).mapTo(String.class).one()));
        assertThrows(IllegalStateException.class, () -> new PostgresSchemaMigrator(this.logger, 1, PostgresSchema::initialize, List.of()).migrate(storage.jdbi(), this.prefix));
    }

    @Test
    void migrationLockSurvivesDdlUntilTransactionCommits() throws Exception {
        Jdbi direct = Jdbi.create(this.url, this.username, this.password);
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PostgresSchemaMigrator migrator = new PostgresSchemaMigrator(this.logger, 1, (handle, prefix) -> {
            PostgresSchema.initialize(handle, prefix);
            created.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }, List.of());
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> migrator.migrate(direct, this.prefix));
        try {
            assertTrue(created.await(5, TimeUnit.SECONDS));
            boolean acquired = direct.inTransaction(handle -> handle.createQuery("SELECT pg_try_advisory_xact_lock(1936946030, hashtext(current_schema() || ':' || :prefix))").bind("prefix", this.prefix).mapTo(Boolean.class).one());
            assertFalse(acquired);
        } finally {
            release.countDown();
        }
        first.get(5, TimeUnit.SECONDS);
        boolean acquiredAfterCommit = direct.inTransaction(handle -> handle.createQuery("SELECT pg_try_advisory_xact_lock(1936946030, hashtext(current_schema() || ':' || :prefix))").bind("prefix", this.prefix).mapTo(Boolean.class).one());
        assertTrue(acquiredAfterCommit);
    }

    @Test
    void urlParametersOverrideTimeoutsAndPoolClosesOnShutdown() throws Exception {
        PluginConfig.PostgresOptions options = this.options(this.prefix);
        setOption(options, "url", this.url + "&connectTimeout=7&socketTimeout=23");
        PostgresStorageProvider storage = new PostgresStorageProvider(options, this.binary, this.serial, ForkJoinPool.commonPool(), this.logger);
        this.providers.add(storage);
        storage.initialize();
        Field field = PostgresStorageProvider.class.getDeclaredField("dataSource");
        field.setAccessible(true);
        HikariDataSource pool = (HikariDataSource) field.get(storage);
        PGSimpleDataSource source = (PGSimpleDataSource) pool.getDataSource();
        assertEquals(7, source.getConnectTimeout());
        assertEquals(23, source.getSocketTimeout());
        try (var connection = pool.getConnection()) {
            assertEquals(23000, connection.getNetworkTimeout());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
        }
        storage.shutdown();
        assertTrue(pool.isClosed());
        assertThrows(IllegalStateException.class, storage::maps);
    }

    @Test
    void rejectsUnversionedTablesAndInvalidOptions() throws Exception {
        PostgresStorageProvider storage = this.provider(this.prefix, ForkJoinPool.commonPool());
        Jdbi direct = Jdbi.create(this.url, this.username, this.password);
        direct.useHandle(handle -> handle.execute("CREATE TABLE " + this.table("snapshots") + " (id UUID PRIMARY KEY)"));
        assertThrows(IllegalStateException.class, storage::initialize);
        assertThrows(IllegalArgumentException.class, () -> this.provider("x".repeat(41), ForkJoinPool.commonPool()).initialize());
        assertThrows(IllegalArgumentException.class, () -> this.provider("bad-", ForkJoinPool.commonPool()).initialize());
    }

    private PostgresStorageProvider open() throws Exception {
        PostgresStorageProvider storage = this.provider(this.prefix, ForkJoinPool.commonPool());
        storage.initialize();
        return storage;
    }

    private PostgresStorageProvider provider(String prefix, Executor executor) throws Exception {
        PostgresStorageProvider storage = new PostgresStorageProvider(this.options(prefix), this.binary, this.serial, executor, this.logger);
        this.providers.add(storage);
        return storage;
    }

    private PluginConfig.PostgresOptions options(String prefix) throws Exception {
        PluginConfig.PostgresOptions options = new PluginConfig.PostgresOptions();
        setOption(options, "url", this.url);
        setOption(options, "username", this.username);
        setOption(options, "password", this.password);
        setOption(options, "tablePrefix", prefix);
        return options;
    }

    private static void setOption(PluginConfig.PostgresOptions options, String name, String value) throws Exception {
        Field field = PluginConfig.PostgresOptions.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(options, value);
    }

    private String table(String suffix) {
        return "\"" + this.prefix + suffix + "\"";
    }

    private long meta(PostgresStorageProvider storage, String name) {
        return storage.jdbi().withHandle(handle -> handle.createQuery("SELECT value FROM " + this.table("meta") + " WHERE id = :id").bind("id", name).mapTo(Long.class).one());
    }

    private static Snapshot snapshot(UUID player, long timestamp, boolean pinned) {
        return new Snapshot(new SnapshotMeta(UUID.randomUUID(), player, timestamp, SaveCause.COMMAND, pinned, "test", 4440), Map.of(DataKey.of("unregistered", "value"), NBT.createLong(timestamp)));
    }

    private static Snapshot withId(Snapshot source, UUID id) {
        SnapshotMeta meta = source.meta();
        return new Snapshot(new SnapshotMeta(id, meta.player(), meta.timestamp(), meta.cause(), meta.pinned(), meta.server(), meta.mcDataVersion()), source.data());
    }

    private static MapData mapData(int color) {
        CompoundTag tag = NBT.createCompound();
        tag.putString("dimension", "minecraft:overworld");
        byte[] colors = new byte[MapData.PIXEL_COUNT];
        colors[0] = (byte) color;
        tag.putByteArray("colors", colors);
        return new MapData(4440, tag);
    }

    private static final class QuietLogger implements PluginLogger {
        @Override public void info(String message) {}
        @Override public void warn(String message) {}
        @Override public void warn(String message, Throwable failure) {}
        @Override public void error(String message) {}
        @Override public void error(String message, Throwable failure) {}
    }
}
