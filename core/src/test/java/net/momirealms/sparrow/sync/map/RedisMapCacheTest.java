package net.momirealms.sparrow.sync.map;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.momirealms.sparrow.sync.map.cache.RedisMapCache;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.map.message.MapInvalidationMessage;
import net.momirealms.sparrow.sync.test.RedisTestSupport;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.redis.MessageBrokerManager;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisMapCacheTest {
    @RegisterExtension
    private final MapFlowTestSupport.PluginInstance pluginInstance = new MapFlowTestSupport.PluginInstance();

    private RedisConnector connector;
    private MessageBrokerManager broker;
    private RedisMapCache source;
    private RedisMapCache foreign;

    @BeforeAll
    void connect() {
        SyncLogger logger = MapFlowTestSupport.logger(new ArrayList<>());
        this.connector = new RedisConnector(RedisTestSupport.options(3), logger);
        try {
            this.connector.initialize();
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(false, "local Redis unavailable: " + exception.getMessage());
        }
        this.broker = new MessageBrokerManager(this.connector, "map-tests", logger);
        this.broker.initialize();
        this.source = new RedisMapCache(this.connector.connection().async(), this.broker.broker(), ForkJoinPool.commonPool());
        this.foreign = new RedisMapCache(this.connector.connection().async(), this.broker.broker(), ForkJoinPool.commonPool());
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
        StoredMap stored = new StoredMap(new MapIdentity(MapFlowTestSupport.SOURCE, -1), MapFlowTestSupport.map(7).data());
        this.source.publish(stored).get(5, TimeUnit.SECONDS);
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        assertEquals(stored, this.foreign.find(-1).get(3, TimeUnit.SECONDS).orElseThrow());
        assertTrue(this.connector.connection().sync().ttl(this.key(-1)) > 604_790);
        MapPublisher foreignPublisher = new MapPublisher(new MapFlowTestSupport.Storage(), this.foreign, "B-world", ForkJoinPool.commonPool());
        assertThrows(CompletionException.class, () -> foreignPublisher.publish(stored.identity().source(), stored.data()).join());
        foreignPublisher.close();
        assertEquals(stored, this.source.find(-1).join().orElseThrow());

        this.connector.connection().sync().expire(this.key(-1), 1);
        assertTrue(this.foreign.touch(-1).join());
        assertTrue(this.connector.connection().sync().ttl(this.key(-1)) > 604_790);
        this.connector.connection().sync().del(this.key(-1));
        assertFalse(this.foreign.touch(-1).join());
        assertEquals(0L, this.connector.connection().sync().exists(this.key(-1)));
    }

    @Test
    void sourcePublicationRepairsLocalIdentityThroughRedisNotification() throws Exception {
        MapFlowTestSupport.NativeMaps nativeMaps = new MapFlowTestSupport.NativeMaps();
        MapFlowTestSupport.Storage storage = new MapFlowTestSupport.Storage();
        storage.current = MapFlowTestSupport.map(7);
        SyncLogger logger = MapFlowTestSupport.logger(new ArrayList<>());
        CountDownLatch refreshed = new CountDownLatch(1);
        MapReceiver receiver = new MapReceiver(storage, this.foreign, nativeMaps.adapter, nativeMaps.server, "B-world", logger);
        MapFlowTestSupport.scheduler(ForkJoinPool.commonPool(), task -> {
            task.run();
            if (nativeMaps.replica.colors[0] == 8) {
                refreshed.countDown();
            }
        });
        MapPublisher publisher = new MapPublisher(storage, this.source, "A-world", ForkJoinPool.commonPool());
        try {
            this.connector.connection().sync().del(this.key(-1));
            receiver.receive(MapFlowTestSupport.IDENTITY).get(3, TimeUnit.SECONDS);
            NmsPlayerFixture.set(MapItemSavedData.class, nativeMaps.replica, "dimension", Level.OVERWORLD);
            nativeMaps.replica.setDirty(false);
            MapInvalidationMessage.listener(receiver::refresh);
            // 来源发布经过真实 Redis 通知, 接收侧修正身份、更新像素并标脏.
            publisher.publish(MapFlowTestSupport.SOURCE, MapFlowTestSupport.map(8).data()).get(5, TimeUnit.SECONDS);
            assertTrue(refreshed.await(3, TimeUnit.SECONDS));
            assertSame(nativeMaps.replica, nativeMaps.level.getMapData(new MapId(-1)));
            assertEquals(MapFlowTestSupport.IDENTITY.replicaDimension(), MapFlowTestSupport.dimension(nativeMaps.replica));
            assertEquals(8, nativeMaps.replica.colors[0]);
            assertTrue(nativeMaps.replica.isDirty());
        } finally {
            MapInvalidationMessage.listener(null);
            receiver.close();
            publisher.close();
        }
    }

    @Test
    void rejectsCorruptPayloads() {
        StoredMap stored = new StoredMap(new MapIdentity(MapFlowTestSupport.SOURCE, -2), MapFlowTestSupport.map(9).data());
        this.source.publish(stored).join();
        this.connector.connection().sync().set(this.key(-2), new byte[]{1, 2, 3});
        assertThrows(CompletionException.class, () -> this.foreign.find(-2).join());
    }

    @Test
    void separatesCachesLocksAndMessageChannelsByRedisDatabase() {
        SyncLogger logger = MapFlowTestSupport.logger(new ArrayList<>());
        RedisConnector other = new RedisConnector(RedisTestSupport.options(4), logger);
        MessageBrokerManager otherBroker = new MessageBrokerManager(other, "other-map-tests", logger);
        try {
            other.initialize();
            otherBroker.initialize();
            assertEquals(3, this.connector.database());
            assertEquals(4, other.database());
            assertFalse(Arrays.equals(this.broker.broker().channel(), otherBroker.broker().channel()));
            RedisMapCache otherCache = new RedisMapCache(other.connection().async(), otherBroker.broker(), ForkJoinPool.commonPool());
            StoredMap stored = new StoredMap(MapFlowTestSupport.IDENTITY, MapFlowTestSupport.map(9).data());
            this.source.publish(stored).join();
            assertTrue(otherCache.find(-1).join().isEmpty());
            StoredMap otherStored = new StoredMap(stored.identity(), MapFlowTestSupport.map(10).data());
            otherCache.publish(otherStored).join();
            assertEquals(stored, this.source.find(-1).join().orElseThrow());
            assertEquals(otherStored, otherCache.find(-1).join().orElseThrow());
            // 两个 broker 均已订阅, 本库广播只有本库的一个订阅者收到.
            assertEquals(1L, this.connector.connection().sync().publish(this.broker.broker().channel(), this.broker.broker().encode(new MapInvalidationMessage(-1))));
            assertEquals(1L, other.connection().sync().publish(otherBroker.broker().channel(), otherBroker.broker().encode(new MapInvalidationMessage(-1))));
            UUID player = UUID.randomUUID();
            SessionLock localLock = new SessionLock(this.connector, "map-tests");
            SessionLock otherLock = new SessionLock(other, "map-tests");
            String localValue = assertInstanceOf(SessionLock.AcquireOutcome.Acquired.class, localLock.tryAcquire(player).join()).value();
            String otherValue = assertInstanceOf(SessionLock.AcquireOutcome.Acquired.class, otherLock.tryAcquire(player).join()).value();
            assertTrue(localLock.release(player, localValue).join());
            assertEquals(otherValue, assertInstanceOf(SessionLock.AcquireOutcome.Held.class, otherLock.tryAcquire(player).join()).value());
            assertTrue(otherLock.release(player, otherValue).join());
        } finally {
            if (other.available()) other.connection().sync().del(this.key(-1));
            otherBroker.shutdown();
            other.shutdown();
        }
    }

    private byte[] key(int id) {
        return ("sparrow-sync:maps:" + id).getBytes(StandardCharsets.UTF_8);
    }
}
