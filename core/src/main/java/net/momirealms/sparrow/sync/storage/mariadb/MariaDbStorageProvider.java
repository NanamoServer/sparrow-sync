package net.momirealms.sparrow.sync.storage.mariadb;

import com.zaxxer.hikari.HikariDataSource;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.SnapshotRow;
import net.momirealms.sparrow.sync.storage.SnapshotRowMapper;
import net.momirealms.sparrow.sync.storage.mysql.MysqlMapStorage;
import net.momirealms.sparrow.sync.storage.mysql.MysqlSchema;
import net.momirealms.sparrow.sync.storage.mysql.MysqlSchemaMigrator;
import net.momirealms.sparrow.sync.storage.mysql.MysqlStorageProvider;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.mariadb.jdbc.MariaDbDataSource;

import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import java.util.concurrent.Executor;

@ApiStatus.Internal
public final class MariaDbStorageProvider extends MysqlStorageProvider {

    public MariaDbStorageProvider(
            @NotNull PluginConfig.MariaDbOptions options,
            @NotNull SnapshotDataCodec codec,
            @NotNull PlayerSerialExecutor serialExecutor,
            @NotNull Executor asyncExecutor,
            @NotNull SyncLogger logger
    ) {
        super(options, codec, serialExecutor, asyncExecutor, logger);
    }

    @Override
    public void initialize() {
        if (this.dataSource != null) throw new IllegalStateException("MariaDB storage is already initialized");
        // 表名会拼入 DDL, 限定字符集并为最长的业务表名预留长度.
        if (!this.options.tablePrefix().matches("[a-z0-9_]{0,52}")) {
            throw new IllegalArgumentException("MariaDB table prefix must contain at most 52 lowercase ASCII letters, digits or underscores");
        }
        HikariDataSource pool = new HikariDataSource();
        boolean ready = false;
        try {
            MariaDbDataSource source = new MariaDbDataSource() {
                private int loginTimeout;

                // Hikari 的建连等待秒数单独保存, URL 中的 connectTimeout 保持原值.
                @Override
                public void setLoginTimeout(int seconds) {
                    this.loginTimeout = seconds;
                }

                @Override
                public int getLoginTimeout() {
                    return this.loginTimeout;
                }
            };
            // 原 URL 的参数放在默认值之后, 由驱动解析并覆盖同名选项.
            int query = this.options.url().indexOf('?');
            String address = query < 0 ? this.options.url() : this.options.url().substring(0, query);
            String parameters = query < 0 ? "" : "&" + this.options.url().substring(query + 1);
            source.setUrl(address + "?connectTimeout=5000&socketTimeout=10000" + parameters);
            source.setUser(this.options.username());
            source.setPassword(this.options.password());
            // 每条连接使用读已提交隔离级别和严格写入模式.
            pool.setDataSource(source);
            pool.setPoolName("sparrow-sync-mariadb");
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
                        @NotNull
                        @Override
                        protected Argument build(UUID value, ConfigRegistry config) {
                            byte[] bytes = UUIDUtils.toBytes(value);
                            return (position, statement, context) -> statement.setBytes(position, bytes);
                        }
                    })
                    .registerRowMapper(SnapshotRow.class, new SnapshotRowMapper())
                    .registerRowMapper(SnapshotMeta.class, (result, context) -> SnapshotRowMapper.readMeta(result));
            new MysqlSchemaMigrator(this.logger, MysqlSchema.CURRENT_VERSION, MysqlSchema::initialize, MIGRATIONS).migrate(connected, this.options.tablePrefix());
            MysqlMapStorage mapStorage = new MysqlMapStorage(connected, this.options.tablePrefix(), this.asyncExecutor);
            // 所有准备成功后转交连接池所有权.
            this.dataSource = pool;
            this.jdbi = connected;
            this.maps = mapStorage;
            ready = true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize MariaDB storage", exception);
        } finally {
            if (!ready) pool.close();
        }
    }
}
