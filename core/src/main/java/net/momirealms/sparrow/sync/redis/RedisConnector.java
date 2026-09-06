package net.momirealms.sparrow.sync.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
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
        this.logger.info(LogCategory.LIFECYCLE, LogConstants.REDIS_READY);
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
