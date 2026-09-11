package net.momirealms.sparrow.sync.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import net.momirealms.sparrow.redis.messagebroker.connection.PubSubRedisConnection;
import net.momirealms.sparrow.redis.messagebroker.connection.RedisConnection;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

public final class RedisConnector {
    private static final RedisServerVersion MINIMUM_SERVER_VERSION = new RedisServerVersion(8, 0, 0);
    private static final String SERVER_INFO_SECTION = "server";

    private SparrowSync plugin;
    private PluginConfig.RedisOptions options;
    private SyncLogger logger;
    private int database; // 启动连接实际选用的数据库编号, 消息频道也按此隔离

    private volatile RedisClient client;
    private volatile StatefulRedisConnection<byte[], byte[]> connection;  // 锁命令
    private volatile PubSubRedisConnection brokerConnection;              // 消息 broker 的命令与 Pub/Sub

    public RedisConnector(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public RedisConnector(@NotNull PluginConfig.RedisOptions options, @NotNull SyncLogger logger) {
        this.options = options;
        this.logger = logger;
    }

    /** 读取启动配置并建立 Redis 连接. */
    public void onLoad() {
        this.options = PluginConfig.redis();
        this.logger = this.plugin.logger();
        this.initialize();
    }

    public void initialize() {
        RedisURI uri = buildUri(this.options);
        this.database = uri.getDatabase();
        this.client = RedisClient.create(uri);
        this.connection = this.client.connect(ByteArrayCodec.INSTANCE);
        this.brokerConnection = new PubSubRedisConnection(this.client);
        this.verifyRedisVersion();
        this.logger.info(LogCategory.LIFECYCLE, LogConstants.REDIS_READY);
    }

    // 版本低于最低支持版本时在连接阶段停下, 抛给插件层记录并关闭服务器
    private void verifyRedisVersion() {
        RedisServerVersion reported;
        try {
            reported = RedisServerVersion.parse(this.connection.sync().info(SERVER_INFO_SECTION));
        } catch (RedisException exception) {
            // INFO 可能被 ACL 或中间代理禁用, 探测失败仅做警告
            this.logger.warnWithFileCause(LogCategory.LIFECYCLE, null, null, exception, LogConstants.REDIS_VERSION_CHECK_FAILED);
            return;
        }
        if (reported == null) {
            this.logger.warn(LogCategory.LIFECYCLE, LogConstants.REDIS_VERSION_CHECK_FAILED);
            return;
        }
        if (reported.atLeast(MINIMUM_SERVER_VERSION)) return;
        this.logger.error(LogCategory.LIFECYCLE, LogConstants.REDIS_VERSION_UNSUPPORTED, reported.toString(), MINIMUM_SERVER_VERSION.toString());
        throw new IllegalStateException("Redis server version " + reported + " is not supported, Redis " + MINIMUM_SERVER_VERSION + " or later is required");
    }

    private static RedisURI buildUri(PluginConfig.RedisOptions options) {
        RedisURI uri = RedisURI.create(options.url());
        if (!options.password().isEmpty()) {
            String username = options.username().isEmpty() ? null : options.username();
            uri.setCredentialsProvider(RedisCredentialsProvider.from(() -> RedisCredentials.just(username, options.password())));
        }
        return uri;
    }

    @NotNull
    public StatefulRedisConnection<byte[], byte[]> connection() {
        return this.connection;
    }

    @NotNull
    public RedisConnection brokerConnection() {
        return this.brokerConnection;
    }

    public int database() {
        return this.database;
    }

    public boolean available() {
        StatefulRedisConnection<byte[], byte[]> connection = this.connection;
        return connection != null && connection.isOpen();
    }

    public void shutdown() {
        PubSubRedisConnection brokerConnection = this.brokerConnection;
        if (brokerConnection != null) brokerConnection.close();
        StatefulRedisConnection<byte[], byte[]> connection = this.connection;
        if (connection != null) connection.close();
        RedisClient client = this.client;
        if (client != null) client.shutdown(0, 2, TimeUnit.SECONDS);
    }
}
