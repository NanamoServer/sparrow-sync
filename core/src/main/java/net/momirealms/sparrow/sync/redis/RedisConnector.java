package net.momirealms.sparrow.sync.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import net.momirealms.sparrow.redis.messagebroker.connection.PubSubRedisConnection;
import net.momirealms.sparrow.redis.messagebroker.connection.RedisConnection;
import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

public final class RedisConnector {
    private final PluginConfig.RedisOptions options;
    private final SyncLogger logger;

    private volatile RedisClient client;
    private volatile StatefulRedisConnection<byte[], byte[]> connection;  // 锁命令
    private volatile PubSubRedisConnection brokerConnection;              // 消息 broker 的命令与 Pub/Sub

    public RedisConnector(@NotNull PluginConfig.RedisOptions options, @NotNull SyncLogger logger) {
        this.options = options;
        this.logger = logger;
    }

    public void initialize() {
        this.client = RedisClient.create(buildUri(this.options));
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
