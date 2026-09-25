package net.momirealms.sparrow.sync.storage.mysql;

import net.momirealms.sparrow.sync.test.NoopSnapshotCache;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.sync.storage.SnapshotRow;
import net.momirealms.sparrow.sync.storage.SnapshotRowMapper;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.jdbc.JdbcConnection;
import com.zaxxer.hikari.HikariDataSource;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.map.MapStorage;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotStash;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveOutcome;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.storage.mysql.upgrade.MysqlSchemaMigration;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleListener;
import org.jdbi.v3.core.Handles;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sparrow.test.database", matches = "true", disabledReason = "Database integration tests are off by default; run with -Psparrow.test.database=true")
@EnabledIfEnvironmentVariable(named = "SPARROW_TEST_MYSQL_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MysqlStorageProviderTest {
    private final QuietLogger console = new QuietLogger();
    private final SyncLogger logger = new SyncLogger(this.console);
    private final List<MysqlStorageProvider> providers = new ArrayList<>();
    private final SnapshotDataCodec binary = new SnapshotDataCodec(CompressorRegistry.DEFLATE);
    private final RowSnapshotCodec codec = new RowSnapshotCodec(this.binary);
    private Jdbi admin;
    private Jdbi direct;
    private String database;
    private String url;
    private String prefix;
    private String username;
    private String password;
    private PlayerSerialExecutor serialExecutor;
    @TempDir
    Path stashDirectory;

    @BeforeAll
    void connect() {
        String configuredUrl = System.getenv("SPARROW_TEST_MYSQL_URL");
        this.username = System.getenv().getOrDefault("SPARROW_TEST_MYSQL_USERNAME", "root");
        this.password = System.getenv().getOrDefault("SPARROW_TEST_MYSQL_PASSWORD", "");
        this.admin = Jdbi.create(configuredUrl, this.username, this.password);
        this.database = "sparrow_mysql_it_" + UUID.randomUUID().toString().replace("-", "");
        this.admin.useHandle(handle -> handle.execute("CREATE DATABASE `" + this.database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"));
        int query = configuredUrl.indexOf('?');
        String address = query < 0 ? configuredUrl : configuredUrl.substring(0, query);
        String options = query < 0 ? "" : configuredUrl.substring(query);
        this.url = address.substring(0, address.lastIndexOf('/') + 1) + this.database + options;
        this.direct = Jdbi.create(this.url, this.username, this.password);
        System.out.println("MySQL integration server: " + this.direct.withHandle(handle -> handle.createQuery("SELECT VERSION()").mapTo(String.class).one()));
    }

    @BeforeEach
    void prepare() {
        this.prefix = "it_" + UUID.randomUUID().toString().replace("-", "") + "_";
        this.serialExecutor = new PlayerSerialExecutor(this.logger, 4);
        this.console.messages.clear();
    }

    @AfterEach
    void closeProviders() {
        this.serialExecutor.shutdown(5, TimeUnit.SECONDS);
        for (int i = 0; i < this.providers.size(); i++) this.providers.get(i).shutdown();
        this.providers.clear();
    }

    @AfterAll
    void dropDatabase() {
        if (this.database != null) {
            this.admin.useHandle(handle -> handle.execute("DROP DATABASE `" + this.database + "`"));
        }
    }

    @Test
    void createsFourTablesAndRoundTripsTheBinaryRow() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        Snapshot snapshot = new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1756300000001L, SaveCause.COMMAND, true, "大厅-É😀", 4440),
                Map.of(DataKey.of("external", "data"), NBT.createLongArray(new long[]{Long.MIN_VALUE, 42})));
        SnapshotRow row = this.codec.encode(snapshot);
        this.insert(provider.jdbi(), row);
        SnapshotRow restored = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT * FROM `" + this.prefix + "snapshots` WHERE id = :id")
                .bind("id", snapshot.meta().id()).mapTo(SnapshotRow.class).one());
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(restored)).snapshot());
        assertEquals(Set.of(this.prefix + "meta", this.prefix + "maps", this.prefix + "snapshots", this.prefix + "users"),
                Set.copyOf(provider.jdbi().withHandle(handle -> handle.createQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND LEFT(table_name, :length) = :prefix")
                        .bind("length", this.prefix.length()).bind("prefix", this.prefix).mapTo(String.class).list())));
        assertEquals(MysqlSchema.CURRENT_VERSION, this.meta("schema"));
        assertEquals(0, this.meta("maps"));
        assertEquals(List.of("PRIMARY"), provider.jdbi().withHandle(handle -> handle.createQuery("SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = :table")
                .bind("table", this.prefix + "meta").mapTo(String.class).list()));
        assertEquals("bigint", provider.jdbi().withHandle(handle -> handle.createQuery("SELECT data_type FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = :table AND column_name = 'updated_at'")
                .bind("table", this.prefix + "maps").mapTo(String.class).one()));
    }

    @Test
    void metadataProjectionDoesNotReadFormatOrPayload() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        SnapshotMeta meta = new SnapshotMeta(UUID.fromString("fedcba98-7654-3210-0123-456789abcdef"), UUID.randomUUID(), 123456789L, SaveCause.UNKNOWN, false, "", 4440);
        this.insert(provider.jdbi(), new SnapshotRow(meta, 99, new byte[]{0}));
        SnapshotMeta found = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT id, player, ts, cause, pinned, server, mc_data FROM `" + this.prefix + "snapshots`")
                .mapTo(SnapshotMeta.class).one());
        assertEquals(meta, found);
        assertEquals(Optional.of(meta), provider.snapshotMeta(meta.id()).join());
        assertTrue(provider.snapshotMeta(UUID.randomUUID()).join().isEmpty());
        assertEquals("FEDCBA98765432100123456789ABCDEF", provider.jdbi().withHandle(handle -> handle.createQuery("SELECT HEX(id) FROM `" + this.prefix + "snapshots`").mapTo(String.class).one()));
        assertEquals(List.of(meta), provider.listSnapshots(SnapshotQuery.of(meta.player())).join());
    }

    @Test
    void snapshotQueriesUseTimestampAndUnsignedBinaryIdOrder() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        Snapshot earlier = new Snapshot(new SnapshotMeta(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), player, 10, SaveCause.COMMAND, true, "大厅", 4440), Map.of());
        Snapshot lowerId = new Snapshot(new SnapshotMeta(UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"), player, 20, SaveCause.COMMAND, false, "大厅", 4440), Map.of());
        Snapshot latest = new Snapshot(new SnapshotMeta(UUID.fromString("80000000-0000-0000-0000-000000000000"), player, 20, SaveCause.COMMAND, false, "大厅", 4440), Map.of(DataKey.of("external", "test"), NBT.createString("payload😀")));
        Snapshot foreign = new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 30, SaveCause.COMMAND, false, "other", 4440), Map.of());
        for (Snapshot snapshot : List.of(earlier, latest, lowerId, foreign)) {
            this.insert(provider.jdbi(), this.codec.encode(snapshot));
        }
        assertEquals(Optional.of(latest), provider.latestSnapshot(player).join());
        assertEquals(Optional.of(earlier), provider.snapshot(earlier.meta().id()).join());
        assertEquals(Optional.of(latest), provider.snapshot(latest.meta().id()).join());
        assertEquals(Optional.of(foreign), provider.latestSnapshot(foreign.meta().player()).join());
        assertEquals(Optional.empty(), provider.latestSnapshot(UUID.randomUUID()).join());
        assertEquals(Optional.empty(), provider.snapshot(UUID.randomUUID()).join());
        assertEquals(List.of(latest.meta(), lowerId.meta(), earlier.meta()), provider.listSnapshots(SnapshotQuery.of(player)).join());
    }

    @Test
    void listCombinesInclusiveRangesPinFiltersAndLimits() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        long[] timestamps = {Long.MIN_VALUE, 10, 20, 20, 30, Long.MAX_VALUE};
        List<SnapshotMeta> metas = new ArrayList<>();
        for (int i = 0; i < timestamps.length; i++) {
            SnapshotMeta meta = new SnapshotMeta(new UUID(0, i + 1), player, timestamps[i], SaveCause.COMMAND, i % 2 == 1, "", 4440);
            metas.add(meta);
            this.insert(provider.jdbi(), this.codec.encode(new Snapshot(meta, Map.of())));
        }
        SnapshotQuery all = SnapshotQuery.of(player);
        List<Map.Entry<SnapshotQuery, List<SnapshotMeta>>> cases = List.of(
                Map.entry(all, List.of(metas.get(5), metas.get(4), metas.get(3), metas.get(2), metas.get(1), metas.get(0))),
                Map.entry(all.between(10, 20), List.of(metas.get(3), metas.get(2), metas.get(1))),
                Map.entry(all.between(20, 20), List.of(metas.get(3), metas.get(2))),
                Map.entry(all.between(Long.MIN_VALUE, 10), List.of(metas.get(1), metas.get(0))),
                Map.entry(all.between(30, Long.MAX_VALUE), List.of(metas.get(5), metas.get(4))),
                Map.entry(all.between(Long.MIN_VALUE, Long.MIN_VALUE), List.of(metas.get(0))),
                Map.entry(all.between(Long.MAX_VALUE, Long.MAX_VALUE), List.of(metas.get(5))),
                Map.entry(all.between(21, 29), List.of()),
                Map.entry(all.withPinned(SnapshotQuery.PinFilter.PINNED), List.of(metas.get(5), metas.get(3), metas.get(1))),
                Map.entry(all.withPinned(SnapshotQuery.PinFilter.UNPINNED), List.of(metas.get(4), metas.get(2), metas.get(0))),
                Map.entry(all.between(10, 20).withPinned(SnapshotQuery.PinFilter.PINNED).withLimit(1), List.of(metas.get(3))),
                Map.entry(all.between(10, 20).withPinned(SnapshotQuery.PinFilter.UNPINNED).withLimit(1), List.of(metas.get(2))),
                Map.entry(all.withLimit(2), List.of(metas.get(5), metas.get(4))),
                Map.entry(all.withLimit(2).withOffset(2), List.of(metas.get(3), metas.get(2))),
                Map.entry(all.withOffset(5), List.of(metas.get(0))),
                Map.entry(all.withLimit(2).withOffset(6), List.of()),
                Map.entry(all.between(10, 20).withPinned(SnapshotQuery.PinFilter.PINNED).withLimit(1).withOffset(1), List.of(metas.get(1)))
        );
        for (int i = 0; i < cases.size(); i++) {
            var expected = cases.get(i);
            assertEquals(expected.getValue(), provider.listSnapshots(expected.getKey()).join(), expected.getKey().toString());
            assertEquals((long) provider.listSnapshots(expected.getKey().withLimit(0).withOffset(0)).join().size(), provider.countSnapshots(expected.getKey()).join());
        }
        assertEquals(List.of(), provider.listSnapshots(SnapshotQuery.of(UUID.randomUUID())).join());
        assertEquals(provider.listSnapshots(all).join(), provider.listSnapshots(all.withLimit(-1)).join());
    }

    @Test
    void paginationOnlySelectsTheRequestedMetadataRows() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        List<SnapshotMeta> metas = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            SnapshotMeta meta = new SnapshotMeta(new UUID(0, i + 1), player, 100, SaveCause.COMMAND, true, "lobby", 4440);
            metas.addFirst(meta);
            this.insert(provider.jdbi(), new SnapshotRow(meta, 99, new byte[]{0}));
        }
        List<String> statements = new ArrayList<>();
        provider.jdbi().setSqlLogger(new SqlLogger() {
            @Override
            public void logBeforeExecution(StatementContext context) {
                statements.add(context.getRenderedSql());
            }
        });
        SnapshotQuery query = SnapshotQuery.of(player).withOffset(27).withLimit(27);
        assertEquals(metas.subList(27, 32), provider.listSnapshots(query).join());
        assertEquals(32L, provider.countSnapshots(query).join());
        assertEquals(2, statements.size());
        assertTrue(statements.getFirst().contains("LIMIT :limit OFFSET :offset"));
        assertFalse(statements.getFirst().contains("`format`"));
        assertFalse(statements.getFirst().contains("`data`"));
        assertTrue(statements.getLast().startsWith("SELECT COUNT(*)"));
        assertFalse(statements.getLast().contains("LIMIT"));
        assertFalse(statements.getLast().contains("OFFSET"));
    }

    @Test
    void invalidFullSnapshotsFailWhileMetadataRemainsReadable() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        Snapshot valid = new Snapshot(new SnapshotMeta(UUID.randomUUID(), player, 10, SaveCause.COMMAND, false, "", 4440), Map.of());
        SnapshotMeta broken = new SnapshotMeta(UUID.randomUUID(), player, 20, SaveCause.COMMAND, false, "", 4440);
        this.insert(provider.jdbi(), this.codec.encode(valid));
        this.insert(provider.jdbi(), new SnapshotRow(broken, 2, new byte[]{0}));
        CompletionException byId = assertThrows(CompletionException.class, () -> provider.snapshot(broken.id()).join());
        assertInstanceOf(IOException.class, byId.getCause());
        assertEquals(FormatException.InvalidReason.CORRUPTED, assertInstanceOf(FormatException.class, byId.getCause()).reason());
        assertTrue(byId.getCause().getMessage().contains("CORRUPTED"));
        assertThrows(CompletionException.class, () -> provider.latestSnapshot(player).join());
        assertEquals(List.of(broken, valid.meta()), provider.listSnapshots(SnapshotQuery.of(player)).join());
        assertEquals(Optional.of(valid), provider.snapshot(valid.meta().id()).join());
        provider.jdbi().useHandle(handle -> handle.createUpdate("UPDATE `" + this.prefix + "snapshots` SET format = 99 WHERE id = :id").bind("id", broken.id()).execute());
        CompletionException futureFormat = assertThrows(CompletionException.class, () -> provider.latestSnapshot(player).join());
        assertTrue(futureFormat.getCause().getMessage().contains("UNSUPPORTED_FORMAT"));
        assertEquals(FormatException.InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(FormatException.class, futureFormat.getCause()).reason());
    }

    @Test
    void fullSnapshotReadsDecodeAfterConnectionReleaseInOneWorkerTask() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        MysqlStorageProvider provider = this.provider(this.url, this.prefix, tasks::addLast);
        provider.initialize();
        Snapshot snapshot = new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 20, SaveCause.COMMAND, false, "", 4440), Map.of(DataKey.of("test", "payload"), NBT.createString("detached row")));
        HikariDataSource pool = this.pool(provider);
        this.insert(provider.jdbi(), this.codec.encode(snapshot));
        ArrayDeque<SnapshotRow> rows = new ArrayDeque<>();
        AtomicInteger released = new AtomicInteger();
        SnapshotRowMapper mapper = new SnapshotRowMapper();
        provider.jdbi().registerRowMapper(SnapshotRow.class, (result, context) -> {
            SnapshotRow row = mapper.map(result, context);
            row.data()[0] = 0;
            rows.addLast(row);
            return row;
        });
        provider.jdbi().getConfig(Handles.class).addListener(new HandleListener() {
            @Override
            public void handleClosed(Handle handle) {
                assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
                SnapshotRow row = rows.pollFirst();
                if (row != null) {
                    row.data()[0] = 'S';
                    released.incrementAndGet();
                }
            }
        });
        for (int i = 0; i < 2; i++) {
            CompletableFuture<Optional<Snapshot>> read = i == 0 ? provider.latestSnapshot(snapshot.meta().player()) : provider.snapshot(snapshot.meta().id());
            assertFalse(read.isDone());
            assertEquals(1, tasks.size());
            tasks.removeFirst().run();
            assertTrue(read.isDone());
            assertEquals(Optional.of(snapshot), read.join());
            assertEquals(i + 1, released.get());
            assertTrue(tasks.isEmpty());
        }
        CompletableFuture<Optional<Snapshot>> missing = provider.snapshot(UUID.randomUUID());
        assertEquals(1, tasks.size());
        tasks.removeFirst().run();
        assertTrue(missing.isDone());
        assertEquals(Optional.empty(), missing.join());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void mapReadsCompleteInOneWorkerTask() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        MysqlStorageProvider provider = this.provider(this.url, this.prefix, tasks::addLast);
        provider.initialize();
        MapStorage maps = provider.maps();
        CompletableFuture<StoredMap> registration = maps.register(new MapSource("source", 1), mapData(1));
        tasks.removeFirst().run();
        StoredMap stored = registration.join();
        for (int i = 0; i < 2; i++) {
            CompletableFuture<Optional<StoredMap>> read = maps.find(i == 0 ? stored.identity().globalId() : -100);
            assertFalse(read.isDone());
            assertEquals(1, tasks.size());
            tasks.removeFirst().run();
            assertTrue(read.isDone());
            assertEquals(i == 0 ? Optional.of(stored) : Optional.empty(), read.join());
            assertEquals(0, this.pool(provider).getHikariPoolMXBean().getActiveConnections());
            assertTrue(tasks.isEmpty());
        }
    }

    @Test
    void ensureUserRefreshesTheNameAndSessionTime() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        long before = System.currentTimeMillis();
        provider.ensureUser(player, "Original").join();
        long after = System.currentTimeMillis();
        long seen = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT last_seen FROM `" + this.prefix + "users` WHERE player = :player").bind("player", player).mapTo(Long.class).one());
        assertTrue(seen >= before && seen <= after);
        provider.jdbi().useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "users` SET last_seen = 1"));
        provider.ensureUser(player, "Original").join();
        long refreshed = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT last_seen FROM `" + this.prefix + "users`").mapTo(Long.class).one());
        assertTrue(refreshed > 1);
        provider.ensureUser(player, "Renamed").join();
        assertEquals(Optional.of(player), provider.lookupUser("Renamed").join());
        assertEquals(Optional.empty(), provider.lookupUser("Original").join());
        int count = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM `" + this.prefix + "users`").mapTo(Integer.class).one());
        assertEquals(1, count);
    }

    @Test
    void lookupUserChoosesTheLatestSessionAndBreaksTiesByUuid() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID first = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID second = UUID.fromString("80000000-0000-0000-0000-000000000000");
        provider.ensureUser(first, "Shared").join();
        provider.ensureUser(second, "Shared").join();
        provider.jdbi().useHandle(handle -> handle.createUpdate("UPDATE `" + this.prefix + "users` SET last_seen = CASE WHEN player = :first THEN 20 ELSE 10 END").bind("first", first).execute());
        assertEquals(Optional.of(first), provider.lookupUser("Shared").join());
        provider.jdbi().useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "users` SET last_seen = 20"));
        assertEquals(Optional.of(second), provider.lookupUser("Shared").join());
        assertEquals(Optional.empty(), provider.lookupUser("Nobody").join());
    }

    @Test
    void namesPreserveUnicodeCaseAndLiteralSqlCharacters() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        List<String> names = List.of("Catnies", "catnies", "Cátnies", "Ca\u0301tnies", "玩家😀", "a' OR '1'='1", " Leading", "", "😀".repeat(64));
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            UUID player = UUID.randomUUID();
            players.add(player);
            provider.ensureUser(player, names.get(i)).join();
        }
        for (int i = 0; i < names.size(); i++) {
            assertEquals(Optional.of(players.get(i)), provider.lookupUser(names.get(i)).join(), names.get(i));
        }
    }

    @Test
    void invalidUserNamesFailWithoutChangingTheExistingMapping() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        provider.ensureUser(player, "Original").join();
        provider.jdbi().useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "users` SET last_seen = 17"));
        List<String> invalid = List.of("a".repeat(65), "😀".repeat(65), "Original ", " ");
        for (int i = 0; i < invalid.size(); i++) {
            String name = invalid.get(i);
            CompletionException write = assertThrows(CompletionException.class, () -> provider.ensureUser(player, name).join());
            assertInstanceOf(IllegalArgumentException.class, write.getCause());
            CompletionException read = assertThrows(CompletionException.class, () -> provider.lookupUser(name).join());
            assertInstanceOf(IllegalArgumentException.class, read.getCause());
        }
        assertEquals(Optional.of(player), provider.lookupUser("Original").join());
        long seen = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT last_seen FROM `" + this.prefix + "users`").mapTo(Long.class).one());
        assertEquals(17, seen);
    }

    @Test
    void databaseErrorsCompleteQueryAndUserFuturesExceptionally() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        provider.jdbi().useHandle(handle -> {
            handle.execute("DROP TABLE `" + this.prefix + "snapshots`");
            handle.execute("DROP TABLE `" + this.prefix + "users`");
        });
        assertThrows(CompletionException.class, () -> provider.latestSnapshot(player).join());
        assertThrows(CompletionException.class, () -> provider.snapshot(UUID.randomUUID()).join());
        assertThrows(CompletionException.class, () -> provider.listSnapshots(SnapshotQuery.of(player)).join());
        assertThrows(CompletionException.class, () -> provider.ensureUser(player, "Catnies").join());
        assertThrows(CompletionException.class, () -> provider.lookupUser("Catnies").join());
        assertThrows(CompletionException.class, () -> provider.saveSnapshot(this.snapshot(player, 20, false)).join());
        assertThrows(CompletionException.class, () -> provider.rotate(player, 1).join());
        assertThrows(CompletionException.class, () -> provider.setPinned(UUID.randomUUID(), true).join());
        assertThrows(CompletionException.class, () -> provider.deleteSnapshot(UUID.randomUUID()).join());
    }

    @Test
    void mapsShareThePoolAndRefreshTimeOnlyOnContentWrites() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        assertThrows(IllegalStateException.class, provider::maps);
        provider.initialize();
        MapStorage maps = provider.maps();
        assertSame(maps, provider.maps());
        long before = System.currentTimeMillis();
        StoredMap stored = maps.register(new MapSource("source", 0), mapData(1)).join();
        assertEquals(-1, stored.identity().globalId());
        assertTrue(this.mapTime(-1) >= before && this.mapTime(-1) <= System.currentTimeMillis());
        assertEquals(stored, maps.find(-1).join().orElseThrow());
        this.direct.useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "maps` SET updated_at = 5 WHERE global_id = -1"));
        assertEquals(stored, maps.register(stored.identity().source(), mapData(2)).join());
        assertEquals(stored, maps.find(-1).join().orElseThrow());
        assertEquals(5, this.mapTime(-1));
        MapData updated = new MapData(4441, mapData(3).getTag());
        before = System.currentTimeMillis();
        maps.update(stored.identity(), updated).join();
        assertTrue(this.mapTime(-1) >= before && this.mapTime(-1) <= System.currentTimeMillis());
        assertEquals(updated, maps.find(-1).join().orElseThrow().data());
        assertNull(updated.getTag().get("updated_at"));
        provider.shutdown();
        assertThrows(IllegalStateException.class, provider::maps);
        assertThrows(CompletionException.class, () -> maps.find(-1).join());
        provider.initialize();
        assertEquals(updated, provider.maps().find(-1).join().orElseThrow().data());
        assertEquals(-2, provider.maps().register(new MapSource("source", 1), mapData(4)).join().identity().globalId());
        assertEquals(MysqlSchema.CURRENT_VERSION, this.meta("schema"));
    }

    @Test
    void concurrentMapRegistrationReturnsOneCompleteWinner() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        MapStorage maps = provider.maps();
        List<CompletableFuture<StoredMap>> registrations = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            registrations.add(maps.register(new MapSource("same", 0), mapData(i)));
        }
        CompletableFuture.allOf(registrations.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);
        StoredMap winner = registrations.getFirst().join();
        for (int i = 0; i < registrations.size(); i++) {
            assertEquals(winner, registrations.get(i).join());
        }
        assertEquals(winner, maps.find(winner.identity().globalId()).join().orElseThrow());
        int count = this.direct.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM `" + this.prefix + "maps`").mapTo(Integer.class).one());
        assertEquals(1, count);
        assertTrue(this.meta("maps") >= 1 && this.meta("maps") <= 32);
    }

    @Test
    void differentMapSourcesAcrossProvidersReceiveUniqueIds() throws Exception {
        MysqlStorageProvider first = this.provider(this.url, this.prefix);
        MysqlStorageProvider second = this.provider(this.urlWith("useAffectedRows=true"), this.prefix);
        first.initialize();
        second.initialize();
        List<CompletableFuture<StoredMap>> registrations = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            MapStorage maps = (i & 1) == 0 ? first.maps() : second.maps();
            registrations.add(maps.register(new MapSource("parallel", i), mapData(i)));
        }
        CompletableFuture.allOf(registrations.toArray(new CompletableFuture[0])).get(20, TimeUnit.SECONDS);
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < registrations.size(); i++) {
            StoredMap stored = registrations.get(i).join();
            assertEquals(new MapSource("parallel", i), stored.identity().source());
            assertTrue(ids.add(stored.identity().globalId()));
        }
        assertEquals(-128, Collections.min(ids));
        assertEquals(-1, Collections.max(ids));
        assertEquals(128, this.meta("maps"));
        int storedCount = this.direct.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM `" + this.prefix + "maps`").mapTo(Integer.class).one());
        assertEquals(128, storedCount);
    }

    @Test
    void mapSequenceCommitsBeforeTheAssignedIdIsRead() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        CountDownLatch beforeRead = new CountDownLatch(1);
        CountDownLatch resumeRead = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        provider.jdbi().setSqlLogger(new SqlLogger() {
            @Override
            public void logBeforeExecution(StatementContext context) {
                if (context.getRawSql().equals("SELECT LAST_INSERT_ID()") && firstRead.getAndSet(false)) {
                    beforeRead.countDown();
                    try {
                        assertTrue(resumeRead.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                }
            }
        });
        CompletableFuture<StoredMap> first = provider.maps().register(new MapSource("first", 0), mapData(1));
        try {
            assertTrue(beforeRead.await(5, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            assertEquals(1, this.meta("maps"));
            StoredMap second = provider.maps().register(new MapSource("second", 0), mapData(2)).get(5, TimeUnit.SECONDS);
            assertEquals(-2, second.identity().globalId());
        } finally {
            resumeRead.countDown();
        }
        assertEquals(-1, first.get(5, TimeUnit.SECONDS).identity().globalId());
        assertEquals(2, this.meta("maps"));
    }

    @Test
    void failedMapSequenceResultReadLeavesACommittedGap() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        AtomicBoolean fail = new AtomicBoolean(true);
        SQLTransientConnectionException disconnected = new SQLTransientConnectionException("injected sequence result failure", "08006");
        provider.jdbi().setSqlLogger(new SqlLogger() {
            @Override
            public void logBeforeExecution(StatementContext context) {
                if (context.getRawSql().equals("SELECT LAST_INSERT_ID()") && fail.getAndSet(false)) {
                    throw new UnableToExecuteStatementException(disconnected, context);
                }
            }
        });
        MapSource source = new MapSource("retry", 0);
        CompletionException failure = assertThrows(CompletionException.class, () -> provider.maps().register(source, mapData(1)).join());
        assertSame(disconnected, MysqlFailureClassifier.sqlCause(failure));
        assertEquals(1, this.meta("maps"));
        assertEquals(Optional.empty(), provider.maps().find(-1).join());
        assertEquals(-2, provider.maps().register(source, mapData(2)).join().identity().globalId());
        assertEquals(2, this.meta("maps"));
    }

    @Test
    void unavailableMapSequenceDoesNotReuseAPooledConnectionsPreviousId() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        provider.jdbi().getConfig(Handles.class).addListener(new HandleListener() {
            @Override
            public void handleCreated(Handle handle) {
                handle.createQuery("SELECT LAST_INSERT_ID(777)").mapTo(Long.class).one();
            }
        });
        long[] unavailable = {-1, 2147483648L, 2147483649L};
        for (int i = 0; i < unavailable.length; i++) {
            long value = unavailable[i];
            this.direct.useHandle(handle -> handle.createUpdate("UPDATE `" + this.prefix + "meta` SET `value` = :value WHERE id = 'maps'").bind("value", value).execute());
            assertThrows(CompletionException.class, () -> provider.maps().register(new MapSource("unavailable", 0), mapData(1)).join());
            assertEquals(value, this.meta("maps"));
        }
        this.direct.useHandle(handle -> handle.execute("DELETE FROM `" + this.prefix + "meta` WHERE id = 'maps'"));
        assertThrows(CompletionException.class, () -> provider.maps().register(new MapSource("missing", 0), mapData(1)).join());
        int storedCount = this.direct.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM `" + this.prefix + "maps`").mapTo(Integer.class).one());
        assertEquals(0, storedCount);
        this.direct.useHandle(handle -> handle.execute("INSERT INTO `" + this.prefix + "meta` VALUES ('maps', 2147483647)"));
        assertEquals(Integer.MIN_VALUE, provider.maps().register(new MapSource("last", 0), mapData(1)).join().identity().globalId());
        assertThrows(CompletionException.class, () -> provider.maps().register(new MapSource("exhausted", 0), mapData(2)).join());
        assertEquals(2147483648L, this.meta("maps"));
    }

    @Test
    void mapIdentityAndSourceValidationPreserveDataOnFailure() throws Exception {
        MysqlStorageProvider provider = this.provider(this.urlWith("useAffectedRows=true"), this.prefix);
        provider.initialize();
        MapStorage maps = provider.maps();
        StoredMap stored = maps.register(new MapSource("A", 0), mapData(1)).join();
        this.direct.useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "maps` SET updated_at = 5"));
        List<MapIdentity> wrong = List.of(new MapIdentity(new MapSource("B", 0), -1), new MapIdentity(new MapSource("A", 1), -1), new MapIdentity(stored.identity().source(), -99));
        for (int i = 0; i < wrong.size(); i++) {
            MapIdentity identity = wrong.get(i);
            assertThrows(CompletionException.class, () -> maps.update(identity, mapData(2)).join());
        }
        assertEquals(5, this.mapTime(-1));
        assertEquals(stored, maps.find(-1).join().orElseThrow());
        assertTrue(maps.find(-99).join().isEmpty());
        assertThrows(CompletionException.class, () -> maps.register(new MapSource("bad ", 1), mapData(2)).join());
        assertThrows(CompletionException.class, () -> maps.register(new MapSource("😀".repeat(256), 1), mapData(2)).join());
        assertEquals(1, this.meta("maps"));
        maps.update(stored.identity(), stored.data()).join();
        maps.update(stored.identity(), stored.data()).join();
        assertEquals(stored, maps.find(-1).join().orElseThrow());
        StoredMap differentCase = maps.register(new MapSource("a", 0), mapData(3)).join();
        assertNotEquals(stored.identity().globalId(), differentCase.identity().globalId());
    }

    @Test
    void mapCounterStopsAtTheMinimumIntAndPreservesFailedAllocationGaps() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        MapStorage maps = provider.maps();
        StoredMap first = maps.register(new MapSource("same", 0), mapData(1)).join();
        this.direct.useHandle(handle -> handle.execute("CREATE UNIQUE INDEX external_owner ON `" + this.prefix + "maps` (owner)"));
        assertThrows(CompletionException.class, () -> maps.register(new MapSource("same", 1), mapData(2)).join());
        assertEquals(2, this.meta("maps"));
        assertTrue(maps.find(-2).join().isEmpty());
        assertEquals(first, maps.find(-1).join().orElseThrow());
        this.direct.useHandle(handle -> handle.execute("DROP INDEX external_owner ON `" + this.prefix + "maps`"));
        assertEquals(-3, maps.register(new MapSource("same", 1), mapData(3)).join().identity().globalId());
        this.direct.useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "meta` SET value = 2147483647 WHERE id = 'maps'"));
        assertEquals(Integer.MIN_VALUE, maps.register(new MapSource("last", 0), mapData(4)).join().identity().globalId());
        assertThrows(CompletionException.class, () -> maps.register(new MapSource("exhausted", 0), mapData(5)).join());
        assertEquals(2147483648L, this.meta("maps"));
    }

    @Test
    void malformedMapsAndInvalidSequencesFailWithoutRepair() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        MapStorage maps = provider.maps();
        this.direct.useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "meta` SET value = -1 WHERE id = 'maps'"));
        assertThrows(CompletionException.class, () -> maps.register(new MapSource("bad-sequence", 0), mapData(1)).join());
        this.direct.useHandle(handle -> handle.execute("DELETE FROM `" + this.prefix + "meta` WHERE id = 'maps'"));
        assertThrows(CompletionException.class, () -> maps.register(new MapSource("missing-sequence", 0), mapData(1)).join());
        this.direct.useHandle(handle -> handle.execute("INSERT INTO `" + this.prefix + "meta` VALUES ('maps', 0)"));
        StoredMap stored = maps.register(new MapSource("legacy", 0), mapData(1)).join();
        this.direct.useHandle(handle -> {
            handle.execute("ALTER TABLE `" + this.prefix + "maps` MODIFY updated_at BIGINT NULL");
            handle.execute("UPDATE `" + this.prefix + "maps` SET updated_at = NULL");
        });
        assertThrows(CompletionException.class, () -> maps.find(-1).join());
        assertThrows(CompletionException.class, () -> maps.register(stored.identity().source(), mapData(2)).join());
        assertThrows(CompletionException.class, () -> maps.update(stored.identity(), mapData(2)).join());
        this.direct.useHandle(handle -> {
            assertTrue(handle.createQuery("SELECT updated_at IS NULL FROM `" + this.prefix + "maps`").mapTo(Boolean.class).one());
            handle.execute("UPDATE `" + this.prefix + "maps` SET updated_at = 5, data = X'00'");
        });
        assertThrows(CompletionException.class, () -> maps.find(-1).join());
    }

    @Test
    void mapEncodingFailureDoesNotChangeTimeOrAllocateAnId() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        MapStorage maps = provider.maps();
        CompoundTag tag = mapData(2).getTag();
        tag.putString("invalid", "a".repeat(70_000));
        MapData invalid = new MapData(4440, tag);
        assertThrows(CompletionException.class, () -> maps.register(new MapSource("bad", 0), invalid).join());
        assertEquals(0, this.meta("maps"));
        StoredMap stored = maps.register(new MapSource("good", 0), mapData(1)).join();
        this.direct.useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "maps` SET updated_at = 5"));
        assertThrows(CompletionException.class, () -> maps.update(stored.identity(), invalid).join());
        assertEquals(5, this.mapTime(-1));
        assertEquals(stored, maps.find(-1).join().orElseThrow());
    }

    @Test
    void mapTimeIndexAndPrefixesRemainIndependent() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        MysqlStorageProvider other = this.provider(this.url, "other_" + this.prefix);
        provider.initialize();
        other.initialize();
        StoredMap first = provider.maps().register(new MapSource("source", 0), mapData(1)).join();
        StoredMap second = provider.maps().register(new MapSource("source", 1), mapData(2)).join();
        assertTrue(other.maps().find(first.identity().globalId()).join().isEmpty());
        assertEquals(-1, other.maps().register(first.identity().source(), mapData(3)).join().identity().globalId());
        this.direct.useHandle(handle -> {
            handle.execute("UPDATE `" + this.prefix + "maps` SET updated_at = CASE WHEN global_id = -1 THEN 10 ELSE 20 END");
            assertEquals(List.of(second.identity().globalId()), handle.createQuery("SELECT global_id FROM `" + this.prefix + "maps` FORCE INDEX (map_updated_at) WHERE updated_at >= 20 AND updated_at <= 20").mapTo(Integer.class).list());
        });
        assertEquals(first, provider.maps().find(-1).join().orElseThrow());
    }

    @Test
    void mapPayloadDoesNotUseTheSnapshotFrameLimit() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        CompoundTag tag = mapData(1).getTag();
        tag.putByteArray("extra", new byte[15 * 1024 * 1024]);
        MapData large = new MapData(4440, tag);
        assertTrue(large.encode().length > 15 * 1024 * 1024);
        StoredMap stored = provider.maps().register(new MapSource("large", 0), large).get(15, TimeUnit.SECONDS);
        assertEquals(large, provider.maps().find(stored.identity().globalId()).get(15, TimeUnit.SECONDS).orElseThrow().data());
    }

    @Test
    void pendingSnapshotsRestoreThroughTheMysqlStorageInterface() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        SnapshotStash stash = new SnapshotStash(this.stashDirectory, new BinarySnapshotCodec(CompressorRegistry.DEFLATE), this.logger, new NoopSnapshotCache());
        UUID player = UUID.randomUUID();
        Snapshot old = this.snapshot(player, 10, false);
        Snapshot latest = this.snapshot(player, 20, false);
        provider.saveSnapshot(latest).join();
        stash.stash(old, "Test", SaveResult.RETRY_LATER);
        stash.stash(latest, "Test", SaveResult.RETRY_LATER);
        stash.restorePending(provider);
        assertEquals(latest, provider.latestSnapshot(player).join().orElseThrow());
        assertEquals(List.of(latest.meta(), old.meta()), provider.listSnapshots(player).join());
        try (var files = Files.list(this.stashDirectory.resolve("snapshot/pending"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void savedLateAndDuplicateSnapshotsPreserveTheStoredContent() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        Snapshot first = this.snapshot(player, 10, false);
        Snapshot newest = this.snapshot(player, 30, false);
        Snapshot late = this.snapshot(player, 20, false);
        assertEquals(SaveResult.SAVED, provider.saveSnapshot(first).join());
        assertEquals(SaveResult.SAVED, provider.saveSnapshot(newest).join());
        assertEquals(SaveResult.SAVED_OUT_OF_ORDER, provider.saveSnapshot(late).join());
        assertTrue(provider.setPinned(first.meta().id(), true).join());
        Snapshot replay = new Snapshot(first.meta(), Map.of(DataKey.of("test", "other"), NBT.createString("different")));
        assertEquals(SaveResult.DUPLICATE, provider.saveSnapshot(replay).join());
        assertEquals(new Snapshot(first.meta().withPinned(true), first.allData()), provider.snapshot(first.meta().id()).join().orElseThrow());
        assertEquals(newest, provider.latestSnapshot(player).join().orElseThrow());
        assertEquals(3, provider.listSnapshots(SnapshotQuery.of(player)).join().size());
    }

    @Test
    void reversedEncodingCompletionPreservesSaveAndRotationOrder() throws Exception {
        ArrayDeque<Runnable> encodings = new ArrayDeque<>();
        MysqlStorageProvider provider = this.provider(this.url, this.prefix, encodings::addLast);
        provider.initialize();
        UUID player = UUID.randomUUID();
        Snapshot first = this.snapshot(player, 10, false);
        Snapshot second = this.snapshot(player, 20, false);
        CompletableFuture<SaveResult> firstSave = provider.saveSnapshot(first);
        CompletableFuture<Integer> rotation = provider.rotate(player, 0);
        CompletableFuture<SaveResult> secondSave = provider.saveSnapshot(second);
        assertEquals(2, encodings.size());
        encodings.removeLast().run();
        assertFalse(secondSave.isDone());
        int before = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM `" + this.prefix + "snapshots`").mapTo(Integer.class).one());
        assertEquals(0, before);
        encodings.removeFirst().run();
        assertEquals(SaveResult.SAVED, firstSave.get(5, TimeUnit.SECONDS));
        assertEquals(1, rotation.get(5, TimeUnit.SECONDS));
        assertEquals(SaveResult.SAVED, secondSave.get(5, TimeUnit.SECONDS));
        List<Long> timestamps = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT ts FROM `" + this.prefix + "snapshots`").mapTo(Long.class).list());
        assertEquals(List.of(20L), timestamps);
    }

    @Test
    void consecutiveSavesStayInSubmissionOrder() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        List<CompletableFuture<SaveResult>> saves = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            saves.add(provider.saveSnapshot(this.snapshot(player, i, false)));
        }
        CompletableFuture.allOf(saves.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        for (int i = 0; i < saves.size(); i++) {
            assertEquals(SaveResult.SAVED, saves.get(i).join());
        }
        assertEquals(20, provider.latestSnapshot(player).join().orElseThrow().meta().timestamp());
    }

    @Test
    void foreignUniqueConstraintIsNotReportedAsDuplicate() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        Snapshot first = this.snapshot(player, 10, false);
        Snapshot second = this.snapshot(player, 20, false);
        assertEquals(SaveResult.SAVED, provider.saveSnapshot(first).join());
        provider.jdbi().useHandle(handle -> handle.execute("CREATE UNIQUE INDEX external_server ON `" + this.prefix + "snapshots` (server)"));
        assertEquals(SaveResult.REJECTED_MALFORMED, provider.saveSnapshot(second).join());
        assertEquals(Optional.empty(), provider.snapshot(second.meta().id()).join());
        assertEquals(SaveResult.DUPLICATE, provider.saveSnapshot(first).join());
    }

    @Test
    void lockTimeoutKeepsTheSqlFailureAndCanRetry() throws Exception {
        MysqlStorageProvider provider = this.provider(this.urlWith("sessionVariables=innodb_lock_wait_timeout=1"), this.prefix);
        provider.initialize();
        Snapshot snapshot = this.snapshot(UUID.randomUUID(), 10, false);
        this.insert(provider.jdbi(), this.codec.encode(snapshot));
        try (Handle blocker = this.direct.open()) {
            blocker.begin();
            blocker.createUpdate("DELETE FROM `" + this.prefix + "snapshots` WHERE id = :id").bind("id", UUIDUtils.toBytes(snapshot.meta().id())).execute();
            SaveOutcome blocked = provider.saveSnapshotOutcome(snapshot).get(5, TimeUnit.SECONDS);
            assertEquals(SaveResult.RETRY_LATER, blocked.result());
            assertEquals(1205, MysqlFailureClassifier.sqlCause(blocked.failure()).getErrorCode());
            blocker.commit();
        }
        assertEquals(SaveResult.SAVED, provider.saveSnapshot(snapshot).join());
    }

    @Test
    void retryAfterAnUncertainInsertIsIdempotent() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        Snapshot snapshot = this.snapshot(UUID.randomUUID(), 10, false);
        AtomicBoolean fail = new AtomicBoolean(true);
        SQLTransientConnectionException disconnected = new SQLTransientConnectionException("injected lost acknowledgement", "08006");
        provider.jdbi().setSqlLogger(new SqlLogger() {
            @Override
            public void logAfterExecution(StatementContext context) {
                if (context.getRawSql().startsWith("INSERT INTO") && fail.getAndSet(false)) {
                    throw new UnableToExecuteStatementException(disconnected, context);
                }
            }
        });
        SaveOutcome uncertain = provider.saveSnapshotOutcome(snapshot).join();
        assertEquals(SaveResult.RETRY_LATER, uncertain.result());
        assertSame(disconnected, MysqlFailureClassifier.sqlCause(uncertain.failure()));
        assertEquals(snapshot, provider.snapshot(snapshot.meta().id()).join().orElseThrow());
        assertEquals(SaveResult.DUPLICATE, provider.saveSnapshot(snapshot).join());
        assertEquals(1, provider.listSnapshots(SnapshotQuery.of(snapshot.meta().player())).join().size());
    }

    @Test
    void aTemporaryOrderCheckFailureKeepsTheCommittedResult() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        Snapshot snapshot = this.snapshot(UUID.randomUUID(), 10, false);
        AtomicBoolean fail = new AtomicBoolean(true);
        int warnings = this.console.warnings.get();
        provider.jdbi().setSqlLogger(new SqlLogger() {
            @Override
            public void logBeforeExecution(StatementContext context) {
                if (context.getRawSql().startsWith("SELECT") && context.getRawSql().contains("ORDER BY") && fail.getAndSet(false)) {
                    throw new UnableToExecuteStatementException(new SQLTransientConnectionException("injected diagnostic failure", "08006"), context);
                }
            }
        });
        assertEquals(new SaveOutcome(SaveResult.SAVED, null), provider.saveSnapshotOutcome(snapshot).join());
        assertEquals(warnings + 1, this.console.warnings.get());
        assertEquals(snapshot, provider.snapshot(snapshot.meta().id()).join().orElseThrow());
        assertEquals(SaveResult.DUPLICATE, provider.saveSnapshot(snapshot).join());
    }

    @Test
    void aFailedDuplicateCheckRemainsRetryable() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        Snapshot snapshot = this.snapshot(UUID.randomUUID(), 10, false);
        assertEquals(SaveResult.SAVED, provider.saveSnapshot(snapshot).join());
        AtomicBoolean fail = new AtomicBoolean(true);
        provider.jdbi().setSqlLogger(new SqlLogger() {
            @Override
            public void logBeforeExecution(StatementContext context) {
                if (context.getRawSql().startsWith("SELECT 1 FROM") && fail.getAndSet(false)) {
                    throw new UnableToExecuteStatementException(new SQLTransientConnectionException("injected duplicate check failure", "08006"), context);
                }
            }
        });
        SaveOutcome outcome = provider.saveSnapshotOutcome(snapshot).join();
        assertEquals(SaveResult.RETRY_LATER, outcome.result());
        assertNotNull(outcome.failure());
        assertEquals(SaveResult.DUPLICATE, provider.saveSnapshot(snapshot).join());
    }

    @Test
    void encodingAndSqlConstraintFailuresAreMalformed() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        Snapshot snapshot = this.snapshot(UUID.randomUUID(), 10, false);
        SnapshotMeta meta = snapshot.meta();
        Snapshot tooLong = new Snapshot(new SnapshotMeta(meta.id(), meta.player(), meta.timestamp(), meta.cause(), false, "😀".repeat(256), 4440), snapshot.allData());
        assertEquals(SaveResult.REJECTED_MALFORMED, provider.saveSnapshot(tooLong).join());
        Snapshot badEncoding = new Snapshot(meta, Map.of(DataKey.of("test", "long"), NBT.createString("a".repeat(70_000))));
        assertThrows(IOException.class, () -> this.codec.encode(badEncoding));
        assertEquals(SaveResult.REJECTED_MALFORMED, provider.saveSnapshot(badEncoding).join());
        provider.jdbi().useHandle(handle -> handle.execute("ALTER TABLE `" + this.prefix + "snapshots` MODIFY cause VARCHAR(1) NOT NULL"));
        assertEquals(SaveResult.REJECTED_MALFORMED, provider.saveSnapshot(snapshot).join());
        assertEquals(Optional.empty(), provider.snapshot(meta.id()).join());
    }

    @Test
    void payloadLimitIncludesTheFrameAndAllowsEquality() throws Exception {
        SnapshotDataCodec uncompressedBinary = new SnapshotDataCodec(CompressorRegistry.NONE);
        RowSnapshotCodec uncompressed = new RowSnapshotCodec(uncompressedBinary);
        MysqlStorageProvider provider = this.provider(this.url, this.prefix, uncompressedBinary, ForkJoinPool.commonPool());
        provider.initialize();
        int limit = 15 * 1024 * 1024;
        DataKey key = DataKey.of("test", "blob");
        SnapshotMeta meta = this.snapshot(UUID.randomUUID(), 10, false).meta();
        int overhead = uncompressed.encode(new Snapshot(meta, Map.of(key, NBT.createByteArray(new byte[0])))).data().length;
        Snapshot exact = new Snapshot(meta, Map.of(key, NBT.createByteArray(new byte[limit - overhead])));
        assertEquals(limit, uncompressed.encode(exact).data().length);
        assertEquals(SaveResult.SAVED, provider.saveSnapshot(exact).get(10, TimeUnit.SECONDS));
        int stored = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT OCTET_LENGTH(data) FROM `" + this.prefix + "snapshots`").mapTo(Integer.class).one());
        assertEquals(limit, stored);
        Snapshot oversized = new Snapshot(this.snapshot(meta.player(), 20, false).meta(), Map.of(key, NBT.createByteArray(new byte[limit - overhead + 1])));
        assertEquals(SaveResult.REJECTED_OVERSIZED, provider.saveSnapshot(oversized).get(10, TimeUnit.SECONDS));
        assertEquals(Optional.empty(), provider.snapshot(oversized.meta().id()).join());
    }

    @Test
    void programmingFailuresAreNotConvertedToSaveResults() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        IllegalStateException bug = new IllegalStateException("injected implementation failure");
        provider.jdbi().setSqlLogger(new SqlLogger() {
            @Override
            public void logBeforeExecution(StatementContext context) {
                throw bug;
            }
        });
        CompletionException failure = assertThrows(CompletionException.class, () -> provider.saveSnapshot(this.snapshot(UUID.randomUUID(), 10, false)).join());
        assertSame(bug, failure.getCause());
    }

    @Test
    void rotationUsesTheFullBoundaryAndPreservesPinnedAndForeignRows() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        List<UUID> ids = List.of(UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"), UUID.fromString("80000000-0000-0000-0000-000000000000"), UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        for (int i = 0; i < ids.size(); i++) {
            SnapshotMeta meta = new SnapshotMeta(ids.get(i), player, 20, SaveCause.COMMAND, false, "test", 4440);
            assertEquals(SaveResult.SAVED, provider.saveSnapshot(new Snapshot(meta, Map.of())).join());
        }
        Snapshot pinned = this.snapshot(player, 10, true);
        Snapshot foreign = this.snapshot(UUID.randomUUID(), 5, false);
        provider.saveSnapshot(pinned).join();
        provider.saveSnapshot(foreign).join();
        assertEquals(0, provider.rotate(player, 5).join());
        assertEquals(0, provider.rotate(player, 3).join());
        assertEquals(2, provider.rotate(player, 1).join());
        assertEquals(List.of(ids.get(2), pinned.meta().id()), provider.listSnapshots(SnapshotQuery.of(player)).join().stream().map(SnapshotMeta::id).toList());
        assertEquals(0, provider.rotate(player, 1).join());
        assertEquals(1, provider.rotate(player, 0).join());
        assertEquals(List.of(pinned.meta()), provider.listSnapshots(SnapshotQuery.of(player)).join());
        assertEquals(Optional.of(foreign), provider.snapshot(foreign.meta().id()).join());
        assertEquals(0, provider.rotate(player, -1).join());
    }

    @Test
    void pinAndDeleteResultsDoNotDependOnAffectedRowsMode() throws Exception {
        for (int i = 0; i < 2; i++) {
            MysqlStorageProvider provider = this.provider(this.urlWith("useAffectedRows=" + (i == 1)), this.prefix);
            provider.initialize();
            Snapshot snapshot = this.snapshot(UUID.randomUUID(), 10, false);
            UUID id = snapshot.meta().id();
            assertFalse(provider.setPinned(id, true).join());
            assertFalse(provider.deleteSnapshot(id).join());
            assertEquals(SaveResult.SAVED, provider.saveSnapshot(snapshot).join());
            assertFalse(provider.setPinned(id, false).join());
            assertTrue(provider.setPinned(id, true).join());
            assertFalse(provider.setPinned(id, true).join());
            assertTrue(provider.setPinned(id, false).join());
            assertTrue(provider.deleteSnapshot(id).join());
            assertFalse(provider.deleteSnapshot(id).join());
        }
    }

    @Test
    void queryAndUserOperationsRespectTheTablePrefix() throws Exception {
        MysqlStorageProvider first = this.provider(this.url, this.prefix);
        MysqlStorageProvider second = this.provider(this.url, "other_" + this.prefix);
        first.initialize();
        second.initialize();
        UUID player = UUID.randomUUID();
        Snapshot snapshot = new Snapshot(new SnapshotMeta(UUID.randomUUID(), player, 20, SaveCause.COMMAND, false, "", 4440), Map.of());
        this.insert(first.jdbi(), this.codec.encode(snapshot));
        first.ensureUser(player, "First").join();
        second.ensureUser(player, "Second").join();
        assertEquals(Optional.empty(), second.latestSnapshot(player).join());
        assertEquals(Optional.empty(), second.snapshot(snapshot.meta().id()).join());
        assertEquals(List.of(), second.listSnapshots(SnapshotQuery.of(player)).join());
        assertEquals(Optional.empty(), second.lookupUser("First").join());
        assertEquals(Optional.empty(), first.lookupUser("Second").join());
        assertEquals(Optional.of(player), first.lookupUser("First").join());
        assertEquals(Optional.of(player), second.lookupUser("Second").join());
    }

    @Test
    void closesThePoolAndPreservesMetadataOnReopen() throws Exception {
        List<?> drivers = Collections.list(DriverManager.getDrivers());
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        assertThrows(IllegalStateException.class, provider::jdbi);
        provider.initialize();
        Jdbi connected = provider.jdbi();
        HikariDataSource pool = this.pool(provider);
        connected.useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "meta` SET value = 73 WHERE id = 'maps'"));
        provider.shutdown();
        provider.shutdown();
        assertTrue(pool.isClosed());
        assertThrows(IllegalStateException.class, provider::jdbi);
        assertThrows(Exception.class, () -> connected.withHandle(handle -> handle.createQuery("SELECT 1").mapTo(Integer.class).one()));
        provider.initialize();
        assertEquals(73, this.meta("maps"));
        assertEquals(drivers, Collections.list(DriverManager.getDrivers()));
    }

    @Test
    void concurrentStartupCreatesOneSchema() throws Exception {
        MysqlStorageProvider first = this.provider(this.url, this.prefix);
        MysqlStorageProvider second = this.provider(this.url, this.prefix);
        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture.allOf(CompletableFuture.runAsync(first::initialize, executor), CompletableFuture.runAsync(second::initialize, executor)).get(15, TimeUnit.SECONDS);
        }
        assertEquals(MysqlSchema.CURRENT_VERSION, this.meta("schema"));
        assertEquals(0, this.pendingCount());
    }

    @Test
    void failedInitializationClosesEveryConnection() throws Exception {
        new MysqlSchemaMigrator(this.logger, MysqlSchema.CURRENT_VERSION, MysqlSchema::initialize, List.of()).migrate(this.direct, this.prefix);
        this.direct.useHandle(handle -> handle.createUpdate("UPDATE `" + this.prefix + "meta` SET value = :version WHERE id = 'schema'").bind("version", MysqlSchema.CURRENT_VERSION + 1).execute());
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        assertThrows(IllegalStateException.class, provider::initialize);
        assertThrows(IllegalStateException.class, provider::jdbi);
        int connections = this.admin.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM information_schema.processlist WHERE db = :database")
                .bind("database", this.database).mapTo(Integer.class).one());
        assertEquals(0, connections);
        assertEquals(MysqlSchema.CURRENT_VERSION + 1, this.meta("schema"));
    }

    @Test
    void initialSchemaIncludesRetentionIndexAndPreservesStoredDataOnRestart() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        assertEquals(List.of(LogConstants.STORAGE_MYSQL_SCHEMA_INITIALIZING), List.copyOf(this.console.messages));
        assertEquals(1, this.meta("schema"));
        this.direct.useHandle(handle -> {
            String query = "SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = :table AND index_name = :index ORDER BY seq_in_index";
            assertEquals(List.of("player", "ts", "id"), handle.createQuery(query).bind("table", this.prefix + "snapshots").bind("index", "snapshot_player_time").mapTo(String.class).list());
            assertEquals(List.of("player", "pinned", "ts", "id"), handle.createQuery(query).bind("table", this.prefix + "snapshots").bind("index", "snapshot_player_pin_time").mapTo(String.class).list());
        });
        Snapshot snapshot = this.snapshot(UUID.randomUUID(), 10, true);
        assertEquals(SaveResult.SAVED, provider.saveSnapshot(snapshot).join());
        provider.ensureUser(snapshot.meta().player(), "Preserved").join();
        StoredMap map = provider.maps().register(new MapSource("preserved", 0), mapData(1)).join();
        String expected = this.direct.withHandle(handle -> handle.createQuery("SHOW CREATE TABLE `" + this.prefix + "snapshots`").map((result, context) -> result.getString(2)).one());
        provider.shutdown();
        this.console.messages.clear();
        provider.initialize();
        assertTrue(this.console.messages.isEmpty());
        assertEquals(1, this.meta("schema"));
        assertEquals(0, this.pendingCount());
        assertEquals(1, this.meta("maps"));
        assertEquals(Optional.of(snapshot), provider.snapshot(snapshot.meta().id()).join());
        assertEquals(Optional.of(snapshot.meta().player()), provider.lookupUser("Preserved").join());
        assertEquals(Optional.of(map), provider.maps().find(map.identity().globalId()).join());
        assertEquals(expected, this.direct.withHandle(handle -> handle.createQuery("SHOW CREATE TABLE `" + this.prefix + "snapshots`").map((result, context) -> result.getString(2)).one()));
        assertEquals(List.of(snapshot.meta()), provider.listSnapshots(SnapshotQuery.of(snapshot.meta().player())).join());
    }

    @Test
    void longDdlAndConcurrentStartupLogMigrationOnceAndRestoreNetworkTimeouts() throws Exception {
        MysqlStorageProvider provider = this.provider(this.urlWith("socketTimeout=750"), this.prefix);
        provider.initialize();
        this.console.messages.clear();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<JdbcConnection> physical = new AtomicReference<>();
        MysqlSchemaMigration slow = new MysqlSchemaMigration() {
            @Override
            public int targetVersion() {
                return 2;
            }

            @Override
            public void migrate(@NonNull Handle handle, @NonNull String prefix) {
                runs.incrementAndGet();
                physical.set(assertDoesNotThrow(() -> handle.getConnection().unwrap(JdbcConnection.class)));
                int timeout = assertDoesNotThrow(() -> handle.getConnection().getNetworkTimeout());
                assertEquals(1_800_000, timeout);
                assertEquals(List.of(LogConstants.STORAGE_MYSQL_SCHEMA_MIGRATING), List.copyOf(MysqlStorageProviderTest.this.console.messages));
                entered.countDown();
                handle.execute("ALTER TABLE `" + prefix + "snapshots` ADD INDEX test_migration_time (ts), ALGORITHM=INPLACE, LOCK=NONE");
            }
        };
        MysqlSchemaMigrator migrator = new MysqlSchemaMigrator(this.logger, 2, MysqlSchema::initialize, List.of(slow));
        try (var executor = Executors.newFixedThreadPool(2); Handle blocker = this.direct.open()) {
            blocker.begin();
            blocker.createQuery("SELECT COUNT(*) FROM `" + this.prefix + "snapshots`").mapTo(Integer.class).one();
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> migrator.migrate(provider.jdbi(), this.prefix), executor);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> migrator.migrate(provider.jdbi(), this.prefix), executor);
            try {
                Thread.sleep(6_000);
                assertFalse(first.isDone());
                assertFalse(second.isDone());
            } finally {
                blocker.commit();
            }
            CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, runs.get());
        assertEquals(2, this.meta("schema"));
        assertEquals(0, this.pendingCount());
        assertEquals(750, physical.get().getNetworkTimeout());
        assertEquals(List.of(LogConstants.STORAGE_MYSQL_SCHEMA_MIGRATING), List.copyOf(this.console.messages));
    }

    @Test
    void failedMigrationRestoresTimeoutAndLogsResumedStep() throws Exception {
        MysqlStorageProvider provider = this.provider(this.urlWith("socketTimeout=500"), this.prefix);
        provider.initialize();
        this.console.messages.clear();
        AtomicReference<JdbcConnection> physical = new AtomicReference<>();
        AtomicBoolean failAfterDdl = new AtomicBoolean(true);
        MysqlSchemaMigration interrupted = new MysqlSchemaMigration() {
            @Override
            public int targetVersion() {
                return 2;
            }

            @Override
            public void migrate(@NonNull Handle handle, @NonNull String prefix) {
                physical.set(assertDoesNotThrow(() -> handle.getConnection().unwrap(JdbcConnection.class)));
                handle.execute("CREATE TABLE IF NOT EXISTS `" + prefix + "migration_probe` (id INT PRIMARY KEY)");
                if (failAfterDdl.getAndSet(false)) {
                    handle.createQuery("SELECT SLEEP(1)").mapTo(Integer.class).one();
                    throw new IllegalStateException("injected failure after DDL");
                }
            }
        };
        MysqlSchemaMigrator migrator = new MysqlSchemaMigrator(this.logger, 2, MysqlSchema::initialize, List.of(interrupted));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> migrator.migrate(provider.jdbi(), this.prefix));
        assertTrue(failure.getMessage().contains("injected failure"));
        assertEquals(1, this.meta("schema"));
        assertEquals(2, this.meta("schema_pending"));
        assertTrue(this.tableExists(this.prefix + "migration_probe"));
        assertEquals(500, physical.get().getNetworkTimeout());
        assertEquals(List.of(LogConstants.STORAGE_MYSQL_SCHEMA_MIGRATING), List.copyOf(this.console.messages));
        this.direct.useHandle(handle -> {
            String lock = handle.createQuery("SELECT SHA2(CONCAT('sparrow-sync-schema:', DATABASE(), ':', :prefix), 256)").bind("prefix", this.prefix).mapTo(String.class).one();
            assertEquals(1, handle.createQuery("SELECT GET_LOCK(:name, 0)").bind("name", lock).mapTo(Integer.class).one());
            handle.createQuery("SELECT RELEASE_LOCK(:name)").bind("name", lock).mapTo(Integer.class).one();
        });
        migrator.migrate(provider.jdbi(), this.prefix);
        assertEquals(2, this.meta("schema"));
        assertEquals(0, this.pendingCount());
        assertEquals(List.of(LogConstants.STORAGE_MYSQL_SCHEMA_MIGRATING, LogConstants.STORAGE_MYSQL_SCHEMA_MIGRATING), List.copyOf(this.console.messages));
    }

    @Test
    void partialInitialDdlCanResumeWithoutLosingData() {
        BiConsumer<Handle, String> interrupted = (handle, prefix) -> {
            MysqlSchema.initialize(handle, prefix);
            handle.execute("UPDATE `" + prefix + "meta` SET value = 97 WHERE id = 'maps'");
            throw new IllegalStateException("injected failure after initial DDL");
        };
        assertThrows(IllegalStateException.class, () -> new MysqlSchemaMigrator(this.logger, MysqlSchema.CURRENT_VERSION, interrupted, List.of()).migrate(this.direct, this.prefix));
        assertEquals(MysqlSchema.CURRENT_VERSION, this.meta("schema_pending"));
        new MysqlSchemaMigrator(this.logger, MysqlSchema.CURRENT_VERSION, MysqlSchema::initialize, List.of()).migrate(this.direct, this.prefix);
        assertEquals(MysqlSchema.CURRENT_VERSION, this.meta("schema"));
        assertEquals(97, this.meta("maps"));
        assertEquals(0, this.pendingCount());
    }

    @Test
    void wholeTableRebuildResumesAfterRenameAndRejectsOlderCode() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        provider.jdbi().useHandle(handle -> handle.createUpdate("INSERT INTO `" + this.prefix + "users` (player, name, last_seen) VALUES (:player, 'MiXeD', 123)").bind("player", player).execute());
        AtomicBoolean failAfterRename = new AtomicBoolean(true);
        MysqlSchemaMigration rebuild = this.rebuildUsers(failAfterRename);
        MysqlSchemaMigrator newer = new MysqlSchemaMigrator(this.logger, 2, (handle, prefix) -> fail("Existing schemas must use migrations"), List.of(rebuild));
        assertThrows(IllegalStateException.class, () -> newer.migrate(provider.jdbi(), this.prefix));
        assertEquals(1, this.meta("schema"));
        assertEquals(2, this.meta("schema_pending"));
        assertTrue(this.tableExists(this.prefix + "users_old"));
        assertThrows(IllegalStateException.class, () -> new MysqlSchemaMigrator(this.logger, 1, MysqlSchema::initialize, List.of()).migrate(this.direct, this.prefix));
        newer.migrate(this.direct, this.prefix);
        assertEquals(2, this.meta("schema"));
        assertEquals(0, this.pendingCount());
        assertFalse(this.tableExists(this.prefix + "users_old"));
        assertFalse(this.tableExists(this.prefix + "users_new"));
        this.direct.useHandle(handle -> {
            Map<String, Object> user = handle.createQuery("SELECT HEX(player) AS player, name, last_seen FROM `" + this.prefix + "users`").mapToMap().one();
            assertEquals(player.toString().replace("-", "").toUpperCase(), user.get("player"));
            assertEquals("MiXeD_v2", user.get("name"));
            assertEquals(123L, user.get("last_seen"));
        });
    }

    @Test
    void migrationLockSurvivesDdlAndSeparatesPrefixes() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        MysqlSchemaMigration migration = new MysqlSchemaMigration() {
            @Override
            public int targetVersion() {
                return 2;
            }

            @Override
            public void migrate(@NonNull Handle handle, @NonNull String prefix) {
                runs.incrementAndGet();
                handle.execute("ALTER TABLE `" + prefix + "users` ADD COLUMN extra INT NOT NULL DEFAULT 0");
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("migration was not released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        };
        MysqlSchemaMigrator migrator = new MysqlSchemaMigrator(this.logger, 2, (handle, prefix) -> fail("Existing schemas must use migrations"), List.of(migration));
        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> migrator.migrate(provider.jdbi(), this.prefix), executor);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                CompletableFuture<Void> second = CompletableFuture.runAsync(() -> migrator.migrate(this.direct, this.prefix), executor);
                new MysqlSchemaMigrator(this.logger, MysqlSchema.CURRENT_VERSION, MysqlSchema::initialize, List.of()).migrate(this.direct, "other_" + this.prefix);
                assertFalse(second.isDone());
                release.countDown();
                CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
                assertEquals(1, runs.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rejectsUnversionedTablesAndInvalidMigrationSequences() {
        this.direct.useHandle(handle -> handle.execute("CREATE TABLE `" + this.prefix + "users` (name VARCHAR(16))"));
        assertThrows(IllegalStateException.class, () -> new MysqlSchemaMigrator(this.logger, MysqlSchema.CURRENT_VERSION, MysqlSchema::initialize, List.of()).migrate(this.direct, this.prefix));
        assertEquals(0, this.pendingCount());
        MysqlSchemaMigration second = this.rebuildUsers(new AtomicBoolean());
        assertThrows(IllegalArgumentException.class, () -> new MysqlSchemaMigrator(this.logger, 0, MysqlSchema::initialize, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MysqlSchemaMigrator(this.logger, 3, MysqlSchema::initialize, List.of(second, second)));
        assertThrows(IllegalArgumentException.class, () -> new MysqlSchemaMigrator(this.logger, 3, MysqlSchema::initialize, List.of(second)));
        assertThrows(IllegalArgumentException.class, () -> new MysqlSchemaMigrator(this.logger, 1, MysqlSchema::initialize, List.of(second)));
        List<MysqlSchemaMigration> reversed = new ArrayList<>(this.userMigrations(3, new ArrayList<>()));
        Collections.reverse(reversed);
        assertThrows(IllegalArgumentException.class, () -> new MysqlSchemaMigrator(this.logger, 3, MysqlSchema::initialize, reversed));
    }

    @Test
    void freshAndUpgradedDatabasesReachTheSameLatestSchema() {
        List<Integer> applied = new ArrayList<>();
        AtomicInteger initializations = new AtomicInteger();
        List<MysqlSchemaMigration> migrations = this.userMigrations(10, applied);
        MysqlSchemaMigrator latest = new MysqlSchemaMigrator(this.logger, 10, (handle, prefix) -> {
            assertEquals(10L, handle.createQuery("SELECT value FROM `" + prefix + "meta` WHERE id = 'schema_pending'").mapTo(Long.class).one());
            initializations.incrementAndGet();
            this.initializeUsers(handle, prefix, 10);
        }, migrations);
        latest.migrate(this.direct, this.prefix);
        assertEquals(10, this.meta("schema"));
        assertEquals(0, this.pendingCount());
        assertTrue(applied.isEmpty());

        String olderPrefix = "old_" + this.prefix;
        new MysqlSchemaMigrator(this.logger, 7, (handle, prefix) -> this.initializeUsers(handle, prefix, 7), migrations.subList(0, 6)).migrate(this.direct, olderPrefix);
        this.direct.useHandle(handle -> {
            handle.execute("INSERT INTO `" + olderPrefix + "users` (player, name) VALUES (42, 'MiXeD')");
            handle.execute("INSERT INTO `" + olderPrefix + "meta` (id, value) VALUES ('maps', 73)");
        });
        latest.migrate(this.direct, olderPrefix);
        assertEquals(List.of(8, 9, 10), applied);
        assertEquals(1, initializations.get());
        this.direct.useHandle(handle -> {
            assertEquals(10L, handle.createQuery("SELECT value FROM `" + olderPrefix + "meta` WHERE id = 'schema'").mapTo(Long.class).one());
            assertEquals(0, handle.createQuery("SELECT COUNT(*) FROM `" + olderPrefix + "meta` WHERE id = 'schema_pending'").mapTo(Integer.class).one());
            assertEquals(73L, handle.createQuery("SELECT value FROM `" + olderPrefix + "meta` WHERE id = 'maps'").mapTo(Long.class).one());
            assertEquals("MiXeD", handle.createQuery("SELECT name FROM `" + olderPrefix + "users` WHERE player = 42").mapTo(String.class).one());
            String freshDdl = handle.createQuery("SHOW CREATE TABLE `" + this.prefix + "users`").map((result, context) -> result.getString(2)).one();
            String upgradedDdl = handle.createQuery("SHOW CREATE TABLE `" + olderPrefix + "users`").map((result, context) -> result.getString(2)).one();
            assertEquals(freshDdl, upgradedDdl.replace(olderPrefix, this.prefix));
        });
        latest.migrate(this.direct, this.prefix);
        latest.migrate(this.direct, olderPrefix);
        assertEquals(1, initializations.get());
        assertEquals(List.of(8, 9, 10), applied);
    }

    @Test
    void interruptedLatestInitializationRequiresTheSameTargetVersion() {
        List<Integer> applied = new ArrayList<>();
        List<MysqlSchemaMigration> migrations = this.userMigrations(10, applied);
        AtomicBoolean failBeforeMaps = new AtomicBoolean(true);
        MysqlSchemaMigrator interrupted = new MysqlSchemaMigrator(this.logger, 10, (handle, prefix) -> {
            this.initializeUsers(handle, prefix, 10);
            if (failBeforeMaps.getAndSet(false)) {
                handle.execute("INSERT INTO `" + prefix + "users` (player, name) VALUES (42, 'preserved')");
                throw new IllegalStateException("injected failure during V10 initialization");
            }
            handle.execute("CREATE TABLE IF NOT EXISTS `" + prefix + "maps` (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        }, migrations);
        assertThrows(IllegalStateException.class, () -> interrupted.migrate(this.direct, this.prefix));
        assertEquals(10, this.meta("schema_pending"));
        assertFalse(this.tableExists(this.prefix + "maps"));
        int publishedVersions = this.direct.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM `" + this.prefix + "meta` WHERE id = 'schema'").mapTo(Integer.class).one());
        assertEquals(0, publishedVersions);
        int[] otherVersions = {9, 11};
        for (int i = 0; i < otherVersions.length; i++) {
            int version = otherVersions[i];
            MysqlSchemaMigrator mismatched = new MysqlSchemaMigrator(this.logger, version, (handle, prefix) -> fail("A different initialization version must be rejected"), this.userMigrations(version, applied));
            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> mismatched.migrate(this.direct, this.prefix));
            assertTrue(exception.getMessage().contains("initialization targets version 10"));
            assertEquals(10, this.meta("schema_pending"));
        }
        interrupted.migrate(this.direct, this.prefix);
        assertEquals(10, this.meta("schema"));
        assertEquals(0, this.pendingCount());
        assertTrue(this.tableExists(this.prefix + "maps"));
        assertTrue(applied.isEmpty());
        assertEquals("preserved", this.direct.withHandle(handle -> handle.createQuery("SELECT name FROM `" + this.prefix + "users` WHERE player = 42").mapTo(String.class).one()));
    }

    @Test
    void rejectsPendingMigrationsThatDoNotFollowTheStoredVersion() {
        List<Integer> applied = new ArrayList<>();
        new MysqlSchemaMigrator(this.logger, 7, (handle, prefix) -> this.initializeUsers(handle, prefix, 7), this.userMigrations(7, applied)).migrate(this.direct, this.prefix);
        MysqlSchemaMigrator latest = new MysqlSchemaMigrator(this.logger, 10, (handle, prefix) -> fail("Existing schemas must use migrations"), this.userMigrations(10, applied));
        int[] invalidTargets = {0, 7, 9, 11};
        for (int i = 0; i < invalidTargets.length; i++) {
            int pending = invalidTargets[i];
            this.direct.useHandle(handle -> handle.createUpdate("INSERT INTO `" + this.prefix + "meta` (id, value) VALUES ('schema_pending', :version) ON DUPLICATE KEY UPDATE value = :version").bind("version", pending).execute());
            assertThrows(IllegalStateException.class, () -> latest.migrate(this.direct, this.prefix));
            assertEquals(7, this.meta("schema"));
            assertEquals(pending, this.meta("schema_pending"));
        }
        assertTrue(applied.isEmpty());
    }

    private void initializeUsers(Handle handle, String prefix, int version) {
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS `" + prefix + "users` (player INT NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL");
        for (int target = 2; target <= version; target++) {
            ddl.append(", revision_").append(target).append(" INT NOT NULL DEFAULT 0");
        }
        handle.execute(ddl.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin").toString());
    }

    private List<MysqlSchemaMigration> userMigrations(int version, List<Integer> applied) {
        List<MysqlSchemaMigration> migrations = new ArrayList<>();
        for (int target = 2; target <= version; target++) {
            int next = target;
            migrations.add(new MysqlSchemaMigration() {
                @Override
                public int targetVersion() {
                    return next;
                }

                @Override
                public void migrate(@NonNull Handle handle, @NonNull String prefix) {
                    assertEquals(next - 1L, handle.createQuery("SELECT value FROM `" + prefix + "meta` WHERE id = 'schema'").mapTo(Long.class).one());
                    assertEquals((long) next, handle.createQuery("SELECT value FROM `" + prefix + "meta` WHERE id = 'schema_pending'").mapTo(Long.class).one());
                    handle.execute("ALTER TABLE `" + prefix + "users` ADD COLUMN revision_" + next + " INT NOT NULL DEFAULT 0");
                    applied.add(next);
                }
            });
        }
        return migrations;
    }

    private MysqlSchemaMigration rebuildUsers(AtomicBoolean failAfterRename) {
        return new MysqlSchemaMigration() {
            @Override
            public int targetVersion() {
                return 2;
            }

            @Override
            public void migrate(@NonNull Handle handle, @NonNull String prefix) {
                boolean renamed = handle.createQuery("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = :table AND column_name = 'name'")
                        .bind("table", prefix + "users").mapTo(Integer.class).one() == 128;
                if (!renamed) {
                    handle.execute("CREATE TABLE IF NOT EXISTS `" + prefix + "users_new` (player BINARY(16) NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, last_seen BIGINT NOT NULL) ENGINE=InnoDB");
                    handle.execute("INSERT INTO `" + prefix + "users_new` SELECT player, CONCAT(name, '_v2'), last_seen FROM `" + prefix + "users` ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = VALUES(last_seen)");
                    handle.execute("RENAME TABLE `" + prefix + "users` TO `" + prefix + "users_old`, `" + prefix + "users_new` TO `" + prefix + "users`");
                    if (failAfterRename.getAndSet(false)) throw new IllegalStateException("injected failure after table swap");
                }
                handle.execute("DROP TABLE IF EXISTS `" + prefix + "users_old`");
            }
        };
    }

    private MysqlStorageProvider provider(String url, String prefix) throws Exception {
        return this.provider(url, prefix, ForkJoinPool.commonPool());
    }

    private MysqlStorageProvider provider(String url, String prefix, Executor executor) throws Exception {
        return this.provider(url, prefix, this.binary, executor);
    }

    private MysqlStorageProvider provider(String url, String prefix, SnapshotDataCodec codec, Executor executor) throws Exception {
        PluginConfig.MysqlOptions options = new PluginConfig.MysqlOptions();
        for (Map.Entry<String, String> entry : Map.of("url", url, "username", this.username, "password", this.password, "tablePrefix", prefix).entrySet()) {
            Field field = PluginConfig.MysqlOptions.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(options, entry.getValue());
        }
        MysqlStorageProvider provider = new MysqlStorageProvider(options, codec, this.serialExecutor, executor, this.logger);
        this.providers.add(provider);
        return provider;
    }

    private HikariDataSource pool(MysqlStorageProvider provider) throws Exception {
        Field field = MysqlStorageProvider.class.getDeclaredField("dataSource");
        field.setAccessible(true);
        return (HikariDataSource) field.get(provider);
    }

    private void insert(Jdbi jdbi, SnapshotRow row) {
        SnapshotMeta meta = row.meta();
        jdbi.useHandle(handle -> handle.createUpdate("INSERT INTO `" + this.prefix + "snapshots` (id, player, ts, cause, pinned, server, format, mc_data, data) VALUES (:id, :player, :ts, :cause, :pinned, :server, :format, :mcData, :data)")
                .bind("id", meta.id()).bind("player", meta.player()).bind("ts", meta.timestamp()).bind("cause", meta.cause().name())
                .bind("pinned", meta.pinned()).bind("server", meta.server()).bind("format", row.format()).bind("mcData", meta.mcDataVersion()).bind("data", row.data()).execute());
    }

    private long meta(String key) {
        return this.direct.withHandle(handle -> handle.createQuery("SELECT value FROM `" + this.prefix + "meta` WHERE id = :id").bind("id", key).mapTo(Long.class).one());
    }

    private int pendingCount() {
        return this.direct.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM `" + this.prefix + "meta` WHERE id = 'schema_pending'").mapTo(Integer.class).one());
    }

    private boolean tableExists(String table) {
        return this.direct.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = :table").bind("table", table).mapTo(Integer.class).one()) != 0;
    }

    private String urlWith(String properties) {
        return this.url + (this.url.contains("?") ? "&" : "?") + properties;
    }

    private long mapTime(int globalId) {
        return this.direct.withHandle(handle -> handle.createQuery("SELECT updated_at FROM `" + this.prefix + "maps` WHERE global_id = :id").bind("id", globalId).mapTo(Long.class).one());
    }

    private static MapData mapData(int color) {
        CompoundTag tag = NBT.createCompound();
        tag.putString("dimension", "minecraft:overworld");
        byte[] colors = new byte[MapData.PIXEL_COUNT];
        colors[0] = (byte) color;
        tag.putByteArray("colors", colors);
        return new MapData(4440, tag);
    }

    private Snapshot snapshot(UUID player, long timestamp, boolean pinned) {
        return new Snapshot(new SnapshotMeta(UUID.randomUUID(), player, timestamp, SaveCause.COMMAND, pinned, "test", 4440), Map.of(DataKey.of("test", "value"), NBT.createLong(timestamp)));
    }

    private static final class QuietLogger implements PluginLogger {
        private final AtomicInteger warnings = new AtomicInteger();
        private final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();

        @Override public void info(String message) { this.messages.add(message); }
        @Override public void warn(String message) { this.warnings.incrementAndGet(); }
        @Override public void warn(String message, Throwable failure) { this.warnings.incrementAndGet(); }
        @Override public void error(String message) {}
        @Override public void error(String message, Throwable failure) {}
    }
}
