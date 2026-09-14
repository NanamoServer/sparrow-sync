package net.momirealms.sparrow.sync.cluster;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import net.momirealms.sparrow.sync.cluster.RemoteSnapshotManager;
import net.momirealms.sparrow.sync.cluster.message.SnapshotCaptureRequestMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotCaptureResponseMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotRestoreRequestMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotRestoreResponseMessage;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.redis.MessageBrokerManager;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.momirealms.sparrow.sync.snapshot.operation.SnapshotCaptureResult;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotRestoreResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class RemoteSnapshotManagerTest {
    private SparrowSync plugin;
    private RemoteSnapshotManager remote;

    @BeforeAll
    static void initializeProxy() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @BeforeEach
    void setup() {
        this.plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        this.remote = new RemoteSnapshotManager(this.plugin);
    }

    @Test
    @SuppressWarnings("unchecked")
    void remoteOperationsWaitForAcknowledgementAndMissingHeartbeatFailsWithoutSending() throws Exception {
        UUID player = UUID.randomUUID();
        AtomicBoolean alive = new AtomicBoolean(true);
        RedisAsyncCommands<byte[], byte[]> commands = proxy(RedisAsyncCommands.class, (instance, method, args) -> {
            assertEquals("get", method.getName());
            AsyncCommand<byte[], byte[], byte[]> result = new AsyncCommand<>(new Command<>(CommandType.GET, null));
            result.complete(alive.get() ? new byte[]{1} : null);
            return result;
        });
        StatefulRedisConnection<byte[], byte[]> connection = proxy(StatefulRedisConnection.class, (instance, method, args) -> commands);
        RedisConnector connector = NmsPlayerFixture.allocate(RedisConnector.class);
        NmsPlayerFixture.set(RedisConnector.class, connector, "connection", connection);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "redisConnector", connector);
        AtomicReference<CompletableFuture<SnapshotCaptureResponseMessage>> response = new AtomicReference<>(new CompletableFuture<>());
        CompletableFuture<SnapshotRestoreResponseMessage> restored = new CompletableFuture<>();
        AtomicInteger sent = new AtomicInteger();
        MessageBroker<ByteBuf> broker = proxy(MessageBroker.class, (instance, method, args) -> {
            assertEquals("publishTwoWay", method.getName());
            assertEquals("remote", args[1]);
            sent.incrementAndGet();
            if (args[0] instanceof SnapshotRestoreRequestMessage) return restored;
            assertInstanceOf(SnapshotCaptureRequestMessage.class, args[0]);
            return response.get();
        });
        MessageBrokerManager manager = NmsPlayerFixture.allocate(MessageBrokerManager.class);
        NmsPlayerFixture.set(MessageBrokerManager.class, manager, "broker", broker);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "messageBrokerManager", manager);
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previous = serverField.get(null);
        Server server = proxy(Server.class, (instance, method, args) -> {
            assertEquals("getPlayer", method.getName());
            return null;
        });
        serverField.set(null, server);
        try {
            CompletableFuture<SnapshotCaptureResult> capture = this.remote.capture("remote", player, SaveCause.COMMAND);
            assertFalse(capture.isDone());
            UUID saved = UUID.randomUUID();
            response.get().complete(new SnapshotCaptureResponseMessage(new SnapshotCaptureResult.Captured(saved)));
            assertEquals(new SnapshotCaptureResult.Captured(saved), capture.join());
            response.set(new CompletableFuture<>());
            CompletableFuture<SnapshotRestoreResult> restore = this.remote.restore("remote", player, saved);
            assertFalse(restore.isDone());
            restored.complete(new SnapshotRestoreResponseMessage(SnapshotRestoreResult.OFFLINE));
            assertInstanceOf(SnapshotRestoreResult.Offline.class, restore.join());
            response.set(new CompletableFuture<>());
            CompletableFuture<SnapshotCaptureResult> lost = this.remote.capture("remote", player, SaveCause.COMMAND);
            response.get().completeExceptionally(new IllegalStateException("connection lost"));
            assertInstanceOf(SnapshotCaptureResult.Unavailable.class, lost.join());
            alive.set(false);
            assertInstanceOf(SnapshotCaptureResult.Offline.class, this.remote.capture("remote", player, SaveCause.COMMAND).join());
            assertEquals(3, sent.get());
        } finally {
            serverField.set(null, previous);
        }
    }

    @ParameterizedTest
    @MethodSource("captureResults")
    void captureResponsesRoundTripEveryOutcome(SnapshotCaptureResult result) {
        SnapshotCaptureResponseMessage response = new SnapshotCaptureResponseMessage(result);
        response.setMessageId(77);
        response.setSourceServer("remote");
        response.setTargetServer("origin");
        ByteBuf buffer = Unpooled.buffer();
        try {
            SnapshotCaptureResponseMessage.CODEC.encode(buffer, response);
            SnapshotCaptureResponseMessage decoded = SnapshotCaptureResponseMessage.CODEC.decode(buffer);
            assertEquals(77, decoded.messageId());
            assertEquals(result, decoded.result());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    static Stream<SnapshotCaptureResult> captureResults() {
        return Stream.of(new SnapshotCaptureResult.Captured(UUID.randomUUID()),
                new SnapshotCaptureResult.Offline(),
                new SnapshotCaptureResult.Cancelled(),
                new SnapshotCaptureResult.Failed(),
                new SnapshotCaptureResult.Unavailable());
    }

    @ParameterizedTest
    @MethodSource("restoreResults")
    void restoreResponsesRoundTripEveryOutcome(SnapshotRestoreResult result) {
        SnapshotRestoreResponseMessage response = new SnapshotRestoreResponseMessage(result);
        response.setMessageId(77);
        response.setSourceServer("remote");
        response.setTargetServer("origin");
        ByteBuf buffer = Unpooled.buffer();
        try {
            SnapshotRestoreResponseMessage.CODEC.encode(buffer, response);
            SnapshotRestoreResponseMessage decoded = SnapshotRestoreResponseMessage.CODEC.decode(buffer);
            assertEquals(77, decoded.messageId());
            assertEquals(result, decoded.result());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    static Stream<SnapshotRestoreResult> restoreResults() {
        return Stream.of(new SnapshotRestoreResult.Restored(UUID.randomUUID()),
                new SnapshotRestoreResult.RestoredOffline(UUID.randomUUID()),
                new SnapshotRestoreResult.NotFound(),
                new SnapshotRestoreResult.WrongPlayer(),
                new SnapshotRestoreResult.Offline(),
                new SnapshotRestoreResult.Restored(UUID.randomUUID(), List.of(DataKey.sparrow("inventory"))),
                new SnapshotRestoreResult.Cancelled(SnapshotRestoreResult.Stage.SAVE, List.of(DataKey.sparrow("health"))),
                new SnapshotRestoreResult.Failed(SnapshotRestoreResult.Stage.PREPARE, "读取失败", null, List.of()),
                new SnapshotRestoreResult.Failed(SnapshotRestoreResult.Stage.APPLY, "apply failed", null, List.of(DataKey.sparrow("health"))),
                new SnapshotRestoreResult.Failed(SnapshotRestoreResult.Stage.SAVE, "save failed", null, List.of(DataKey.sparrow("health"))),
                new SnapshotRestoreResult.Cancelled(),
                new SnapshotRestoreResult.Failed(),
                new SnapshotRestoreResult.Unavailable());
    }

    @Test
    void requestPayloadsPreserveCauseAndMissingReceiverAnswersOffline() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        ByteBuf captureBuffer = Unpooled.buffer();
        ByteBuf restoreBuffer = Unpooled.buffer();
        try {
            SnapshotCaptureRequestMessage capture = new SnapshotCaptureRequestMessage(playerId, SaveCause.API);
            capture.setMessageId(78);
            capture.setSourceServer("a");
            capture.setTargetServer("b");
            SnapshotCaptureRequestMessage.CODEC.encode(captureBuffer, capture);
            SnapshotCaptureRequestMessage decodedCapture = SnapshotCaptureRequestMessage.CODEC.decode(captureBuffer);
            SnapshotCaptureRequestMessage.receiver(null);
            assertEquals(playerId, field(decodedCapture, "playerId"));
            assertEquals(SaveCause.API, field(decodedCapture, "cause"));
            assertFalse(captureBuffer.isReadable());
            assertEquals("b", decodedCapture.targetServer());
            assertInstanceOf(SnapshotCaptureResult.Offline.class, decodedCapture.handleRequest().join().result());
            SnapshotRestoreRequestMessage restore = new SnapshotRestoreRequestMessage(playerId, snapshotId);
            restore.setMessageId(78);
            restore.setSourceServer("a");
            restore.setTargetServer("b");
            SnapshotRestoreRequestMessage.CODEC.encode(restoreBuffer, restore);
            SnapshotRestoreRequestMessage decodedRestore = SnapshotRestoreRequestMessage.CODEC.decode(restoreBuffer);
            SnapshotRestoreRequestMessage.receiver(null);
            assertEquals(playerId, field(decodedRestore, "playerId"));
            assertEquals(snapshotId, field(decodedRestore, "snapshotId"));
            assertFalse(restoreBuffer.isReadable());
            assertInstanceOf(SnapshotRestoreResult.Offline.class, decodedRestore.handleRequest().join().result());
            assertNotEquals(capture.identifier(), restore.identifier());
        } finally {
            captureBuffer.release();
            restoreBuffer.release();
        }
    }

    @Test
    void restoreResponsePreservesFailureContextAndOmitsLocalThrowable() {
        SnapshotRestoreResult.Failed failure = new SnapshotRestoreResult.Failed(SnapshotRestoreResult.Stage.SAVE,
                "database unavailable", new IllegalStateException("database unavailable"), List.of(DataKey.sparrow("health")));
        SnapshotRestoreResponseMessage response = new SnapshotRestoreResponseMessage(failure);
        response.setMessageId(79);
        response.setSourceServer("remote");
        response.setTargetServer("origin");
        ByteBuf buffer = Unpooled.buffer();
        try {
            SnapshotRestoreResponseMessage.CODEC.encode(buffer, response);
            SnapshotRestoreResult.Failed decoded = assertInstanceOf(SnapshotRestoreResult.Failed.class, SnapshotRestoreResponseMessage.CODEC.decode(buffer).result());
            assertEquals(failure.stage(), decoded.stage());
            assertEquals(failure.detail(), decoded.detail());
            assertEquals(failure.skipped(), decoded.skipped());
            assertNull(decoded.cause());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
