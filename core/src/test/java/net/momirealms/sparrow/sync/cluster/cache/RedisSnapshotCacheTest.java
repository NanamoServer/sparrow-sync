package net.momirealms.sparrow.sync.cluster.cache;

import net.momirealms.sparrow.sync.cluster.cache.RedisSnapshotCache;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.RedisTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisSnapshotCacheTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000cace");

    private final SyncLogger logger = new SyncLogger(new QuietLogger());
    private RedisConnector connector;
    private RedisSnapshotCache cache;

    @BeforeAll
    void connect() {
        this.connector = new RedisConnector(RedisTestSupport.options(5), this.logger);
        try {
            this.connector.initialize();
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(false, "local Redis unavailable: " + exception.getMessage());
        }
        // 构造器只接插件实例, 这里逐字段注入测试用的依赖
        this.cache = NmsPlayerFixture.allocate(RedisSnapshotCache.class);
        NmsPlayerFixture.set(RedisSnapshotCache.class, this.cache, "connector", this.connector);
        NmsPlayerFixture.set(RedisSnapshotCache.class, this.cache, "codec", new BinarySnapshotCodec(CompressorRegistry.DEFLATE));
        NmsPlayerFixture.set(RedisSnapshotCache.class, this.cache, "logger", this.logger);
        NmsPlayerFixture.set(RedisSnapshotCache.class, this.cache, "executor", ForkJoinPool.commonPool());
    }

    @AfterAll
    void close() {
        if (this.connector != null && this.connector.available()) {
            this.connector.connection().sync().del(this.key());
        }
        if (this.connector != null) this.connector.shutdown();
    }

    @Test
    void publishesThenTakesTheEntryExactlyOnce() {
        Snapshot snapshot = this.snapshot();
        this.cache.publish(snapshot, 15).join();
        assertEquals(snapshot, this.cache.consume(PLAYER).join().orElseThrow());
        // GETDEL 取回的同时已经删掉条目
        assertEquals(0L, this.connector.connection().sync().exists(this.key()));
        assertTrue(this.cache.consume(PLAYER).join().isEmpty());
    }

    @Test
    void entryExpiresOnItsOwn() {
        this.cache.publish(this.snapshot(), 15).join();
        long ttl = this.connector.connection().sync().ttl(this.key());
        assertTrue(ttl > 0 && ttl <= 15, "ttl was " + ttl);
    }

    @Test
    void unreadablePayloadFallsBackToAnEmptyResult() {
        this.connector.connection().sync().set(this.key(), new byte[]{1, 2, 3});
        assertTrue(this.cache.consume(PLAYER).join().isEmpty());
        assertEquals(0L, this.connector.connection().sync().exists(this.key()));
    }

    @Test
    void invalidateRemovesTheEntry() {
        this.cache.publish(this.snapshot(), 15).join();
        this.cache.invalidate(PLAYER).join();
        assertEquals(0L, this.connector.connection().sync().exists(this.key()));
        assertTrue(this.cache.consume(PLAYER).join().isEmpty());
    }

    @Test
    void consumingWithoutAnEntryReturnsEmpty() {
        assertTrue(this.cache.consume(PLAYER).join().isEmpty());
    }

    private Snapshot snapshot() {
        SnapshotMeta meta = SnapshotMeta.builder()
                .player(PLAYER)
                .timestamp(System.currentTimeMillis())
                .cause(SaveCause.DISCONNECT)
                .server("test")
                .mcDataVersion(1)
                .build();
        return new Snapshot(meta, Map.of());
    }

    private byte[] key() {
        return ("sparrow-sync:latest-snapshot:" + PLAYER).getBytes(StandardCharsets.UTF_8);
    }

    private static final class QuietLogger implements PluginLogger {

        @Override
        public void info(String s) {
        }

        @Override
        public void warn(String s) {
        }

        @Override
        public void warn(String s, Throwable t) {
        }

        @Override
        public void error(String s) {
        }

        @Override
        public void error(String s, Throwable t) {
        }
    }
}
