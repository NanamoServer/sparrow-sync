package net.momirealms.sparrow.sync.message;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.redis.MessageBrokerManager;
import net.momirealms.sparrow.sync.session.cluster.*;
import net.momirealms.sparrow.sync.session.cluster.SessionLock.AcquireOutcome;
import net.momirealms.sparrow.sync.session.cluster.HandoffManager.HandoffOutcome;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 集成测试, 依赖本机 6379 端口的 Redis, 不可达时整类跳过.
// 两套 broker 与锁模拟持有服 serverA 和等锁服 serverB, appId 每次随机隔离
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HandoffManagerTest {
    private static final String CLUSTER = "it" + Long.toHexString(System.nanoTime());
    // 压缩节奏让判死场景秒级完成
    private static final long PROBE_INTERVAL = 100;
    private static final long PROBE_TIMEOUT = 300;
    private static final long DEAD_SILENCE = 600;

    private final SyncLogger logger = new SyncLogger(new QuietLogger());
    private final Set<UUID> sessionsA = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor probeExecutor = new ScheduledThreadPoolExecutor(1);

    private RedisConnector connectorA;
    private RedisConnector connectorB;
    private SessionLock lockA;
    private SessionLock lockB;
    private MessageBrokerManager brokerA;
    private MessageBrokerManager brokerB;
    private HandoffManager serviceA;
    private HandoffManager serviceB;
    private RedisClient inspector;
    private StatefulRedisConnection<String, String> inspection;

    @BeforeAll
    void connect() {
        PluginConfig.RedisOptions options = new PluginConfig.RedisOptions();
        this.connectorA = new RedisConnector(options, this.logger);
        try {
            this.connectorA.initialize();
        } catch (Exception exception) {
            Assumptions.assumeTrue(false, "local Redis is not reachable: " + exception.getMessage());
        }
        this.connectorB = new RedisConnector(options, this.logger);
        this.connectorB.initialize();
        this.lockA = new SessionLock(this.connectorA, CLUSTER, "serverA");
        this.lockB = new SessionLock(this.connectorB, CLUSTER, "serverB");
        this.brokerA = new MessageBrokerManager(this.connectorA, CLUSTER, "serverA", this.logger);
        this.brokerA.initialize();
        this.brokerB = new MessageBrokerManager(this.connectorB, CLUSTER, "serverB", this.logger);
        this.brokerB.initialize();
        HandoffManager.ProbeScheduler scheduler = (task, delayMillis) -> this.probeExecutor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        this.serviceA = new HandoffManager(this.brokerA.broker(), this.lockA, this.sessionsA::contains, scheduler, PROBE_INTERVAL, PROBE_TIMEOUT, DEAD_SILENCE);
        this.serviceB = new HandoffManager(this.brokerB.broker(), this.lockB, uuid -> false, scheduler, PROBE_INTERVAL, PROBE_TIMEOUT, DEAD_SILENCE);
        // 应答槽是单值的, 两个服务同 JVM 共存时显式指回持有服一侧
        HandoffRequestMessage.service(this.serviceA);
        this.inspector = RedisClient.create(options.url());
        this.inspection = this.inspector.connect();
    }

    @AfterAll
    void disconnect() {
        this.probeExecutor.shutdownNow();
        if (this.inspection != null) {
            List<String> keys = this.inspection.sync().keys("ss:" + CLUSTER + ":lock:*");
            if (!keys.isEmpty()) this.inspection.sync().del(keys.toArray(String[]::new));
            this.inspection.close();
        }
        if (this.inspector != null) this.inspector.shutdown(0, 2, TimeUnit.SECONDS);
        if (this.brokerA != null) this.brokerA.shutdown();
        if (this.brokerB != null) this.brokerB.shutdown();
        if (this.connectorA != null) this.connectorA.shutdown();
        if (this.connectorB != null) this.connectorB.shutdown();
    }

    @Test
    void probeAnsweredSavingWhileSessionAlive() throws Exception {
        UUID player = UUID.randomUUID();
        this.sessionsA.add(player);

        HandoffResponseMessage response = this.request(player);

        assertEquals(HandoffResponseMessage.Status.SAVING, response.status());
        this.sessionsA.remove(player);
    }

    @Test
    void probeAnsweredDoneWithSettledTimestamp() throws Exception {
        UUID player = UUID.randomUUID();
        this.serviceA.recordSettled(player, 1_756_300_000_777L);

        HandoffResponseMessage response = this.request(player);

        assertEquals(HandoffResponseMessage.Status.DONE, response.status());
        assertEquals(1_756_300_000_777L, response.timestamp());
    }

    @Test
    void probeAnsweredUnknownWithoutAnyRecord() throws Exception {
        HandoffResponseMessage response = this.request(UUID.randomUUID());

        assertEquals(HandoffResponseMessage.Status.UNKNOWN, response.status());
    }

    @Test
    void handoffCompletesAfterHolderSettles() throws Exception {
        UUID player = UUID.randomUUID();
        // A 侧持锁且会话在保存中
        String heldByA = this.acquire(this.lockA, player);
        this.sessionsA.add(player);

        CompletableFuture<HandoffOutcome> handoff = this.serviceB.awaitHandoff(player, heldByA, System.nanoTime() + TimeUnit.SECONDS.toNanos(8));
        // 让 B 至少吃到一轮 SAVING, 再按生产顺序 settle: 登记 -> 移除会话 -> 释放锁
        Thread.sleep(350);
        this.serviceA.recordSettled(player, 1_756_300_111_222L);
        this.sessionsA.remove(player);
        assertTrue(this.lockA.release(player, heldByA).get(5, TimeUnit.SECONDS));

        HandoffOutcome outcome = handoff.get(8, TimeUnit.SECONDS);

        assertEquals("done", outcome.method());
        assertTrue(outcome.lockValue().startsWith("serverB:"));
        assertEquals(outcome.lockValue(), this.inspection.sync().get(this.key(player)));
    }

    @Test
    void handoffSeizesSilentHolder() throws Exception {
        UUID player = UUID.randomUUID();
        // 锁值指向一台不存在的服务器, 探测得不到任何应答
        String ghost = "serverGhost:" + UUID.randomUUID();
        this.inspection.sync().set(this.key(player), ghost);

        HandoffOutcome outcome = this.serviceB.awaitHandoff(player, ghost, System.nanoTime() + TimeUnit.SECONDS.toNanos(8)).get(8, TimeUnit.SECONDS);

        assertEquals("seized", outcome.method());
        assertTrue(outcome.lockValue().startsWith("serverB:"));
        assertEquals(outcome.lockValue(), this.inspection.sync().get(this.key(player)));
    }

    @Test
    void handoffTimesOutWhileHolderKeepsSaving() throws Exception {
        UUID player = UUID.randomUUID();
        String heldByA = this.acquire(this.lockA, player);
        this.sessionsA.add(player);

        CompletableFuture<HandoffOutcome> handoff = this.serviceB.awaitHandoff(player, heldByA, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> handoff.get(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        this.sessionsA.remove(player);
        assertTrue(this.lockA.release(player, heldByA).get(5, TimeUnit.SECONDS));
    }

    private HandoffResponseMessage request(UUID player) throws Exception {
        return this.brokerB.broker().publishTwoWay(new HandoffRequestMessage(player), "serverA").get(5, TimeUnit.SECONDS);
    }

    private String acquire(SessionLock lock, UUID player) throws Exception {
        AcquireOutcome outcome = lock.tryAcquire(player).get(5, TimeUnit.SECONDS);
        return assertInstanceOf(AcquireOutcome.Acquired.class, outcome).value();
    }

    private String key(UUID player) {
        return "ss:" + CLUSTER + ":lock:" + player;
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
