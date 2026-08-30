package net.momirealms.sparrow.sync.message;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
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
    private volatile StatefulRedisConnection<byte[], byte[]> connection;

    public RedisConnector(@NotNull PluginConfig.RedisOptions options, @NotNull SyncLogger logger) {
        this.options = options;
        this.logger = logger;
    }

    public void initialize() {
        this.client = RedisClient.create(this.buildUri());
        this.connection = this.client.connect(ByteArrayCodec.INSTANCE);
        this.logger.info(LogCategory.REDIS, LogConstants.REDIS_READY);
    }

    private RedisURI buildUri() {
        RedisURI uri = RedisURI.create(this.options.url());
        if (!this.options.password().isEmpty()) {
            String username = this.options.username().isEmpty() ? null : this.options.username();
            uri.setCredentialsProvider(RedisCredentialsProvider.from(() -> RedisCredentials.just(username, this.options.password())));
        }
        return uri;
    }

    @NotNull
    public StatefulRedisConnection<byte[], byte[]> connection() {
        return this.connection;
    }

    public boolean available() {
        StatefulRedisConnection<byte[], byte[]> connection = this.connection;
        return connection != null && connection.isOpen();
    }

    public void shutdown() {
        StatefulRedisConnection<byte[], byte[]> connection = this.connection;
        if (connection != null) connection.close();
        RedisClient client = this.client;
        if (client != null) client.shutdown(0, 2, TimeUnit.SECONDS);
    }
}
