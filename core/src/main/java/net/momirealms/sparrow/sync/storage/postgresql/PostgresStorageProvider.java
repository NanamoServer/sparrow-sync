package net.momirealms.sparrow.sync.storage.postgresql;

import net.momirealms.sparrow.sync.storage.SnapshotRow;
import net.momirealms.sparrow.sync.storage.SnapshotRowMapper;

import com.zaxxer.hikari.HikariDataSource;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.MapStorage;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.exception.FormatException;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.postgresql.upgrade.PostgresSchemaMigration;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.statement.Query;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@ApiStatus.Internal
public final class PostgresStorageProvider implements StorageProvider {
    private static final int MAX_PAYLOAD_BYTES = 15 * 1024 * 1024; // 编码后的完整 data 帧上限, 等于上限允许写入
    private static final List<PostgresSchemaMigration> MIGRATIONS = List.of(); // 按目标版本排列的旧库升级链, 覆盖 2..CURRENT_VERSION
    private static final String META_COLUMNS = "\"id\", \"player\", \"ts\", \"cause\", \"pinned\", \"server\", \"mc_data\"";
    private static final String NEWEST_FIRST = " ORDER BY \"ts\" DESC, \"id\" DESC";

    private final PluginConfig.PostgresOptions options;
    private final PostgresRowSnapshotCodec codec;
    private final PlayerSerialExecutor serialExecutor;
    private final Executor asyncExecutor; // JDBC 读取与连接归还后的解码在同一 worker 任务内完成
    private final SyncLogger logger;
    private HikariDataSource dataSource;
    private Jdbi jdbi;
    private PostgresMapStorage maps;

    public PostgresStorageProvider(
            @NotNull PluginConfig.PostgresOptions options,
            @NotNull BinarySnapshotCodec codec,
            @NotNull PlayerSerialExecutor serialExecutor,
            @NotNull Executor asyncExecutor,
            @NotNull SyncLogger logger
    ) {
        this.options = options;
        this.codec = new PostgresRowSnapshotCodec(codec);
        this.serialExecutor = serialExecutor;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    // 建立连接池并将数据库升级到当前表版本.
    @Override
    public void initialize() {
        if (this.dataSource != null) throw new IllegalStateException("PostgreSQL storage is already initialized");
        // PostgreSQL 标识符最多 63 字节, 为最长的索引名预留 23 字节.
        if (!this.options.tablePrefix().matches("[a-z0-9_]{0,40}")) {
            throw new IllegalArgumentException("PostgreSQL table prefix must contain at most 40 lowercase ASCII letters, digits or underscores");
        }
        if (!this.options.url().startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("PostgreSQL storage requires a jdbc:postgresql:// URL");
        }
        HikariDataSource pool = new HikariDataSource();
        boolean ready = false;
        try {
            // pgJDBC 的超时单位为秒, 仅为 URL 未指定的选项补默认值.
            PGSimpleDataSource postgres = new PGSimpleDataSource();
            postgres.setUrl(this.options.url());
            Properties properties = Driver.parseURL(this.options.url(), null);
            if (!PGProperty.CONNECT_TIMEOUT.isPresent(properties)) {
                postgres.setConnectTimeout(5);
            }
            if (!PGProperty.SOCKET_TIMEOUT.isPresent(properties)) {
                postgres.setSocketTimeout(10);
            }
            postgres.setUser(this.options.username());
            postgres.setPassword(this.options.password());
            pool.setDataSource(postgres);
            pool.setPoolName("sparrow-sync-postgresql");
            pool.setMaximumPoolSize(10);
            pool.setConnectionTimeout(10000);
            pool.setValidationTimeout(3000);
            pool.setMaxLifetime(1800000);
            pool.setInitializationFailTimeout(10000);
            pool.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
            // 原生 UUID 参数与 BYTEA 数据体分别绑定, 元信息投影不读取数据体.
            Jdbi connected = Jdbi.create(pool)
                    .registerArgument(new AbstractArgumentFactory<UUID>(Types.OTHER) {
                        @NotNull
                        @Override
                        protected Argument build(UUID value, ConfigRegistry config) {
                            return (position, statement, context) -> statement.setObject(position, value);
                        }
                    })
                    .registerRowMapper(SnapshotRow.class, new SnapshotRowMapper())
                    .registerRowMapper(SnapshotMeta.class, (result, context) -> SnapshotRowMapper.readMeta(result));
            new PostgresSchemaMigrator(this.logger, PostgresSchema.CURRENT_VERSION, PostgresSchema::initialize, MIGRATIONS).migrate(connected, this.options.tablePrefix());
            PostgresMapStorage mapStorage = new PostgresMapStorage(connected, this.options.tablePrefix(), this.asyncExecutor);
            // 所有准备成功后才转交连接池所有权, 此时 Jdbi 对应的表结构已经可用.
            this.dataSource = pool;
            this.jdbi = connected;
            this.maps = mapStorage;
            ready = true;
        } finally {
            // 初始化期间发生的连接异常和迁移异常都在此释放本次连接池.
            if (!ready) pool.close();
        }
    }

    @Override
    @NotNull
    public MapStorage maps() {
        if (this.maps == null) {
            throw new IllegalStateException("PostgreSQL storage is not initialized");
        }
        return this.maps;
    }

    // 完整读取先带回元数据和字节帧, 连接归还后在当前任务中解码.
    @Override
    @NotNull
    public CompletableFuture<Optional<Snapshot>> latestSnapshot(@NotNull UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<SnapshotRow> row = this.jdbi().withHandle(handle -> handle.createQuery("SELECT " + META_COLUMNS + ", \"format\", \"data\" FROM \"" + this.options.tablePrefix() + "snapshots\" WHERE \"player\" = :player" + NEWEST_FIRST + " LIMIT 1")
                    .bind("player", player).mapTo(SnapshotRow.class).findOne());
            return row.map(this::decodeRow);
        }, this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<Snapshot>> snapshot(@NotNull UUID snapshotId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<SnapshotRow> row = this.jdbi().withHandle(handle -> handle.createQuery("SELECT " + META_COLUMNS + ", \"format\", \"data\" FROM \"" + this.options.tablePrefix() + "snapshots\" WHERE \"id\" = :id")
                    .bind("id", snapshotId).mapTo(SnapshotRow.class).findOne());
            return row.map(this::decodeRow);
        }, this.asyncExecutor);
    }

    // 已存在但损坏的快照以异常交给上层, 保留行编解码器给出的原因.
    private Snapshot decodeRow(SnapshotRow row) {
        DecodedSnapshot decoded = this.codec.decode(row);
        if (decoded instanceof DecodedSnapshot.Valid(Snapshot snapshot)) return snapshot;
        DecodedSnapshot.Invalid invalid = (DecodedSnapshot.Invalid) decoded;
        throw new CompletionException(new FormatException(invalid.reason(), "stored snapshot is invalid (" + invalid.reason() + "): " + invalid.detail()));
    }

    @Override
    @NotNull
    public CompletableFuture<List<SnapshotMeta>> listSnapshots(@NotNull SnapshotQuery query) {
        return CompletableFuture.supplyAsync(() -> this.jdbi().withHandle(handle -> this.snapshotQuery(handle, query, false).mapTo(SnapshotMeta.class).list()), this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<Long> countSnapshots(@NotNull SnapshotQuery query) {
        return CompletableFuture.supplyAsync(() -> this.jdbi().withHandle(handle -> this.snapshotQuery(handle, query, true).mapTo(Long.class).one()), this.asyncExecutor);
    }

    // 列表与计数共用筛选条件, 只有列表添加排序和页边界.
    private Query snapshotQuery(Handle handle, SnapshotQuery query, boolean count) {
        String columns = count ? "COUNT(*)" : META_COLUMNS;
        StringBuilder sql = new StringBuilder("SELECT " + columns + " FROM \"" + this.options.tablePrefix() + "snapshots\" WHERE \"player\" = :player");
        if (query.from() != SnapshotQuery.UNBOUNDED_FROM) {
            sql.append(" AND \"ts\" >= :from");
        }
        if (query.to() != SnapshotQuery.UNBOUNDED_TO) {
            sql.append(" AND \"ts\" <= :to");
        }
        if (query.pinned() != SnapshotQuery.PinFilter.ANY) {
            sql.append(" AND \"pinned\" = :pinned");
        }
        if (!count) {
            sql.append(NEWEST_FIRST);
            if (query.limit() > SnapshotQuery.NO_LIMIT) {
                sql.append(" LIMIT :limit");
            }
            sql.append(" OFFSET :offset");
        }
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
        if (!count) {
            if (query.limit() > SnapshotQuery.NO_LIMIT) {
                statement.bind("limit", query.limit());
            }
            statement.bind("offset", query.offset());
        }
        return statement;
    }

    // 编码由通用 worker 执行, 保存立即进入玩家队列, 写库时按请求顺序等待编码结果.
    @Override
    @NotNull
    public CompletableFuture<SaveOutcome> saveSnapshotOutcome(@NotNull Snapshot snapshot) {
        SnapshotMeta meta = snapshot.meta();
        CompletableFuture<SnapshotRow> encoded = CompletableFuture.supplyAsync(() -> {
            try {
                if (meta.server().codePointCount(0, meta.server().length()) > 255) {
                    throw new IOException("PostgreSQL snapshot server names must contain at most 255 Unicode code points");
                }
                return this.codec.encode(snapshot);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, this.asyncExecutor);
        return CompletableFuture.supplyAsync(() -> {
            SnapshotRow row;
            try {
                row = encoded.join();
            } catch (CompletionException exception) {
                if (!(exception.getCause() instanceof IOException)) {
                    throw exception;
                }
                this.logger.error(LogCategory.STORAGE, meta.player(), null, exception.getCause(), LogConstants.STORAGE_ENCODE_FAILED, meta.player().toString());
                return new SaveOutcome(SaveResult.REJECTED_MALFORMED, null);
            }
            if (row.data().length > MAX_PAYLOAD_BYTES) {
                this.logger.error(LogCategory.STORAGE, meta.player(), null, LogConstants.STORAGE_OVERSIZED, meta.player().toString(), String.valueOf(row.data().length));
                return new SaveOutcome(SaveResult.REJECTED_OVERSIZED, null);
            }
            return this.insert(row);
        }, this.serialExecutor.executor(meta.player()));
    }

    // 使用普通 INSERT 保留已有快照, 写入异常与已提交后的次序诊断分别处理.
    private SaveOutcome insert(SnapshotRow row) {
        SnapshotMeta meta = row.meta();
        try {
            this.jdbi().useHandle(handle -> handle.createUpdate("INSERT INTO \"" + this.options.tablePrefix() + "snapshots\" (\"id\", \"player\", \"ts\", \"cause\", \"pinned\", \"server\", \"format\", \"mc_data\", \"data\") VALUES (:id, :player, :ts, :cause, :pinned, :server, :format, :mcData, :data)")
                    .bind("id", meta.id()).bind("player", meta.player()).bind("ts", meta.timestamp()).bind("cause", meta.cause().name())
                    .bind("pinned", meta.pinned()).bind("server", meta.server()).bind("format", row.format()).bind("mcData", meta.mcDataVersion()).bind("data", row.data()).execute());
        } catch (JdbiException exception) {
            SQLException sql = PostgresFailureClassifier.sqlCause(exception);
            if (sql != null && "23505".equals(sql.getSQLState())) {
                // 唯一键冲突只有在本快照 ID 已存在时才能认定为幂等重放.
                try {
                    boolean exists = this.jdbi().withHandle(handle -> handle.createQuery("SELECT 1 FROM \"" + this.options.tablePrefix() + "snapshots\" WHERE \"id\" = :id").bind("id", meta.id()).mapTo(Integer.class).findOne().isPresent());
                    if (exists) return new SaveOutcome(SaveResult.DUPLICATE, null);
                } catch (JdbiException checkFailure) {
                    return this.failed(meta, checkFailure);
                }
            }
            return this.failed(meta, exception);
        }
        // INSERT 已在 autocommit 下完成, 暂时无法查询次序时仍保留已落库结果.
        Optional<SnapshotOrder> latest;
        try {
            latest = this.jdbi()
                    .withHandle(handle -> handle.createQuery("SELECT \"id\", \"ts\", \"server\" FROM \"" + this.options.tablePrefix() + "snapshots\" WHERE \"player\" = :player" + NEWEST_FIRST + " LIMIT 1")
                    .bind("player", meta.player()).map((result, context) -> new SnapshotOrder(result.getObject("id", UUID.class), result.getLong("ts"), result.getString("server"))).findOne());
        } catch (JdbiException exception) {
            SQLException sql = PostgresFailureClassifier.sqlCause(exception);
            if (sql == null || PostgresFailureClassifier.classify(sql) != SaveResult.RETRY_LATER) {
                throw exception;
            }
            this.logger.warn(LogCategory.STORAGE, meta.player(), null, exception, LogConstants.STORAGE_ORDER_CHECK_FAILED, meta.id().toString(), meta.player().toString());
            return new SaveOutcome(SaveResult.SAVED, null);
        }
        if (latest.isEmpty() || latest.get().id().equals(meta.id())) return new SaveOutcome(SaveResult.SAVED, null);
        SnapshotOrder newest = latest.get();
        this.logger.file(
                LogCategory.STORAGE, meta.player(), null, LogConstants.STORAGE_OUT_OF_ORDER,
                meta.id().toString(), meta.player().toString(), String.valueOf(meta.timestamp()), newest.server(), String.valueOf(newest.timestamp())
        );
        return new SaveOutcome(SaveResult.SAVED_OUT_OF_ORDER, null);
    }

    // 确定性失败在存储边界记录, 可重试失败保留原异常供保存链收敛日志.
    private SaveOutcome failed(SnapshotMeta meta, JdbiException exception) {
        SQLException sql = PostgresFailureClassifier.sqlCause(exception);
        if (sql == null) {
            throw exception;
        }
        SaveResult result = PostgresFailureClassifier.classify(sql);
        if (result == null) {
            throw exception;
        }
        if (result.retriable()) return new SaveOutcome(result, exception);
        this.logger.error(LogCategory.STORAGE, meta.player(), null, exception, LogConstants.STORAGE_WRITE_REJECTED, meta.player().toString());
        return new SaveOutcome(result, null);
    }

    // 轮转与保存共享玩家队列, 同时刻按 id 保留排序靠前的记录.
    @Override
    @NotNull
    public CompletableFuture<Integer> rotate(@NotNull UUID player, int maxUnpinned) {
        return CompletableFuture.supplyAsync(() -> this.jdbi().withHandle(handle -> {
            String table = "\"" + this.options.tablePrefix() + "snapshots\"";
            if (maxUnpinned <= 0) {
                return handle.createUpdate("DELETE FROM " + table + " WHERE \"player\" = :player AND \"pinned\" = FALSE").bind("player", player).execute();
            }
            // 同一条语句确定保留边界, DELETE 再检查目标记录当前的固定状态.
            return handle.createUpdate("DELETE FROM " + table + " AS expired USING (SELECT \"ts\", \"id\" FROM " + table
                            + " WHERE \"player\" = :player AND \"pinned\" = FALSE" + NEWEST_FIRST + " LIMIT 1 OFFSET :offset) AS retained"
                            + " WHERE (expired.\"ts\", expired.\"id\") < (retained.\"ts\", retained.\"id\")"
                            + " AND expired.\"player\" = :player AND expired.\"pinned\" = FALSE")
                    .bind("player", player).bind("offset", maxUnpinned - 1).execute();
        }), this.serialExecutor.executor(player));
    }

    // 条件中排除已是目标状态的记录, 返回值仅表示本次是否改变状态.
    @Override
    @NotNull
    public CompletableFuture<Boolean> setPinned(@NotNull UUID snapshotId, boolean pinned) {
        return CompletableFuture.supplyAsync(() -> this.jdbi().withHandle(handle -> handle.createUpdate("UPDATE \"" + this.options.tablePrefix() + "snapshots\" SET \"pinned\" = :pinned WHERE \"id\" = :id AND \"pinned\" <> :pinned")
                .bind("id", snapshotId).bind("pinned", pinned).execute() > 0), this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<Boolean> deleteSnapshot(@NotNull UUID snapshotId) {
        return CompletableFuture.supplyAsync(() -> this.jdbi().withHandle(handle -> handle.createUpdate("DELETE FROM \"" + this.options.tablePrefix() + "snapshots\" WHERE \"id\" = :id")
                .bind("id", snapshotId).execute() > 0), this.asyncExecutor);
    }

    // 每次会话刷新当前名字和出现时间, 主键保持玩家 UUID.
    @Override
    @NotNull
    public CompletableFuture<Void> ensureUser(@NotNull UUID player, @NotNull String name) {
        return CompletableFuture.runAsync(() -> {
            validateUserName(name);
            this.jdbi().useHandle(handle -> handle.createUpdate("INSERT INTO \"" + this.options.tablePrefix() + "users\" (\"player\", \"name\", \"last_seen\") VALUES (:player, :name, :lastSeen) ON CONFLICT (\"player\") DO UPDATE SET \"name\" = EXCLUDED.\"name\", \"last_seen\" = EXCLUDED.\"last_seen\"")
                    .bind("player", player).bind("name", name).bind("lastSeen", System.currentTimeMillis()).execute());
        }, this.asyncExecutor);
    }

    // 同名记录取最近会话, 同毫秒时按 UUID 保持稳定顺序.
    @Override
    @NotNull
    public CompletableFuture<Optional<UUID>> lookupUser(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> {
            validateUserName(name);
            return this.jdbi().withHandle(handle -> handle.createQuery("SELECT \"player\" FROM \"" + this.options.tablePrefix() + "users\" WHERE \"name\" = :name ORDER BY \"last_seen\" DESC, \"player\" DESC LIMIT 1")
                    .bind("name", name).map((result, context) -> result.getObject("player", UUID.class)).findOne());
        }, this.asyncExecutor);
    }

    // VARCHAR(64) 按 Unicode 码点计数, C 排序规则保留大小写与尾随空格的区别.
    private static void validateUserName(String name) {
        if (name.codePointCount(0, name.length()) > 64) {
            throw new IllegalArgumentException("PostgreSQL user names must contain at most 64 Unicode code points");
        }
        if (name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("PostgreSQL user names must not contain NUL");
        }
    }

    @NotNull
    Jdbi jdbi() {
        if (this.jdbi == null) throw new IllegalStateException("PostgreSQL storage is not initialized");
        return this.jdbi;
    }

    @Override
    public void shutdown() {
        this.maps = null;
        this.jdbi = null;
        if (this.dataSource != null) {
            this.dataSource.close();
            this.dataSource = null;
        }
    }

    private record SnapshotOrder(UUID id, long timestamp, String server) {
    }
}
