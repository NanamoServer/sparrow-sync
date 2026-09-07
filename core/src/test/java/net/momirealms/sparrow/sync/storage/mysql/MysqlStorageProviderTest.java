package net.momirealms.sparrow.sync.storage.mysql;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.jdbc.JdbcConnection;
import com.zaxxer.hikari.HikariDataSource;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.RowSnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.storage.mysql.upgrade.MysqlSchemaMigration;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Field;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在真实 MySQL 上验证连接池、表结构迁移和快照行映射.
 * 通过环境变量选择测试服务器, 每轮测试创建独立数据库, 每个用例使用独立表前缀.
 */
@EnabledIfEnvironmentVariable(named = "SPARROW_TEST_MYSQL_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MysqlStorageProviderTest {
    private final List<MysqlStorageProvider> providers = new ArrayList<>(); // 当前用例需要关闭的实例
    private final RowSnapshotCodec codec = new RowSnapshotCodec(new BinarySnapshotCodec(CompressorRegistry.DEFLATE)); // 真实行写入前后的编码对照
    private Jdbi admin; // 创建和删除临时数据库的入口
    private Jdbi direct; // 绕过被测连接池检查数据库状态的入口
    private String database; // 本轮创建的独立测试数据库名
    private String url; // 已经选定测试数据库的连接地址
    private String prefix; // 当前用例独占的业务表前缀
    private String username; // 从测试环境读取的数据库账号
    private String password; // 从测试环境读取的认证密码

    /**
     * 读取测试连接参数并创建本轮专用数据库.
     */
    @BeforeAll
    void connect() {
        String configuredUrl = System.getenv("SPARROW_TEST_MYSQL_URL");
        this.username = System.getenv().getOrDefault("SPARROW_TEST_MYSQL_USERNAME", "root");
        this.password = System.getenv().getOrDefault("SPARROW_TEST_MYSQL_PASSWORD", "");
        this.admin = Jdbi.create(configuredUrl, this.username, this.password);
        // 本轮数据全部写入随机命名的测试库, 清理由 AfterAll 执行.
        this.database = "sparrow_mysql_it_" + UUID.randomUUID().toString().replace("-", "");
        this.admin.useHandle(handle -> handle.execute("CREATE DATABASE `" + this.database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"));
        // 保留环境提供的地址和驱动参数, 将选中的数据库替换为测试库.
        int query = configuredUrl.indexOf('?');
        String address = query < 0 ? configuredUrl : configuredUrl.substring(0, query);
        String options = query < 0 ? "" : configuredUrl.substring(query);
        this.url = address.substring(0, address.lastIndexOf('/') + 1) + this.database + options;
        this.direct = Jdbi.create(this.url, this.username, this.password);
        System.out.println("MySQL integration server: " + this.direct.withHandle(handle -> handle.createQuery("SELECT VERSION()").mapTo(String.class).one()));
    }

    /**
     * 为当前用例分配独立表前缀, 将并发与故障注入限制在用例自己的表内.
     */
    @BeforeEach
    void prepare() {
        this.prefix = "it_" + UUID.randomUUID().toString().replace("-", "") + "_";
    }

    /**
     * 关闭当前用例登记的所有连接池, 包括初始化失败的实例.
     */
    @AfterEach
    void closeProviders() {
        for (int i = 0; i < this.providers.size(); i++) this.providers.get(i).shutdown();
        this.providers.clear();
    }

    /**
     * 删除本轮创建的测试数据库, 清理各用例留下的表和迁移中间状态.
     */
    @AfterAll
    void dropDatabase() {
        if (this.database != null) {
            this.admin.useHandle(handle -> handle.execute("DROP DATABASE `" + this.database + "`"));
        }
    }

    /**
     * 验证初始四张表、索引和元记录, 并让快照经过真实 JDBC 写入和读取.
     *
     * @throws Exception 当配置注入、快照编码或数据库操作失败时
     */
    @Test
    void createsFourTablesAndRoundTripsTheBinaryRow() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        Snapshot snapshot = new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1756300000001L, SaveCause.COMMAND, true, "大厅-É😀", 4440),
                Map.of(DataKey.of("external", "data"), NBT.createLongArray(new long[]{Long.MIN_VALUE, 42})));
        // 通过正式类型绑定和行映射走一次数据库往返.
        SnapshotRow row = this.codec.encode(snapshot);
        this.insert(provider.jdbi(), row);
        SnapshotRow restored = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT * FROM `" + this.prefix + "snapshots` WHERE id = :id")
                .bind("id", snapshot.meta().id()).mapTo(SnapshotRow.class).one());
        assertEquals(snapshot, assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(restored)).snapshot());
        // 检查初始业务表、元记录和用于地图时间筛选的列.
        assertEquals(Set.of(this.prefix + "meta", this.prefix + "maps", this.prefix + "snapshots", this.prefix + "users"),
                Set.copyOf(provider.jdbi().withHandle(handle -> handle.createQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND LEFT(table_name, :length) = :prefix")
                        .bind("length", this.prefix.length()).bind("prefix", this.prefix).mapTo(String.class).list())));
        assertEquals(1, this.meta("schema"));
        assertEquals(0, this.meta("maps"));
        assertEquals(List.of("PRIMARY"), provider.jdbi().withHandle(handle -> handle.createQuery("SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = :table")
                .bind("table", this.prefix + "meta").mapTo(String.class).list()));
        assertEquals("bigint", provider.jdbi().withHandle(handle -> handle.createQuery("SELECT data_type FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = :table AND column_name = 'updated_at'")
                .bind("table", this.prefix + "maps").mapTo(String.class).one()));
    }

    /**
     * 验证独立元信息投影可以读取含未知格式和损坏数据帧的记录.
     *
     * @throws Exception 当测试配置注入或数据库操作失败时
     */
    @Test
    void metadataProjectionDoesNotReadFormatOrPayload() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        SnapshotMeta meta = new SnapshotMeta(UUID.fromString("fedcba98-7654-3210-0123-456789abcdef"), UUID.randomUUID(), 123456789L, SaveCause.UNKNOWN, false, "", 4440);
        this.insert(provider.jdbi(), new SnapshotRow(meta, 99, new byte[]{0}));
        SnapshotMeta found = provider.jdbi().withHandle(handle -> handle.createQuery("SELECT id, player, ts, cause, pinned, server, mc_data FROM `" + this.prefix + "snapshots`")
                .mapTo(SnapshotMeta.class).one());
        assertEquals(meta, found);
        assertEquals("FEDCBA98765432100123456789ABCDEF", provider.jdbi().withHandle(handle -> handle.createQuery("SELECT HEX(id) FROM `" + this.prefix + "snapshots`").mapTo(String.class).one()));
    }

    /**
     * 验证关闭后的入口失效, 重开保留计数器, 且全局驱动注册表保持稳定.
     *
     * @throws Exception 当测试配置注入、连接池检查或数据库操作失败时
     */
    @Test
    void closesThePoolAndPreservesMetadataOnReopen() throws Exception {
        // 记录进程级驱动状态, 与关闭后重开的状态比较.
        List<?> drivers = Collections.list(DriverManager.getDrivers());
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        assertThrows(IllegalStateException.class, provider::jdbi);
        provider.initialize();
        Jdbi connected = provider.jdbi();
        HikariDataSource pool = this.pool(provider);
        connected.useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "meta` SET value = 73 WHERE id = 'maps'"));
        // 重复关闭应安全, 已持有的查询入口应随连接池失效.
        provider.shutdown();
        provider.shutdown();
        assertTrue(pool.isClosed());
        assertThrows(IllegalStateException.class, provider::jdbi);
        assertThrows(Exception.class, () -> connected.withHandle(handle -> handle.createQuery("SELECT 1").mapTo(Integer.class).one()));
        provider.initialize();
        assertEquals(73, this.meta("maps"));
        assertEquals(drivers, Collections.list(DriverManager.getDrivers()));
    }

    /**
     * 验证两个实例同时初始化相同前缀时能依次完成同一代表结构准备.
     *
     * @throws Exception 当配置注入、并发初始化或等待失败时
     */
    @Test
    void concurrentStartupCreatesOneSchema() throws Exception {
        MysqlStorageProvider first = this.provider(this.url, this.prefix);
        MysqlStorageProvider second = this.provider(this.url, this.prefix);
        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture.allOf(CompletableFuture.runAsync(first::initialize, executor), CompletableFuture.runAsync(second::initialize, executor)).get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, this.meta("schema"));
        assertEquals(0, this.pendingCount());
    }

    /**
     * 验证遇到高于代码支持范围的表版本时关闭所有初始化连接.
     *
     * @throws Exception 当测试配置注入或数据库操作失败时
     */
    @Test
    void failedInitializationClosesEveryConnection() throws Exception {
        new MysqlSchemaMigrator(MysqlSchema.CURRENT_VERSION, MysqlSchema::initialize, List.of()).migrate(this.direct, this.prefix);
        this.direct.useHandle(handle -> handle.execute("UPDATE `" + this.prefix + "meta` SET value = 2 WHERE id = 'schema'"));
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        assertThrows(IllegalStateException.class, provider::initialize);
        assertThrows(IllegalStateException.class, provider::jdbi);
        int connections = this.admin.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM information_schema.processlist WHERE db = :database")
                .bind("database", this.database).mapTo(Integer.class).one());
        assertEquals(0, connections);
        assertEquals(2, this.meta("schema"));
    }

    /**
     * 验证不满足标识符或传输要求的连接配置在业务表创建前失败.
     *
     * @throws Exception 当测试配置注入失败时
     */
    @Test
    void rejectsInvalidOptionsBeforeCreatingTables() throws Exception {
        assertThrows(IllegalArgumentException.class, this.provider(this.url, "bad-prefix")::initialize);
        assertThrows(IllegalArgumentException.class, this.provider(this.url, "a".repeat(53))::initialize);
        assertThrows(IllegalArgumentException.class, this.provider("jdbc:postgresql://localhost/test", this.prefix)::initialize);
        assertThrows(IllegalStateException.class, this.provider(this.urlWith("maxAllowedPacket=1048576"), this.prefix)::initialize);
        assertThrows(IllegalStateException.class, this.provider(this.urlWith("autoReconnect=true"), this.prefix)::initialize);
    }

    /**
     * 验证 URL 的超时参数生效, 且池中连接启用严格写入模式.
     *
     * @throws Exception 当测试配置注入或数据库操作失败时
     */
    @Test
    void urlDriverParametersOverrideInternalDefaults() throws Exception {
        MysqlStorageProvider provider = this.provider(this.urlWith("connectTimeout=4321&socketTimeout=8765"), this.prefix);
        provider.initialize();
        provider.jdbi().useHandle(handle -> {
            var properties = handle.getConnection().unwrap(JdbcConnection.class).getPropertySet();
            assertEquals(4321, properties.getIntegerProperty(PropertyKey.connectTimeout).getValue());
            assertEquals(8765, properties.getIntegerProperty(PropertyKey.socketTimeout).getValue());
            assertTrue(handle.createQuery("SELECT @@session.sql_mode").mapTo(String.class).one().contains("STRICT_TRANS_TABLES"));
        });
    }

    /**
     * 验证当前完整建表中断后可以重入, 已分配的地图编号进度得到保留.
     */
    @Test
    void partialInitialDdlCanResumeWithoutLosingData() {
        BiConsumer<Handle, String> interrupted = (handle, prefix) -> {
            MysqlSchema.initialize(handle, prefix);
            handle.execute("UPDATE `" + prefix + "meta` SET value = 97 WHERE id = 'maps'");
            throw new IllegalStateException("injected failure after initial DDL");
        };
        assertThrows(IllegalStateException.class, () -> new MysqlSchemaMigrator(MysqlSchema.CURRENT_VERSION, interrupted, List.of()).migrate(this.direct, this.prefix));
        assertEquals(MysqlSchema.CURRENT_VERSION, this.meta("schema_pending"));
        new MysqlSchemaMigrator(MysqlSchema.CURRENT_VERSION, MysqlSchema::initialize, List.of()).migrate(this.direct, this.prefix);
        assertEquals(MysqlSchema.CURRENT_VERSION, this.meta("schema"));
        assertEquals(97, this.meta("maps"));
        assertEquals(0, this.pendingCount());
    }

    /**
     * 验证整表替换后中断可以恢复, 恢复前的进行中版本会阻止旧迁移代码启动.
     *
     * @throws Exception 当测试配置注入或数据库操作失败时
     */
    @Test
    void wholeTableRebuildResumesAfterRenameAndRejectsOlderCode() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        UUID player = UUID.randomUUID();
        provider.jdbi().useHandle(handle -> handle.createUpdate("INSERT INTO `" + this.prefix + "users` (player, name, last_seen) VALUES (:player, 'MiXeD', 123)").bind("player", player).execute());
        // 在新表接替原表后中断, 此时 DDL 已提交而 schema 尚未更新.
        AtomicBoolean failAfterRename = new AtomicBoolean(true);
        MysqlSchemaMigration rebuild = this.rebuildUsers(failAfterRename);
        MysqlSchemaMigrator newer = new MysqlSchemaMigrator(2, (handle, prefix) -> fail("Existing schemas must use migrations"), List.of(rebuild));
        assertThrows(IllegalStateException.class, () -> newer.migrate(provider.jdbi(), this.prefix));
        assertEquals(1, this.meta("schema"));
        assertEquals(2, this.meta("schema_pending"));
        assertTrue(this.tableExists(this.prefix + "users_old"));
        assertThrows(IllegalStateException.class, () -> new MysqlSchemaMigrator(1, MysqlSchema::initialize, List.of()).migrate(this.direct, this.prefix));
        // 同代迁移根据实际表布局恢复, 完成后清理进度标记和临时表.
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

    /**
     * 验证迁移锁跨越 DDL 提交保持有效, 且不同前缀可独立升级.
     *
     * @throws Exception 当配置注入、并发迁移或等待失败时
     */
    @Test
    void migrationLockSurvivesDdlAndSeparatesPrefixes() throws Exception {
        MysqlStorageProvider provider = this.provider(this.url, this.prefix);
        provider.initialize();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        MysqlSchemaMigration migration = new MysqlSchemaMigration() {
            /** {@inheritDoc} */
            @Override
            public int targetVersion() {
                return 2;
            }

            /** {@inheritDoc} */
            @Override
            public void migrate(@NonNull Handle handle, @NonNull String prefix) {
                runs.incrementAndGet();
                // DDL 隐式提交后保持迁移暂停, 让另一个连接尝试获取同一把锁.
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
        MysqlSchemaMigrator migrator = new MysqlSchemaMigrator(2, (handle, prefix) -> fail("Existing schemas must use migrations"), List.of(migration));
        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> migrator.migrate(provider.jdbi(), this.prefix), executor);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                CompletableFuture<Void> second = CompletableFuture.runAsync(() -> migrator.migrate(this.direct, this.prefix), executor);
                // 相同前缀仍在等待, 不同前缀的迁移应当可以完成.
                new MysqlSchemaMigrator(MysqlSchema.CURRENT_VERSION, MysqlSchema::initialize, List.of()).migrate(this.direct, "other_" + this.prefix);
                assertFalse(second.isDone());
                release.countDown();
                CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
                assertEquals(1, runs.get());
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * 验证无版本业务表以及缺失或重复的迁移版本会被拒绝.
     */
    @Test
    void rejectsUnversionedTablesAndInvalidMigrationSequences() {
        this.direct.useHandle(handle -> handle.execute("CREATE TABLE `" + this.prefix + "users` (name VARCHAR(16))"));
        assertThrows(IllegalStateException.class, () -> new MysqlSchemaMigrator(MysqlSchema.CURRENT_VERSION, MysqlSchema::initialize, List.of()).migrate(this.direct, this.prefix));
        assertEquals(0, this.pendingCount());
        MysqlSchemaMigration second = this.rebuildUsers(new AtomicBoolean());
        assertThrows(IllegalArgumentException.class, () -> new MysqlSchemaMigrator(0, MysqlSchema::initialize, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MysqlSchemaMigrator(3, MysqlSchema::initialize, List.of(second, second)));
        assertThrows(IllegalArgumentException.class, () -> new MysqlSchemaMigrator(3, MysqlSchema::initialize, List.of(second)));
        assertThrows(IllegalArgumentException.class, () -> new MysqlSchemaMigrator(1, MysqlSchema::initialize, List.of(second)));
        List<MysqlSchemaMigration> reversed = new ArrayList<>(this.userMigrations(3, new ArrayList<>()));
        Collections.reverse(reversed);
        assertThrows(IllegalArgumentException.class, () -> new MysqlSchemaMigrator(3, MysqlSchema::initialize, reversed));
    }

    // 用测试 V10 对比空库直建和已有 V7 逐级升级, 两条路径的最终 DDL 应相同.
    @Test
    void freshAndUpgradedDatabasesReachTheSameLatestSchema() {
        List<Integer> applied = new ArrayList<>();
        AtomicInteger initializations = new AtomicInteger();
        List<MysqlSchemaMigration> migrations = this.userMigrations(10, applied);
        MysqlSchemaMigrator latest = new MysqlSchemaMigrator(10, (handle, prefix) -> {
            assertEquals(10L, handle.createQuery("SELECT value FROM `" + prefix + "meta` WHERE id = 'schema_pending'").mapTo(Long.class).one());
            initializations.incrementAndGet();
            this.initializeUsers(handle, prefix, 10);
        }, migrations);
        latest.migrate(this.direct, this.prefix);
        assertEquals(10, this.meta("schema"));
        assertEquals(0, this.pendingCount());
        assertTrue(applied.isEmpty());

        String olderPrefix = "old_" + this.prefix;
        new MysqlSchemaMigrator(7, (handle, prefix) -> this.initializeUsers(handle, prefix, 7), migrations.subList(0, 6)).migrate(this.direct, olderPrefix);
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

    // V10 初始化中断后保留目标和已有数据, 只有同一版完整结构可以继续初始化.
    @Test
    void interruptedLatestInitializationRequiresTheSameTargetVersion() {
        List<Integer> applied = new ArrayList<>();
        List<MysqlSchemaMigration> migrations = this.userMigrations(10, applied);
        AtomicBoolean failBeforeMaps = new AtomicBoolean(true);
        MysqlSchemaMigrator interrupted = new MysqlSchemaMigrator(10, (handle, prefix) -> {
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
            MysqlSchemaMigrator mismatched = new MysqlSchemaMigrator(version, (handle, prefix) -> fail("A different initialization version must be rejected"), this.userMigrations(version, applied));
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

    // 已有旧库的进行中目标必须紧接已完成版本, 异常记录保持原样供检查.
    @Test
    void rejectsPendingMigrationsThatDoNotFollowTheStoredVersion() {
        List<Integer> applied = new ArrayList<>();
        new MysqlSchemaMigrator(7, (handle, prefix) -> this.initializeUsers(handle, prefix, 7), this.userMigrations(7, applied)).migrate(this.direct, this.prefix);
        MysqlSchemaMigrator latest = new MysqlSchemaMigrator(10, (handle, prefix) -> fail("Existing schemas must use migrations"), this.userMigrations(10, applied));
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

    // 测试完整结构在一次 CREATE 中包含目标版本的所有列, 同版本重入保留原表和数据.
    private void initializeUsers(Handle handle, String prefix, int version) {
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS `" + prefix + "users` (player INT NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL");
        for (int target = 2; target <= version; target++) {
            ddl.append(", revision_").append(target).append(" INT NOT NULL DEFAULT 0");
        }
        handle.execute(ddl.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin").toString());
    }

    // 每个测试迁移只增加下一代的列, 并检查进入该步时上一代已公布完成.
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

    /**
     * 构造扩大名称列并转换名称内容的测试迁移, 支持表替换后的故障恢复.
     *
     * @param failAfterRename 是否在首次表替换后抛出异常, 触发时自动复位
     * @return 从第一代表结构升级到测试第二代的迁移
     */
    private MysqlSchemaMigration rebuildUsers(AtomicBoolean failAfterRename) {
        return new MysqlSchemaMigration() {
            /** {@inheritDoc} */
            @Override
            public int targetVersion() {
                return 2;
            }

            /**
             * {@inheritDoc}
             *
             * @throws IllegalStateException 当用例注入迁移中断时
             */
            @Override
            public void migrate(@NonNull Handle handle, @NonNull String prefix) {
                // 列宽反映表交换是否已经完成, 重入时据此选择恢复位置.
                boolean renamed = handle.createQuery("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = :table AND column_name = 'name'")
                        .bind("table", prefix + "users").mapTo(Integer.class).one() == 128;
                if (!renamed) {
                    handle.execute("CREATE TABLE IF NOT EXISTS `" + prefix + "users_new` (player BINARY(16) NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, last_seen BIGINT NOT NULL) ENGINE=InnoDB");
                    handle.execute("INSERT INTO `" + prefix + "users_new` SELECT player, CONCAT(name, '_v2'), last_seen FROM `" + prefix + "users` ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = VALUES(last_seen)");
                    // 同一条 RENAME 原子交换表名, 故障点放在交换完成之后.
                    handle.execute("RENAME TABLE `" + prefix + "users` TO `" + prefix + "users_old`, `" + prefix + "users_new` TO `" + prefix + "users`");
                    if (failAfterRename.getAndSet(false)) throw new IllegalStateException("injected failure after table swap");
                }
                handle.execute("DROP TABLE IF EXISTS `" + prefix + "users_old`");
            }
        };
    }

    /**
     * 通过配置字段注入构造待初始化实例, 并登记到当前用例的清理列表.
     *
     * @param url 本次连接使用的 JDBC URL
     * @param prefix 当前用例使用的表前缀
     * @return 尚未初始化的存储实例
     * @throws Exception 当反射配置字段失败时
     */
    private MysqlStorageProvider provider(String url, String prefix) throws Exception {
        // 复用配置加载器写入的字段, 在测试内构造所需连接参数.
        PluginConfig.MysqlOptions options = new PluginConfig.MysqlOptions();
        for (Map.Entry<String, String> entry : Map.of("url", url, "username", this.username, "password", this.password, "tablePrefix", prefix).entrySet()) {
            Field field = PluginConfig.MysqlOptions.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(options, entry.getValue());
        }
        MysqlStorageProvider provider = new MysqlStorageProvider(options);
        this.providers.add(provider);
        return provider;
    }

    /**
     * 读取实例当前持有的连接池, 供测试确认其关闭状态.
     *
     * @param provider 已完成初始化的存储实例
     * @return 实例内部持有的连接池
     * @throws Exception 当反射读取连接池失败时
     */
    private HikariDataSource pool(MysqlStorageProvider provider) throws Exception {
        Field field = MysqlStorageProvider.class.getDeclaredField("dataSource");
        field.setAccessible(true);
        return (HikariDataSource) field.get(provider);
    }

    /**
     * 使用正式 UUID 参数绑定将快照行写入当前用例的快照表.
     *
     * @param jdbi 已注册快照类型映射的查询入口
     * @param row 待写入的完整快照行
     */
    private void insert(Jdbi jdbi, SnapshotRow row) {
        SnapshotMeta meta = row.meta();
        jdbi.useHandle(handle -> handle.createUpdate("INSERT INTO `" + this.prefix + "snapshots` (id, player, ts, cause, pinned, server, format, mc_data, data) VALUES (:id, :player, :ts, :cause, :pinned, :server, :format, :mcData, :data)")
                .bind("id", meta.id()).bind("player", meta.player()).bind("ts", meta.timestamp()).bind("cause", meta.cause().name())
                .bind("pinned", meta.pinned()).bind("server", meta.server()).bind("format", row.format()).bind("mcData", meta.mcDataVersion()).bind("data", row.data()).execute());
    }

    /**
     * 读取当前用例的指定元记录, 供检查版本和计数器进度.
     *
     * @param key 元记录主键
     * @return 元记录保存的数值
     */
    private long meta(String key) {
        return this.direct.withHandle(handle -> handle.createQuery("SELECT value FROM `" + this.prefix + "meta` WHERE id = :id").bind("id", key).mapTo(Long.class).one());
    }

    /**
     * 检查当前用例是否仍有未完成迁移标记.
     *
     * @return 进行中标记的记录数, 为 0 或 1
     */
    private int pendingCount() {
        return this.direct.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM `" + this.prefix + "meta` WHERE id = 'schema_pending'").mapTo(Integer.class).one());
    }

    /**
     * 查询测试数据库中是否存在指定表, 用于检查迁移临时表清理结果.
     *
     * @param table 包含前缀的完整表名
     * @return 表存在时为 true
     */
    private boolean tableExists(String table) {
        return this.direct.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = :table").bind("table", table).mapTo(Integer.class).one()) != 0;
    }

    /**
     * 在测试连接地址末尾附加驱动参数, 用于覆盖默认配置.
     *
     * @param properties 用 &amp; 分隔的 JDBC 参数文本
     * @return 附加参数后的连接地址
     */
    private String urlWith(String properties) {
        return this.url + (this.url.contains("?") ? "&" : "?") + properties;
    }
}
