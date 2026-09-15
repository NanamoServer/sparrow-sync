package net.momirealms.sparrow.sync.cluster;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.test.RedisTestSupport;
import net.momirealms.sparrow.sync.cluster.SessionLock.AcquireOutcome;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionLockTest {
    private static final long LOCK_TTL_MILLIS = TimeUnit.DAYS.toMillis(15);

    private final SyncLogger logger = new SyncLogger(new QuietLogger());
    private RedisConnector connectorA;
    private RedisConnector connectorB;
    private RedisConnector connectorC;
    private SessionLock lockA;
    private SessionLock lockB;
    private SessionLock lockC;
    private RedisClient inspector;
    private StatefulRedisConnection<String, String> inspection;

    @BeforeAll
    void connect() {
        PluginConfig.RedisOptions options = RedisTestSupport.options(0);
        this.connectorA = new RedisConnector(options, this.logger);
        try {
            this.connectorA.initialize();
        } catch (Exception exception) {
            Assumptions.assumeTrue(false, "local Redis is not reachable: " + exception.getMessage());
        }
        this.connectorB = new RedisConnector(options, this.logger);
        this.connectorB.initialize();
        this.connectorC = new RedisConnector(options, this.logger);
        this.connectorC.initialize();
        this.lockA = new SessionLock(this.connectorA, "serverA");
        this.lockB = new SessionLock(this.connectorB, "serverB");
        this.lockC = new SessionLock(this.connectorC, "serverC");
        this.inspector = RedisClient.create(options.url());
        this.inspection = this.inspector.connect();
    }

    @AfterAll
    void disconnect() {
        if (this.inspection != null) {
            List<String> keys = this.inspection.sync().keys("sparrow-sync:lock:*");
            if (!keys.isEmpty()) this.inspection.sync().del(keys.toArray(String[]::new));
            this.inspection.close();
        }
        if (this.inspector != null) this.inspector.shutdown(0, 2, TimeUnit.SECONDS);
        if (this.connectorA != null) this.connectorA.shutdown();
        if (this.connectorB != null) this.connectorB.shutdown();
        if (this.connectorC != null) this.connectorC.shutdown();
    }

    @Test
    void acquireGrantsFreshLockWithGarbageCollectionTtl() throws Exception {
        UUID player = UUID.randomUUID();

        AcquireOutcome outcome = this.lockA.tryAcquire(player).get(5, TimeUnit.SECONDS);

        AcquireOutcome.Acquired acquired = assertInstanceOf(AcquireOutcome.Acquired.class, outcome);
        LockValue value = LockValue.parse(acquired.value());
        assertNotNull(value);
        assertEquals("serverA", value.serverId());
        long ttl = this.inspection.sync().pttl(this.key(player));
        assertTrue(ttl > 0 && ttl <= LOCK_TTL_MILLIS, "unexpected ttl " + ttl);
    }

    @Test
    void acquireReportsCurrentHolderWhenHeld() throws Exception {
        UUID player = UUID.randomUUID();
        String held = this.acquire(this.lockA, player);

        AcquireOutcome fromB = this.lockB.tryAcquire(player).get(5, TimeUnit.SECONDS);
        AcquireOutcome fromA = this.lockA.tryAcquire(player).get(5, TimeUnit.SECONDS);

        assertEquals(held, assertInstanceOf(AcquireOutcome.Held.class, fromB).value());
        assertEquals(held, assertInstanceOf(AcquireOutcome.Held.class, fromA).value());
    }

    @Test
    void releaseDeletesOwnLock() throws Exception {
        UUID player = UUID.randomUUID();
        String held = this.acquire(this.lockA, player);

        assertTrue(this.lockA.release(player, held).get(5, TimeUnit.SECONDS));

        assertInstanceOf(AcquireOutcome.Acquired.class, this.lockB.tryAcquire(player).get(5, TimeUnit.SECONDS));
    }

    @Test
    void releaseIgnoresForeignToken() throws Exception {
        UUID player = UUID.randomUUID();
        String held = this.acquire(this.lockA, player);

        assertFalse(this.lockA.release(player, "serverA:" + UUID.randomUUID()).get(5, TimeUnit.SECONDS));

        AcquireOutcome outcome = this.lockB.tryAcquire(player).get(5, TimeUnit.SECONDS);
        assertEquals(held, assertInstanceOf(AcquireOutcome.Held.class, outcome).value());
    }

    @Test
    void concurrentAcquireGrantsExactlyOne() throws Exception {
        for (int round = 0; round < 20; round++) {
            UUID player = UUID.randomUUID();

            CompletableFuture<AcquireOutcome> fromA = this.lockA.tryAcquire(player);
            CompletableFuture<AcquireOutcome> fromB = this.lockB.tryAcquire(player);
            AcquireOutcome first = fromA.get(5, TimeUnit.SECONDS);
            AcquireOutcome second = fromB.get(5, TimeUnit.SECONDS);

            AcquireOutcome winner = first instanceof AcquireOutcome.Acquired ? first : second;
            AcquireOutcome loser = winner == first ? second : first;
            String acquired = assertInstanceOf(AcquireOutcome.Acquired.class, winner).value();
            assertEquals(acquired, assertInstanceOf(AcquireOutcome.Held.class, loser).value());
        }
    }

    @Test
    void concurrentSeizeGrantsExactlyOne() throws Exception {
        UUID player = UUID.randomUUID();
        String observed = this.acquire(this.lockA, player);

        CompletableFuture<Optional<String>> fromB = this.lockB.seize(player, observed);
        CompletableFuture<Optional<String>> fromC = this.lockC.seize(player, observed);
        Optional<String> byB = fromB.get(5, TimeUnit.SECONDS);
        Optional<String> byC = fromC.get(5, TimeUnit.SECONDS);

        assertTrue(byB.isPresent() ^ byC.isPresent());
        String next = byB.orElseGet(byC::get);
        assertEquals(next, this.inspection.sync().get(this.key(player)));
    }

    @Test
    void staleReleaseCannotDeleteSeizedLock() throws Exception {
        UUID player = UUID.randomUUID();
        String original = this.acquire(this.lockA, player);
        String seized = this.lockB.seize(player, original).get(5, TimeUnit.SECONDS).orElseThrow();

        assertFalse(this.lockA.release(player, original).get(5, TimeUnit.SECONDS));

        assertEquals(seized, this.inspection.sync().get(this.key(player)));
        assertTrue(this.lockB.release(player, seized).get(5, TimeUnit.SECONDS));
    }

    @Test
    void seizeFailsWhenLockGone() throws Exception {
        UUID player = UUID.randomUUID();
        String observed = this.acquire(this.lockA, player);
        assertTrue(this.lockA.release(player, observed).get(5, TimeUnit.SECONDS));

        assertTrue(this.lockB.seize(player, observed).get(5, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void seizeFailsWhenValueChanged() throws Exception {
        UUID player = UUID.randomUUID();
        String stale = this.acquire(this.lockA, player);
        assertTrue(this.lockA.release(player, stale).get(5, TimeUnit.SECONDS));
        String current = this.acquire(this.lockB, player);

        assertTrue(this.lockC.seize(player, stale).get(5, TimeUnit.SECONDS).isEmpty());

        assertEquals(current, this.inspection.sync().get(this.key(player)));
    }

    private String acquire(SessionLock lock, UUID player) throws Exception {
        AcquireOutcome outcome = lock.tryAcquire(player).get(5, TimeUnit.SECONDS);
        return assertInstanceOf(AcquireOutcome.Acquired.class, outcome).value();
    }

    private String key(UUID player) {
        return "sparrow-sync:lock:" + player;
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
