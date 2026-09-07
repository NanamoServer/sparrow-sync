package net.momirealms.sparrow.sync.storage.mysql;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.zaxxer.hikari.HikariDataSource;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.mysql.upgrade.MysqlSchemaMigration;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

@ApiStatus.Internal
public final class MysqlStorageProvider {
    private static final long MIN_PACKET_BYTES = 64L * 1024 * 1024; // 服务端和驱动均须允许至少 64 MiB 的传输包
    private static final List<MysqlSchemaMigration> MIGRATIONS = List.of(); // 按目标版本排列的旧库升级链, 覆盖 2..CURRENT_VERSION

    private final PluginConfig.MysqlOptions options;
    private HikariDataSource dataSource;
    private Jdbi jdbi;

    public MysqlStorageProvider(@NotNull PluginConfig.MysqlOptions options) {
        this.options = options;
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
