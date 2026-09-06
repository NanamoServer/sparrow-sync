package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.redis.MessageBrokerManager;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.KillArgs;
import java.net.SocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisMapCacheTest {
    private final String cluster = "maps-it-" + UUID.randomUUID();
    private RedisConnector connector;
    private MessageBrokerManager broker;
    private RedisMapCache source;
    private RedisMapCache foreign;

    @BeforeAll
    void connect() {
        SyncLogger logger = MapFlowTestSupport.logger(new ArrayList<>());
        this.connector = new RedisConnector(new PluginConfig.RedisOptions(), logger);
        try {
            this.connector.initialize();
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(false, "local Redis unavailable: " + exception.getMessage());
        }
        this.broker = new MessageBrokerManager(this.connector, this.cluster, "map-tests", logger);
        this.broker.initialize();
        this.source = new RedisMapCache(this.connector.connection().async(), this.broker.broker(), this.cluster, "A-world", ForkJoinPool.commonPool());
        this.foreign = new RedisMapCache(this.connector.connection().async(), this.broker.broker(), this.cluster, "B-world", ForkJoinPool.commonPool());
    }

    @AfterAll
    void close() {
        MapInvalidationMessage.listener(null);
        if (this.connector != null && this.connector.available()) {
            this.connector.connection().sync().del(this.key(-1), this.key(-2));
        }
        if (this.broker != null) this.broker.shutdown();
        if (this.connector != null) this.connector.shutdown();
    }

    @Test
    void publishesReadableContentThenBroadcastsAndForeignServerCanOnlyReadOrRenew() throws Exception {
        CountDownLatch notified = new CountDownLatch(1);
        MapInvalidationMessage.listener(id -> {
            if (id == -1) notified.countDown();
        });
        StoredMap stored = new StoredMap(new MapIdentity(this.cluster, MapFlowTestSupport.SOURCE, -1), MapFlowTestSupport.map(7).data());
        this.source.publish(stored).get(5, TimeUnit.SECONDS);
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        assertEquals(stored, this.foreign.find(-1).get(3, TimeUnit.SECONDS).orElseThrow());
        assertTrue(this.connector.connection().sync().ttl(this.key(-1)) > 604_790);
        assertThrows(CompletionException.class, () -> this.foreign.publish(stored).join());
        assertEquals(stored, this.source.find(-1).join().orElseThrow());

        this.connector.connection().sync().expire(this.key(-1), 1);
        assertTrue(this.foreign.touch(-1).join());
        assertTrue(this.connector.connection().sync().ttl(this.key(-1)) > 604_790);
        this.connector.connection().sync().del(this.key(-1));
        assertFalse(this.foreign.touch(-1).join());
        assertEquals(0L, this.connector.connection().sync().exists(this.key(-1)));
    }

    @Test
    void isolatesClustersAndRejectsCorruptPayloads() {
        StoredMap stored = new StoredMap(new MapIdentity(this.cluster, MapFlowTestSupport.SOURCE, -2), MapFlowTestSupport.map(9).data());
        this.source.publish(stored).join();
        RedisMapCache otherCluster = new RedisMapCache(this.connector.connection().async(), this.broker.broker(), this.cluster + "-other", "A-world", ForkJoinPool.commonPool());
        assertTrue(otherCluster.find(-2).join().isEmpty());
        assertThrows(CompletionException.class, () -> otherCluster.publish(stored).join());
        this.connector.connection().sync().set(this.key(-2), new byte[]{1, 2, 3});
        assertThrows(CompletionException.class, () -> this.foreign.find(-2).join());
    }

    @Test
    void connectionListenerReceivesAutomaticReconnectOfItsOwnConnection() throws Exception {
        CountDownLatch reconnected = new CountDownLatch(1);
        RedisConnectionStateListener listener = new RedisConnectionStateListener() {
            @Override
            public void onRedisConnected(RedisChannelHandler<?, ?> connection, SocketAddress address) {
                reconnected.countDown();
            }
        };
        this.connector.addConnectionListener(listener);
        RedisClient inspector = RedisClient.create(new PluginConfig.RedisOptions().url());
        try (var inspection = inspector.connect()) {
            long ownConnection = this.connector.connection().sync().clientId();
            assertEquals(1L, inspection.sync().clientKill(KillArgs.Builder.id(ownConnection)));
            assertTrue(reconnected.await(5, TimeUnit.SECONDS));
            assertEquals("PONG", this.connector.connection().async().ping().toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally {
            this.connector.removeConnectionListener(listener);
            inspector.shutdown();
        }
    }

    private byte[] key(int id) {
        return ("sparrow-sync:maps:" + HexFormat.of().formatHex(this.cluster.getBytes(StandardCharsets.UTF_8)) + ":" + id).getBytes(StandardCharsets.UTF_8);
    }
}
