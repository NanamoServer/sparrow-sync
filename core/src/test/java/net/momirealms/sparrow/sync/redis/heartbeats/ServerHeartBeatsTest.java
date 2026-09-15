package net.momirealms.sparrow.sync.redis.heartbeats;

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.test.RedisTestSupport;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.plugin.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.sync.redis.MessageBrokerManager;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerHeartBeatsTest {
    private static final long HEARTBEAT_INTERVAL = 200;
    private static final long HEARTBEAT_TTL = 600;
    private static final long PROBE_WAIT = 500;

    private final SyncLogger logger = new SyncLogger(new QuietLogger());
    private final ScheduledThreadPoolExecutor heartbeatExecutor = new ScheduledThreadPoolExecutor(1);
    private final ServerHeartBeats.HeartbeatScheduler scheduler = (task, intervalMillis) -> {
        ScheduledFuture<?> future = this.heartbeatExecutor.scheduleAtFixedRate(task, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return new SchedulerTask() {

            @Override
            public void cancel() {
                future.cancel(false);
            }

            @Override
            public boolean cancelled() {
                return future.isCancelled();
            }
        };
    };

    private RedisConnector connectorA;
    private RedisConnector connectorB;
    private RedisConnector connectorC;
    private MessageBrokerManager brokerA;
    private MessageBrokerManager brokerB;
    private MessageBrokerManager brokerC;
    private RedisClient inspector;
    private StatefulRedisConnection<String, String> inspection;

    @BeforeAll
    void connect() {
        PluginConfig.RedisOptions options = RedisTestSupport.options(2);
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
        this.brokerA = new MessageBrokerManager(this.connectorA, "dup", this.logger);
        this.brokerA.initialize();
        this.brokerB = new MessageBrokerManager(this.connectorB, "dup", this.logger);
        this.brokerB.initialize();
        this.brokerC = new MessageBrokerManager(this.connectorC, "stale", this.logger);
        this.brokerC.initialize();
        this.inspector = RedisClient.create(options.url());
        this.inspection = this.inspector.connect();
    }

    @AfterAll
    void disconnect() {
        this.heartbeatExecutor.shutdownNow();
        if (this.inspection != null) {
            List<String> keys = this.inspection.sync().keys("sparrow-sync:*");
            if (!keys.isEmpty()) this.inspection.sync().del(keys.toArray(String[]::new));
            this.inspection.close();
        }
        if (this.inspector != null) this.inspector.shutdown(0, 2, TimeUnit.SECONDS);
        if (this.brokerA != null) this.brokerA.shutdown();
        if (this.brokerB != null) this.brokerB.shutdown();
        if (this.brokerC != null) this.brokerC.shutdown();
        if (this.connectorA != null) this.connectorA.shutdown();
        if (this.connectorB != null) this.connectorB.shutdown();
        if (this.connectorC != null) this.connectorC.shutdown();
    }

    @Test
    void freshIdentityRegistersHeartbeatsAndUnregisters() throws InterruptedException {
        ServerHeartBeats registry = this.registry(this.connectorA, this.brokerA, "fresh");
        assertTrue(registry.initialize());
        String registered = this.inspection.sync().get(this.serverKey("fresh"));
        assertNotNull(registered, "heartbeat key should exist after registration");
        Thread.sleep(HEARTBEAT_TTL * 2);
        assertEquals(registered, this.inspection.sync().get(this.serverKey("fresh")), "heartbeat should keep the same token alive");
        registry.shutdown();
        Thread.sleep(HEARTBEAT_TTL + 200);
        assertNull(this.inspection.sync().get(this.serverKey("fresh")), "the identity must be gone after shutdown");
    }

    @Test
    void liveHolderRejectsTheDuplicateIdentity() {
        ServerHeartBeats holder = this.registry(this.connectorA, this.brokerA, "dup");
        assertTrue(holder.initialize());
        String holderToken = this.inspection.sync().get(this.serverKey("dup"));
        try {
            ServerHeartBeats duplicate = this.registry(this.connectorB, this.brokerB, "dup");
            ServerProbeMessage.registry(holder);
            assertFalse(duplicate.initialize(), "a live holder must reject the duplicate");
            assertEquals(holderToken, this.inspection.sync().get(this.serverKey("dup")), "the holder identity must stay untouched");
        } finally {
            holder.shutdown();
        }
    }

    @Test
    void staleIdentityIsSeizedAfterSilence() {
        this.inspection.sync().set(this.serverKey("stale"), "dead-token");
        ServerHeartBeats registry = this.registry(this.connectorC, this.brokerC, "stale");
        try {
            assertTrue(registry.initialize(), "a silent identity must be seized");
            String value = this.inspection.sync().get(this.serverKey("stale"));
            assertNotNull(value);
            assertNotEquals("dead-token", value, "the stale token must be replaced");
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void staleIdentityExpiringDuringProbeCanRegister() {
        String key = this.serverKey("expiring");
        ServerHeartBeats registry = this.registry(this.connectorC, this.brokerC, "expiring");
        this.inspection.sync().set(key, "dead-token", SetArgs.Builder.px(PROBE_WAIT / 2));
        long started = System.nanoTime();
        try {
            assertTrue(registry.initialize(), "an identity that expires during the probe must be claimable");
            assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(PROBE_WAIT), "registration must have entered the stale identity probe");
            String value = this.inspection.sync().get(key);
            assertNotNull(value);
            assertNotEquals("dead-token", value);
            assertTrue(this.inspection.sync().pttl(key) > 0, "the new identity must have a TTL");
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void identityReplacedDuringProbeIsNotSeized() throws Exception {
        String key = this.serverKey("replaced");
        ServerHeartBeats registry = this.registry(this.connectorC, this.brokerC, "replaced");
        this.inspection.sync().set(key, "dead-token");
        ScheduledFuture<?> replacement = this.heartbeatExecutor.schedule(
                () -> this.inspection.sync().set(key, "new-holder-token"), PROBE_WAIT / 2, TimeUnit.MILLISECONDS);
        try {
            assertFalse(registry.initialize(), "a different token acquired during the probe must be preserved");
            replacement.get(2, TimeUnit.SECONDS);
            assertEquals("new-holder-token", this.inspection.sync().get(key));
        } finally {
            replacement.cancel(false);
            registry.shutdown();
            this.inspection.sync().del(key);
        }
    }

    @Test
    void registrationSweepsOwnStaleLocksOnly() {
        UUID mineA = UUID.randomUUID();
        UUID mineB = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        String bystanderValue = "innocent:" + UUID.randomUUID();
        this.inspection.sync().set(this.lockKey(mineA), "sweeper:" + UUID.randomUUID());
        this.inspection.sync().set(this.lockKey(mineB), "sweeper:" + UUID.randomUUID());
        this.inspection.sync().set(this.lockKey(bystander), bystanderValue);
        ServerHeartBeats registry = this.registry(this.connectorA, this.brokerA, "sweeper");
        try {
            assertTrue(registry.initialize());
            assertNull(this.inspection.sync().get(this.lockKey(mineA)), "own stale lock should be swept");
            assertNull(this.inspection.sync().get(this.lockKey(mineB)), "own stale lock should be swept");
            assertEquals(bystanderValue, this.inspection.sync().get(this.lockKey(bystander)), "locks of other servers must survive");
        } finally {
            registry.shutdown();
            this.inspection.sync().del(this.lockKey(bystander));
        }
    }

    @Test
    void sameServerIdCanRegisterInDifferentRedisDatabases() {
        RedisConnector other = new RedisConnector(RedisTestSupport.options(5), this.logger);
        MessageBrokerManager otherBroker = new MessageBrokerManager(other, "dup", this.logger);
        ServerHeartBeats localRegistry = this.registry(this.connectorA, this.brokerA, "dup");
        ServerHeartBeats otherRegistry = null;
        try {
            other.initialize();
            otherBroker.initialize();
            otherRegistry = this.registry(other, otherBroker, "dup");
            assertTrue(localRegistry.initialize());
            assertTrue(otherRegistry.initialize());
            String localToken = this.inspection.sync().get(this.serverKey("dup"));
            byte[] otherToken = other.connection().sync().get(this.serverKey("dup").getBytes(StandardCharsets.UTF_8));
            assertNotNull(localToken);
            assertNotNull(otherToken);
            assertNotEquals(localToken, new String(otherToken, StandardCharsets.UTF_8));
        } finally {
            localRegistry.shutdown();
            if (otherRegistry != null) otherRegistry.shutdown();
            otherBroker.shutdown();
            other.shutdown();
        }
    }

    @Test
    void sweepMatchesTheExactServerId() {
        UUID prefixed = UUID.randomUUID();
        this.inspection.sync().set(this.lockKey(prefixed), "srv2:" + UUID.randomUUID());
        SessionLock lock = new SessionLock(this.connectorA, "srv");
        try {
            assertEquals(0, lock.sweepStaleLocks(), "srv must not match the srv2 prefix");
            assertNotNull(this.inspection.sync().get(this.lockKey(prefixed)));
        } finally {
            this.inspection.sync().del(this.lockKey(prefixed));
        }
    }

    private ServerHeartBeats registry(RedisConnector connector, MessageBrokerManager broker, String serverId) {
        SessionLock lock = new SessionLock(connector, serverId);
        return new ServerHeartBeats(connector, broker.broker(), lock, serverId, this.logger, this.scheduler, HEARTBEAT_INTERVAL, HEARTBEAT_TTL, PROBE_WAIT);
    }

    private String serverKey(String serverId) {
        return "sparrow-sync:server:" + serverId;
    }

    private String lockKey(UUID player) {
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
