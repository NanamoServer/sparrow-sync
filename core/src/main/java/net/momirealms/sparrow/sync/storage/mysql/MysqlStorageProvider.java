package net.momirealms.sparrow.sync.storage.mysql;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.zaxxer.hikari.HikariDataSource;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.RowSnapshotCodec;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.storage.mysql.upgrade.MysqlSchemaMigration;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.statement.Query;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@ApiStatus.Internal
public final class MysqlStorageProvider {
    private static final long MIN_PACKET_BYTES = 64L * 1024 * 1024; // 服务端和驱动均须允许至少 64 MiB 的传输包
    private static final List<MysqlSchemaMigration> MIGRATIONS = List.of(); // 按目标版本排列的旧库升级链, 覆盖 2..CURRENT_VERSION
    private static final String META_COLUMNS = "`id`, `player`, `ts`, `cause`, `pinned`, `server`, `mc_data`";
    private static final String NEWEST_FIRST = " ORDER BY `ts` DESC, `id` DESC";

    private final PluginConfig.MysqlOptions options;
    private final RowSnapshotCodec codec;
    private final Executor asyncExecutor; // JDBC 读取与解码分别提交到插件 worker
    private HikariDataSource dataSource;
    private Jdbi jdbi;

    public MysqlStorageProvider(@NotNull PluginConfig.MysqlOptions options, @NotNull RowSnapshotCodec codec, @NotNull Executor asyncExecutor) {
        this.options = options;
        this.codec = codec;
        this.asyncExecutor = asyncExecutor;
    }

    // 建立连接池并将数据库升级到当前表版本.
    public void initialize() {
        if (this.dataSource != null) throw new IllegalStateException("MySQL storage is already initialized");
        // 表名会拼入 DDL, 限定字符集并为最长的业务表名预留长度.
        if (!this.options.tablePrefix().matches("[a-z0-9_]{0,52}")) {
            throw new IllegalArgumentException("MySQL table prefix must contain at most 52 lowercase ASCII letters, digits or underscores");
        }
        if (!this.options.url().startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("MySQL storage requires a jdbc:mysql:// URL");
        }
        HikariDataSource pool = new HikariDataSource();
        boolean ready = false;
        try {
            // MysqlDataSource 使用 NonRegisteringDriver, 连接生命周期不占用全局驱动注册表.
            MysqlDataSource mysql = new MysqlDataSource() {
                private int loginTimeout; // Hikari 设置的建连等待秒数, 关闭时用于等待建连线程退出

                // Hikari 按此值等待建连线程退出; Connector/J 的原始实现始终返回 0.
                @Override
                public void setLoginTimeout(int seconds) {
                    this.loginTimeout = seconds;
                }

                @Override
                public int getLoginTimeout() {
                    return this.loginTimeout;
                }
            };
            mysql.setUrl(this.options.url());
            mysql.setUser(this.options.username());
            mysql.setPassword(this.options.password());
            // 驱动默认值提供有限的网络等待时间, URL 中的同名参数仍可覆盖这些值.
            mysql.setConnectTimeout(5000);
            mysql.setSocketTimeout(10000);
            mysql.setCharacterEncoding("UTF-8");
            mysql.setAutoReconnect(false);
            // 连接池采用内部资源策略, 每条连接使用读已提交隔离级别和严格写入模式.
            pool.setDataSource(mysql);
            pool.setPoolName("sparrow-sync-mysql");
            pool.setMaximumPoolSize(10);
            pool.setConnectionTimeout(10000);
            pool.setValidationTimeout(3000);
            pool.setMaxLifetime(1800000);
            pool.setInitializationFailTimeout(10000);
            pool.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
            pool.setConnectionInitSql("SET SESSION sql_mode = CONCAT_WS(',', NULLIF(@@session.sql_mode, ''), 'STRICT_TRANS_TABLES')");

            // UUID 按固定 16 字节绑定, 完整快照行与元信息投影分别注册读取入口.
            Jdbi connected = Jdbi.create(pool)
                    .registerArgument(new AbstractArgumentFactory<UUID>(Types.BINARY) {
                        // 将 UUID 绑定为与 BINARY(16) 主键一致的字节序列.
                        @NotNull
                        @Override
                        protected Argument build(UUID value, ConfigRegistry config) {
                            byte[] bytes = UUIDUtils.toBytes(value);
                            return (position, statement, context) -> statement.setBytes(position, bytes);
                        }
                    })
                    .registerRowMapper(SnapshotRow.class, new MysqlSnapshotRowMapper())
                    .registerRowMapper(SnapshotMeta.class, (result, context) -> MysqlSnapshotRowMapper.readMeta(result));
            connected.useHandle(MysqlStorageProvider::validateConnection);
            new MysqlSchemaMigrator(MysqlSchema.CURRENT_VERSION, MysqlSchema::initialize, MIGRATIONS).migrate(connected, this.options.tablePrefix());
            // 所有准备成功后才转交连接池所有权, 此时 Jdbi 对应的表结构已经可用.
            this.dataSource = pool;
            this.jdbi = connected;
            ready = true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize MySQL storage", exception);
        } finally {
            // 初始化期间发生的连接异常和迁移异常都在此释放本次连接池.
            if (!ready) pool.close();
        }
    }

    // 检查实际连接是否具备快照传输所需的数据库、包容量参数.
    private static void validateConnection(Handle handle) throws SQLException {
        // 迁移以当前数据库为边界, URL 必须选定业务库.
        String database = handle.createQuery("SELECT DATABASE()").mapTo(String.class).one();
        if (database == null || database.isBlank()) throw new IllegalStateException("MySQL URL must select a database");
        // 大快照受服务端和驱动两端的包容量共同限制.
        long serverPacket = handle.createQuery("SELECT @@session.max_allowed_packet").mapTo(Long.class).one();
        var properties = handle.getConnection().unwrap(JdbcConnection.class).getPropertySet();
        int driverPacket = properties.getMemorySizeProperty(PropertyKey.maxAllowedPacket).getValue();
        if (serverPacket < MIN_PACKET_BYTES || driverPacket < MIN_PACKET_BYTES) {
            throw new IllegalStateException("MySQL max_allowed_packet and Connector/J maxAllowedPacket must both be at least " + MIN_PACKET_BYTES + " bytes");
        }
    }

    // 完整读取先带回元数据和字节帧, 连接释放后再投递解码.
    @NotNull
    public CompletableFuture<Optional<Snapshot>> latestSnapshot(@NotNull UUID player) {
        return CompletableFuture.supplyAsync(() -> this.jdbi().withHandle(handle -> handle.createQuery("SELECT " + META_COLUMNS + ", `format`, `data` FROM `" + this.options.tablePrefix() + "snapshots` WHERE `player` = :player" + NEWEST_FIRST + " LIMIT 1")
                        .bind("player", player).mapTo(SnapshotRow.class).findOne()), this.asyncExecutor)
                .thenApplyAsync(row -> row.map(this::decodeRow), this.asyncExecutor);
    }

    @NotNull
    public CompletableFuture<Optional<Snapshot>> snapshot(@NotNull UUID snapshotId) {
        return CompletableFuture.supplyAsync(() -> this.jdbi().withHandle(handle -> handle.createQuery("SELECT " + META_COLUMNS + ", `format`, `data` FROM `" + this.options.tablePrefix() + "snapshots` WHERE `id` = :id")
                        .bind("id", snapshotId).mapTo(SnapshotRow.class).findOne()), this.asyncExecutor)
                .thenApplyAsync(row -> row.map(this::decodeRow), this.asyncExecutor);
    }

    // 已存在但损坏的快照以异常交给上层, 保留行编解码器给出的原因.
    private Snapshot decodeRow(SnapshotRow row) {
        DecodedSnapshot decoded = this.codec.decode(row);
        if (decoded instanceof DecodedSnapshot.Valid(Snapshot snapshot)) return snapshot;
        DecodedSnapshot.Invalid invalid = (DecodedSnapshot.Invalid) decoded;
        throw new CompletionException(new IOException("stored snapshot is invalid (" + invalid.reason() + "): " + invalid.detail()));
    }

    // 组合有效筛选条件, 列表只读取可独立解析的元数据列.
    @NotNull
    public CompletableFuture<List<SnapshotMeta>> listSnapshots(@NotNull SnapshotQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder sql = new StringBuilder("SELECT " + META_COLUMNS + " FROM `" + this.options.tablePrefix() + "snapshots` WHERE `player` = :player");
            if (query.from() != SnapshotQuery.UNBOUNDED_FROM) {
                sql.append(" AND `ts` >= :from");
            }
            if (query.to() != SnapshotQuery.UNBOUNDED_TO) {
                sql.append(" AND `ts` <= :to");
            }
            if (query.pinned() != SnapshotQuery.PinFilter.ANY) {
                sql.append(" AND `pinned` = :pinned");
            }
            sql.append(NEWEST_FIRST);
            if (query.limit() > SnapshotQuery.NO_LIMIT) {
                sql.append(" LIMIT :limit");
            }
            return this.jdbi().withHandle(handle -> {
                Query statement = handle.createQuery(sql.toString()).bind("player", query.player());
                if (query.from() != SnapshotQuery.UNBOUNDED_FROM) {
                    statement.bind("from", query.from());
                }
                if (query.to() != SnapshotQuery.UNBOUNDED_TO) {
                    statement.bind("to", query.to());
                }
                if (query.pinned() != SnapshotQuery.PinFilter.ANY) {
                    statement.bind("pinned", query.pinned() == SnapshotQuery.PinFilter.PINNED);
                }
                if (query.limit() > SnapshotQuery.NO_LIMIT) {
                    statement.bind("limit", query.limit());
                }
                return statement.mapTo(SnapshotMeta.class).list();
            });
        }, this.asyncExecutor);
    }

    // 每次会话刷新当前名字和出现时间, 主键保持玩家 UUID.
    @NotNull
    public CompletableFuture<Void> ensureUser(@NotNull UUID player, @NotNull String name) {
        return CompletableFuture.runAsync(() -> {
            validateUserName(name);
            this.jdbi().useHandle(handle -> handle.createUpdate("INSERT INTO `" + this.options.tablePrefix() + "users` (`player`, `name`, `last_seen`) VALUES (:player, :name, :lastSeen) ON DUPLICATE KEY UPDATE `name` = :name, `last_seen` = :lastSeen")
                    .bind("player", player).bind("name", name).bind("lastSeen", System.currentTimeMillis()).execute());
        }, this.asyncExecutor);
    }

    // 同名记录取最近会话, 同毫秒时按 UUID 保持稳定顺序.
    @NotNull
    public CompletableFuture<Optional<UUID>> lookupUser(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> {
            validateUserName(name);
            return this.jdbi().withHandle(handle -> handle.createQuery("SELECT `player` FROM `" + this.options.tablePrefix() + "users` WHERE `name` = :name ORDER BY `last_seen` DESC, `player` DESC LIMIT 1")
                    .bind("name", name).map((result, context) -> UUIDUtils.fromBytes(result.getBytes("player"))).findOne());
        }, this.asyncExecutor);
    }

    // VARCHAR(64) 按 Unicode 码点计数; utf8mb4_bin 的 PAD SPACE 比较要求名称拒绝尾随 U+0020.
    private static void validateUserName(String name) {
        if (name.codePointCount(0, name.length()) > 64) {
            throw new IllegalArgumentException("MySQL user names must contain at most 64 Unicode code points");
        }
        if (name.endsWith(" ")) {
            throw new IllegalArgumentException("MySQL user names must not end with U+0020 space");
        }
    }

    @NotNull
    public Jdbi jdbi() {
        if (this.jdbi == null) throw new IllegalStateException("MySQL storage is not initialized");
        return this.jdbi;
    }

    public void shutdown() {
        this.jdbi = null;
        if (this.dataSource != null) {
            this.dataSource.close();
            this.dataSource = null;
        }
    }
}
