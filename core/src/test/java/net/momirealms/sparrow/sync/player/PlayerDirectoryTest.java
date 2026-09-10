package net.momirealms.sparrow.sync.player;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import net.momirealms.sparrow.sync.redis.MessageBrokerManager;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.plugin.scheduler.executor.RegionExecutor;
import net.momirealms.sparrow.sync.plugin.command.parser.NetworkPlayerParser;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionListener;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SessionState;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.suggestion.Suggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDirectoryTest {
    @Test
    void mergesTwoServersAndCompletesNamesWithoutRedisIo() {
        FakeRedis redis = new FakeRedis();
        Fixture first = fixture(redis, name -> { throw new AssertionError("database should not be read"); });
        Fixture second = fixture(redis, name -> { throw new AssertionError("database should not be read"); });
        UUID alex = UUID.randomUUID();
        UUID steve = UUID.randomUUID();
        first.join(alex, "Alex");
        second.join(steve, "Steve");
        first.directory.refresh();
        second.directory.refresh();
        first.directory.refresh();
        int commands = redis.calls;
        assertEquals(List.of(Suggestion.suggestion("Alex"), Suggestion.suggestion("Steve")), first.directory.suggestions(""));
        assertEquals(List.of(Suggestion.suggestion("Steve")), first.directory.suggestions("sT"));
        assertTrue(first.directory.suggestions(steve.toString().substring(0, 8)).isEmpty());
        assertEquals(steve, first.directory.resolve("sTEVE").join().orElseThrow().uuid());
        assertTrue(first.directory.cached(steve.toString()).isEmpty());
        assertEquals(commands, redis.calls);
        assertEquals(2, first.directory.onlinePlayers().size());
    }

    @Test
    void reusesSuggestionsUntilPresenceChangesAndDropsRenamedOrOfflineCandidates() {
        Fixture server = fixture(new FakeRedis(), name -> CompletableFuture.completedFuture(Optional.empty()));
        UUID uuid = UUID.randomUUID();
        server.directory.presence(uuid, "Steve", true);
        List<Suggestion> all = server.directory.suggestions("");
        assertSame(all, server.directory.suggestions(""));
        assertSame(all.getFirst(), server.directory.suggestions("sT").getFirst());
        assertTrue(server.directory.suggestions(uuid.toString()).isEmpty());
        NetworkPlayerParser<Object> parser = new NetworkPlayerParser<>(server.directory);
        assertSame(all, parser.suggestions(new CommandContext<>(true, new Object(), new TestCloud()), CommandInput.of("")));
        server.directory.presence(uuid, "Steve", false);
        server.directory.presence(uuid, "Alex", true);
        assertEquals(List.of(Suggestion.suggestion("Alex")), server.directory.suggestions(""));
        assertTrue(server.directory.suggestions("Steve").isEmpty());
        server.directory.presence(uuid, "Alex", false);
        assertTrue(server.directory.suggestions("").isEmpty());
        assertTrue(server.directory.suggestions(uuid.toString()).isEmpty());
    }

    @Test
    void onlyPublishesActiveSessionsAndClearsAllIndexesWhenEmpty() {
        FakeRedis redis = new FakeRedis();
        Fixture server = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        PlayerSession session = server.join(UUID.randomUUID(), "Steve");
        NmsPlayerFixture.set(PlayerSession.class, session, "state", SessionState.PREPARING);
        server.directory.refresh();
        assertTrue(server.directory.suggestions("").isEmpty());
        NmsPlayerFixture.set(PlayerSession.class, session, "state", SessionState.ACTIVE);
        server.directory.refresh();
        assertEquals(List.of(Suggestion.suggestion("Steve")), server.directory.suggestions(""));
        NmsPlayerFixture.set(PlayerSession.class, session, "state", SessionState.SAVING);
        server.directory.refresh();
        assertTrue(server.directory.onlinePlayers().isEmpty());
        assertTrue(server.directory.suggestions("S").isEmpty());
        assertTrue(server.directory.cached("Steve").isEmpty());
    }

    @Test
    void missingHeartbeatClearsServerRosterAndShutdownOnlyRemovesItsOwnKey() {
        FakeRedis redis = new FakeRedis();
        Fixture first = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        Fixture second = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        first.join(UUID.randomUUID(), "Alex");
        second.join(UUID.randomUUID(), "Steve");
        first.directory.refresh();
        second.directory.refresh();
        redis.data.remove("ss:server:" + first.serverId);
        second.directory.refresh();
        assertEquals(List.of(Suggestion.suggestion("Steve")), second.directory.suggestions(""));
        first.directory.refresh();
        first.directory.shutdown();
        second.directory.refresh();
        assertEquals(List.of(Suggestion.suggestion("Steve")), second.directory.suggestions(""));
        assertEquals(1, redis.hashes.size());
    }

    @Test
    void retainsLastGoodListOnFailureAndReturnsItDirectly() {
        FakeRedis redis = new FakeRedis();
        Fixture server = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        server.join(UUID.randomUUID(), "Steve");
        server.directory.refresh();
        List<PlayerIdentity> players = server.directory.onlinePlayers();
        redis.failed = true;
        server.directory.refresh();
        assertSame(players, server.directory.onlinePlayers());
        assertEquals(List.of(Suggestion.suggestion("Steve")), server.directory.suggestions(""));
    }

    @Test
    void presenceUpdatesBothServersImmediatelyAndLateQuitKeepsDestinationOnline() {
        FakeRedis redis = new FakeRedis();
        Fixture first = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        Fixture second = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        UUID uuid = UUID.randomUUID();
        first.directory.presence(uuid, "Steve", true);
        assertEquals(List.of(Suggestion.suggestion("Steve")), second.directory.suggestions(""));
        assertEquals(1, redis.hashes.get("ss:online-players:" + first.serverId).size());
        second.directory.presence(uuid, "Steve", true);
        first.directory.presence(uuid, "Steve", false);
        assertEquals(List.of(Suggestion.suggestion("Steve")), first.directory.suggestions(""));
        assertEquals(List.of(Suggestion.suggestion("Steve")), second.directory.suggestions(""));
        assertFalse(redis.hashes.containsKey("ss:online-players:" + first.serverId));
        second.directory.presence(uuid, "Steve", false);
        assertTrue(first.directory.onlinePlayers().isEmpty());
        assertTrue(second.directory.suggestions("S").isEmpty());
    }

    @Test
    void refreshKeepsLoginHandledWhileItsRosterSnapshotWasBeingRead() {
        FakeRedis redis = new FakeRedis();
        Fixture first = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        Fixture second = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        first.join(UUID.randomUUID(), "Alex");
        UUID steve = UUID.randomUUID();
        // 校准读完远端名单、尚未替换本地视图时, Steve 登录第二台服务器的广播到达.
        redis.afterRosterRead = () -> first.directory.acceptPresence(new PlayerPresenceMessage(second.serverId, steve, "Steve", true));
        first.directory.refresh();
        assertEquals(List.of(Suggestion.suggestion("Alex"), Suggestion.suggestion("Steve")), first.directory.suggestions(""));
    }

    @Test
    void refreshKeepsQuitHandledWhileItsRosterSnapshotWasBeingRead() {
        FakeRedis redis = new FakeRedis();
        Fixture first = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        Fixture second = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        UUID steve = UUID.randomUUID();
        second.directory.presence(steve, "Steve", true);
        // 校准读到第二台服务器仍有 Steve 的旧名单, 但退出广播在替换本地视图前已经到达.
        redis.afterRosterRead = () -> first.directory.acceptPresence(new PlayerPresenceMessage(second.serverId, steve, "Steve", false));
        first.directory.refresh();
        assertTrue(first.directory.suggestions("").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void localPresenceDuringFullRewriteRemainsInRedisAndOtherServers(boolean joined) {
        FakeRedis redis = new FakeRedis();
        Fixture first = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        Fixture second = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        first.join(UUID.randomUUID(), "Alex");
        UUID steve = UUID.randomUUID();
        PlayerSession oldSession = joined ? null : first.join(steve, "Steve");
        first.directory.refresh();
        // 全量任务已采样并执行 DEL, 此时的进退服写入必须排在旧名单 HSET 之后.
        redis.afterRosterDelete = () -> {
            assertFalse(Thread.holdsLock(first.directory));
            if (joined) {
                first.join(steve, "Steve");
            } else {
                NmsPlayerFixture.set(PlayerSession.class, oldSession, "state", SessionState.SAVING);
            }
            int calls = redis.calls;
            first.directory.presence(steve, "Steve", joined);
            assertEquals(calls, redis.calls);
            assertEquals(joined, first.directory.cached("Steve").isPresent());
        };
        first.directory.refresh();
        String field = HexFormat.of().formatHex("Steve".getBytes(StandardCharsets.UTF_8));
        assertEquals(joined, redis.hashes.get("ss:online-players:" + first.serverId).containsKey(field));
        second.directory.refresh();
        assertEquals(joined, second.directory.cached("Steve").isPresent());
    }

    @Test
    void loginAfterReadingAnExistingRemoteRosterKeepsBothPlayers() {
        FakeRedis redis = new FakeRedis();
        Fixture first = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        Fixture second = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        second.directory.presence(UUID.randomUUID(), "Alex", true);
        redis.rosterReadKey = "ss:online-players:" + second.serverId;
        redis.afterRosterRead = () -> second.directory.presence(UUID.randomUUID(), "Steve", true);
        first.directory.refresh();
        assertEquals(List.of(Suggestion.suggestion("Alex"), Suggestion.suggestion("Steve")), first.directory.suggestions(""));
    }

    @Test
    void repeatedOnlineIdentityStillInvalidatesTheOldRosterAfterALostQuit() {
        FakeRedis redis = new FakeRedis();
        Fixture first = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        Fixture second = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        UUID steve = UUID.randomUUID();
        second.directory.presence(UUID.randomUUID(), "Alex", true);
        second.directory.presence(steve, "Steve", true);
        redis.listeners.clear();
        second.directory.presence(steve, "Steve", false);
        redis.listeners.add(first.directory::acceptPresence);
        assertTrue(first.directory.cached("Steve").isPresent());
        redis.rosterReadKey = "ss:online-players:" + second.serverId;
        redis.afterRosterRead = () -> second.directory.presence(steve, "Steve", true);
        first.directory.refresh();
        assertEquals(second.serverId, first.directory.server("Steve").orElseThrow());
    }

    @Test
    void quitRemovesActiveSessionBeforeSubmittingOfflinePresence() throws Exception {
        FakeRedis redis = new FakeRedis();
        Fixture server = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        UUID steve = UUID.randomUUID();
        PlayerSession session = server.join(steve, "Steve");
        server.directory.presence(steve, "Steve", true);
        SparrowSync plugin = (SparrowSync) field(server.directory, "plugin");
        NmsPlayerFixture.set(SparrowSync.class, plugin, "playerDirectory", server.directory);
        RegionExecutor<?> region = proxy(RegionExecutor.class, (instance, method, args) -> {
            assertEquals("runLater", method.getName());
            return null;
        });
        SchedulerAdapter<?> scheduler = proxy(SchedulerAdapter.class, (instance, method, args) -> switch (method.getName()) {
            case "async" -> (Executor) Runnable::run;
            case "sync" -> region;
            default -> throw new AssertionError(method.getName());
        });
        NmsPlayerFixture.set(SparrowSync.class, plugin, "scheduler", scheduler);
        Player player = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> steve;
            case "getName" -> "Steve";
            case "getLocation" -> new Location(null, 0, 64, 0);
            case "getWorld" -> null;
            default -> throw new AssertionError(method.getName());
        });
        List<SessionState> statesAtRemoval = new ArrayList<>();
        redis.afterRosterRemoval = () -> statesAtRemoval.add(session.state());
        new SessionListener(plugin, server.sessions).onQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(List.of(SessionState.SAVING), statesAtRemoval);
        server.directory.refresh();
        assertFalse(redis.hashes.containsKey("ss:online-players:" + server.serverId));
        assertTrue(server.directory.onlinePlayers().isEmpty());
    }

    @Test
    void shutdownDuringFullRewriteDeletesTheRosterAfterTheOldWrite() {
        FakeRedis redis = new FakeRedis();
        Fixture server = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        server.join(UUID.randomUUID(), "Steve");
        redis.afterRosterDelete = server.directory::shutdown;
        server.directory.refresh();
        assertFalse(redis.hashes.containsKey("ss:online-players:" + server.serverId));
        assertTrue(server.directory.onlinePlayers().isEmpty());
    }

    @Test
    void fallbackRepairsDroppedMessagesAndFailedWrites() {
        FakeRedis redis = new FakeRedis();
        Fixture first = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        Fixture second = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        UUID uuid = UUID.randomUUID();
        first.join(uuid, "Steve");
        redis.failed = true;
        first.directory.presence(uuid, "Steve", true);
        assertEquals(List.of(Suggestion.suggestion("Steve")), first.directory.suggestions(""));
        assertTrue(second.directory.onlinePlayers().isEmpty());
        redis.failed = false;
        first.directory.refresh();
        second.directory.refresh();
        assertEquals(List.of(Suggestion.suggestion("Steve")), second.directory.suggestions(""));
        redis.listeners.clear();
        first.directory.presence(uuid, "Steve", false);
        assertEquals(List.of(Suggestion.suggestion("Steve")), second.directory.suggestions(""));
        second.directory.refresh();
        assertTrue(second.directory.onlinePlayers().isEmpty());
    }

    @Test
    void uuidInputDoesNotResolveAnOnlineIdentity() {
        FakeRedis redis = new FakeRedis();
        UUID uuid = UUID.randomUUID();
        Fixture server = fixture(redis, name -> {
            assertEquals(uuid.toString(), name);
            return CompletableFuture.completedFuture(Optional.empty());
        });
        server.directory.presence(uuid, "Steve", true);
        assertEquals(uuid, server.directory.resolve("Steve").join().orElseThrow().uuid());
        assertTrue(server.directory.resolve(uuid.toString()).join().isEmpty());
    }

    @Test
    void fillsRedisFromStorageAndAnotherServerUsesItWithoutDatabaseRead() {
        FakeRedis redis = new FakeRedis();
        UUID uuid = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        Fixture first = fixture(redis, name -> {
            reads.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(uuid));
        });
        assertEquals(uuid, first.directory.resolve("Offline").join().orElseThrow().uuid());
        int calls = redis.calls;
        assertEquals(uuid, first.directory.resolve("Offline").join().orElseThrow().uuid());
        assertEquals(calls + 1, redis.calls);
        assertTrue(first.directory.cached("Offline").isEmpty());
        Fixture second = fixture(redis, name -> { throw new AssertionError("Redis cache should be used"); });
        assertEquals(uuid, second.directory.resolve("Offline").join().orElseThrow().uuid());
        assertEquals(1, reads.get());
        assertTrue(second.directory.suggestions("").isEmpty());
        assertEquals(300_000, redis.data.values().stream().filter(value -> value.bytes.length == 16).findFirst().orElseThrow().expires);
    }

    @Test
    void subsequentLookupSeesAnotherServersRedisUpdate() {
        FakeRedis redis = new FakeRedis();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        Fixture first = fixture(redis, name -> {
            reads.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(older));
        });
        assertEquals(older, first.directory.resolve("Steve").join().orElseThrow().uuid());
        Fixture second = fixture(redis, name -> { throw new AssertionError("login update must not query storage"); });
        second.directory.remember(newer, "Steve");
        assertEquals(newer, first.directory.resolve("Steve").join().orElseThrow().uuid());
        assertEquals(1, reads.get());
        assertTrue(first.directory.onlinePlayers().isEmpty());
    }

    @Test
    void coalescesConcurrentLookupsAndOneCallerCannotCancelTheOthers() {
        FakeRedis redis = new FakeRedis();
        AtomicInteger reads = new AtomicInteger();
        CompletableFuture<Optional<UUID>> stored = new CompletableFuture<>();
        Fixture server = fixture(redis, name -> {
            reads.incrementAndGet();
            return stored;
        });
        CompletableFuture<Optional<PlayerIdentity>> first = server.directory.resolve("Steve");
        CompletableFuture<Optional<PlayerIdentity>> second = server.directory.resolve("Steve");
        first.cancel(true);
        UUID uuid = UUID.randomUUID();
        stored.complete(Optional.of(uuid));
        assertEquals(uuid, second.join().orElseThrow().uuid());
        assertEquals(1, reads.get());
    }

    @Test
    void staleStorageReadDoesNotOverwriteANewerRedisMapping() {
        FakeRedis redis = new FakeRedis();
        CompletableFuture<Optional<UUID>> stored = new CompletableFuture<>();
        Fixture server = fixture(redis, name -> stored);
        CompletableFuture<Optional<PlayerIdentity>> pending = server.directory.resolve("Steve");
        UUID newer = UUID.randomUUID();
        server.directory.remember(newer, "Steve");
        UUID older = UUID.randomUUID();
        stored.complete(Optional.of(older));
        assertEquals(older, pending.join().orElseThrow().uuid());
        assertEquals(newer, server.directory.resolve("Steve").join().orElseThrow().uuid());
        Fixture other = fixture(redis, name -> { throw new AssertionError("cache should contain the newer mapping"); });
        assertEquals(newer, other.directory.resolve("Steve").join().orElseThrow().uuid());
    }

    @Test
    void redisFailureFallsBackToStorageWhileStorageFailureIsNotAnUnknownPlayer() {
        FakeRedis redis = new FakeRedis();
        redis.failed = true;
        UUID uuid = UUID.randomUUID();
        Fixture success = fixture(redis, name -> CompletableFuture.completedFuture(Optional.of(uuid)));
        assertEquals(uuid, success.directory.resolve("Steve").join().orElseThrow().uuid());
        Fixture missing = fixture(redis, name -> CompletableFuture.completedFuture(Optional.empty()));
        assertTrue(missing.directory.resolve("Missing").join().isEmpty());
        Fixture failed = fixture(redis, name -> CompletableFuture.failedFuture(new IllegalStateException("database offline")));
        assertThrows(CompletionException.class, () -> failed.directory.resolve("Failure").join());
    }

    @Test
    void redisExpiryAllowsTheStoredNameMappingToBeReadAgain() {
        FakeRedis redis = new FakeRedis();
        UUID old = UUID.randomUUID();
        Fixture first = fixture(redis, name -> CompletableFuture.completedFuture(Optional.of(old)));
        first.directory.resolve("Steve").join();
        redis.now += 300_001;
        UUID newer = UUID.randomUUID();
        Fixture second = fixture(redis, name -> CompletableFuture.completedFuture(Optional.of(newer)));
        assertEquals(newer, second.directory.resolve("Steve").join().orElseThrow().uuid());
    }

    @Test
    void historicalNameCachePreservesTheStorageCaseComparison() {
        FakeRedis redis = new FakeRedis();
        UUID upper = UUID.randomUUID();
        UUID lower = UUID.randomUUID();
        Fixture first = fixture(redis, name -> CompletableFuture.completedFuture(Optional.of(name.equals("Steve") ? upper : lower)));
        first.directory.resolve("Steve").join();
        first.directory.resolve("steve").join();
        Fixture second = fixture(redis, name -> { throw new AssertionError("Both names should be cached in Redis"); });
        assertEquals(upper, second.directory.resolve("Steve").join().orElseThrow().uuid());
        assertEquals(lower, second.directory.resolve("steve").join().orElseThrow().uuid());
    }

    @Test
    void parserAcceptsTextAndUsesLocalSuggestionsWithoutIo() {
        FakeRedis redis = new FakeRedis();
        Fixture server = fixture(redis, name -> { throw new AssertionError("completion must not read storage"); });
        server.join(UUID.randomUUID(), "Steve");
        server.directory.refresh();
        NetworkPlayerParser<Object> parser = new NetworkPlayerParser<>(server.directory);
        TestCloud cloud = new TestCloud();
        CommandContext<Object> suggestions = new CommandContext<>(true, new Object(), cloud);
        int calls = redis.calls;
        assertTrue(parser.suggestionProvider().suggestionsFuture(suggestions, CommandInput.of("sT")).join().iterator().hasNext());
        assertEquals("Unknown", parser.parse(suggestions, CommandInput.of("Unknown")).parsedValue().orElseThrow());
        assertEquals("stEVE", parser.parse(suggestions, CommandInput.of("stEVE")).parsedValue().orElseThrow());
        CommandContext<Object> execution = new CommandContext<>(new Object(), cloud);
        assertEquals("Offline", parser.parse(execution, CommandInput.of("Offline")).parsedValue().orElseThrow());
        assertEquals(calls, redis.calls);
    }

    @Test
    void completionFailureProducesNoSuggestions() {
        Fixture server = fixture(new FakeRedis(), name -> { throw new AssertionError("completion must not read storage"); });
        NmsPlayerFixture.set(PlayerDirectory.class, server.directory, "online", null);
        NetworkPlayerParser<Object> parser = new NetworkPlayerParser<>(server.directory);
        assertFalse(parser.suggestions(new CommandContext<>(true, new Object(), new TestCloud()), CommandInput.of("Steve")).iterator().hasNext());
    }

    private static Fixture fixture(FakeRedis redis, Function<String, CompletableFuture<Optional<UUID>>> lookup) {
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        RedisConnector connector = NmsPlayerFixture.allocate(RedisConnector.class);
        NmsPlayerFixture.set(RedisConnector.class, connector, "connection", redis.connection());
        NmsPlayerFixture.set(SparrowSync.class, plugin, "redisConnector", connector);
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> {
            assertEquals("lookupUser", method.getName());
            return lookup.apply((String) args[0]);
        });
        NmsPlayerFixture.set(SparrowSync.class, plugin, "storageProvider", storage);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", new SyncLogger(proxy(PluginLogger.class, (instance, method, args) -> null)));
        SchedulerAdapter<?> scheduler = proxy(SchedulerAdapter.class, (instance, method, args) -> {
            assertEquals("async", method.getName());
            return (Executor) Runnable::run;
        });
        NmsPlayerFixture.set(SparrowSync.class, plugin, "scheduler", scheduler);
        SessionManager sessions = new SessionManager(plugin);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "sessionManager", sessions);
        String serverId = UUID.randomUUID().toString();
        PlayerDirectory directory = new PlayerDirectory(plugin);
        NmsPlayerFixture.set(PlayerDirectory.class, directory, "serverId", serverId);
        NmsPlayerFixture.set(PlayerDirectory.class, directory, "rosterKey", ("ss:online-players:" + serverId).getBytes(StandardCharsets.UTF_8));
        redis.data.put("ss:server:" + serverId, new Value(new byte[]{1}, Long.MAX_VALUE));
        redis.listeners.add(directory::acceptPresence);
        MessageBrokerManager manager = NmsPlayerFixture.allocate(MessageBrokerManager.class);
        MessageBroker<?> broker = proxy(MessageBroker.class, (instance, method, args) -> switch (method.getName()) {
            case "channel" -> new byte[]{1};
            case "encode" -> {
                ByteBuf buffer = Unpooled.buffer();
                try {
                    PlayerPresenceMessage.CODEC.encode(buffer, (PlayerPresenceMessage) args[0]);
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    yield bytes;
                } finally {
                    buffer.release();
                }
            }
            default -> throw new AssertionError(method);
        });
        NmsPlayerFixture.set(MessageBrokerManager.class, manager, "broker", broker);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "messageBrokerManager", manager);
        return new Fixture(directory, sessions, serverId);
    }

    private record Fixture(PlayerDirectory directory, SessionManager sessions, String serverId) {
        private PlayerSession join(UUID uuid, String name) {
            PlayerSession session = this.sessions.tryOpen(uuid, name, ConnectionFixture.create());
            NmsPlayerFixture.set(PlayerSession.class, session, "state", SessionState.ACTIVE);
            return session;
        }
    }

    private record Value(byte[] bytes, long expires) {
    }

    private static final class FakeRedis {
        private final Map<String, Value> data = new HashMap<>();
        private final Map<String, Map<String, byte[]>> hashes = new HashMap<>();
        private final List<Consumer<PlayerPresenceMessage>> listeners = new ArrayList<>();
        private long now;
        private int calls;
        private boolean failed;
        private Runnable afterRosterRemoval;
        private Runnable afterRosterDelete;
        private String rosterReadKey;
        private Runnable afterRosterRead; // 校准读完一批远端名单后触发一次, 供测试注入并发通知

        @SuppressWarnings("unchecked")
        private StatefulRedisConnection<byte[], byte[]> connection() {
            RedisCommands<byte[], byte[]> sync = proxy(RedisCommands.class, (instance, method, args) -> this.command(method.getName(), args));
            RedisAsyncCommands<byte[], byte[]> async = proxy(RedisAsyncCommands.class, (instance, method, args) -> {
                AsyncCommand<byte[], byte[], Object> result = new AsyncCommand<>(new Command<>(CommandType.GET, null));
                try {
                    result.complete(this.command(method.getName(), args));
                } catch (RedisException exception) {
                    result.completeExceptionally(exception);
                }
                return result;
            });
            return proxy(StatefulRedisConnection.class, (instance, method, args) -> switch (method.getName()) {
                case "sync" -> sync;
                case "async" -> async;
                case "isOpen" -> true;
                default -> throw new AssertionError(method);
            });
        }

        private Object command(String command, Object[] args) throws Exception {
            this.calls++;
            if (this.failed) throw new RedisException("offline");
            this.data.entrySet().removeIf(entry -> entry.getValue().expires <= this.now);
            Object outcome = switch (command) {
                case "hset" -> {
                    Map<String, byte[]> hash = this.hashes.computeIfAbsent(new String((byte[]) args[0], StandardCharsets.UTF_8), ignored -> new HashMap<>());
                    if (args.length == 2) {
                        @SuppressWarnings("unchecked") Map<byte[], byte[]> entries = (Map<byte[], byte[]>) args[1];
                        entries.forEach((key, value) -> hash.put(HexFormat.of().formatHex(key), value));
                        yield (long) entries.size();
                    }
                    yield hash.put(HexFormat.of().formatHex((byte[]) args[1]), (byte[]) args[2]) == null;
                }
                case "hdel" -> {
                    String name = new String((byte[]) args[0], StandardCharsets.UTF_8);
                    Map<String, byte[]> hash = this.hashes.get(name);
                    long removed = 0;
                    if (hash != null) {
                        for (byte[] key : (byte[][]) args[1]) {
                            if (hash.remove(HexFormat.of().formatHex(key)) != null) removed++;
                        }
                        if (hash.isEmpty()) this.hashes.remove(name);
                    }
                    yield removed;
                }
                case "hgetall" -> {
                    Map<byte[], byte[]> result = new HashMap<>();
                    this.hashes.getOrDefault(new String((byte[]) args[0], StandardCharsets.UTF_8), Map.of())
                            .forEach((key, value) -> result.put(HexFormat.of().parseHex(key), value));
                    yield result;
                }
                case "publish" -> {
                    ByteBuf buffer = Unpooled.wrappedBuffer((byte[]) args[1]);
                    try {
                        PlayerPresenceMessage message = PlayerPresenceMessage.CODEC.decode(buffer);
                        this.listeners.forEach(listener -> listener.accept(message));
                    } finally {
                        buffer.release();
                    }
                    yield (long) this.listeners.size();
                }
                case "set" -> {
                    String key = new String((byte[]) args[0], StandardCharsets.UTF_8);
                    SetArgs options = (SetArgs) args[2];
                    if ((boolean) field(options, "nx") && this.data.containsKey(key)) yield null;
                    Long seconds = (Long) field(options, "ex");
                    Long millis = (Long) field(options, "px");
                    this.data.put(key, new Value((byte[]) args[1], this.now + (millis == null ? seconds * 1000 : millis)));
                    yield "OK";
                }
                case "get" -> {
                    Value value = this.data.get(new String((byte[]) args[0], StandardCharsets.UTF_8));
                    yield value == null ? null : value.bytes;
                }
                case "scan" -> {
                    KeyScanCursor<byte[]> cursor = new KeyScanCursor<>();
                    cursor.setCursor("0");
                    cursor.setFinished(true);
                    this.hashes.keySet().stream().filter(key -> key.startsWith("ss:online-players:"))
                            .map(key -> key.getBytes(StandardCharsets.UTF_8)).forEach(cursor.getKeys()::add);
                    yield cursor;
                }
                case "mget" -> {
                    List<KeyValue<byte[], byte[]>> values = new ArrayList<>();
                    for (byte[] key : (byte[][]) args[0]) {
                        Value value = this.data.get(new String(key, StandardCharsets.UTF_8));
                        values.add(value == null ? KeyValue.empty(key) : KeyValue.just(key, value.bytes));
                    }
                    yield values;
                }
                case "del" -> {
                    long removed = 0;
                    for (byte[] key : (byte[][]) args[0]) {
                        String name = new String(key, StandardCharsets.UTF_8);
                        if (this.data.remove(name) != null || this.hashes.remove(name) != null) removed++;
                    }
                    yield removed;
                }
                default -> throw new AssertionError(command);
            };
            // 名单读取完成后触发一次, 供测试在远端名单已读出后注入通知.
            if ("hgetall".equals(command) && this.afterRosterRead != null && (this.rosterReadKey == null || this.rosterReadKey.equals(new String((byte[]) args[0], StandardCharsets.UTF_8)))) {
                Runnable hook = this.afterRosterRead;
                this.afterRosterRead = null;
                hook.run();
            }
            if ("hdel".equals(command) && this.afterRosterRemoval != null) {
                Runnable hook = this.afterRosterRemoval;
                this.afterRosterRemoval = null;
                hook.run();
            }
            if ("del".equals(command) && this.afterRosterDelete != null) {
                Runnable hook = this.afterRosterDelete;
                this.afterRosterDelete = null;
                hook.run();
            }
            return outcome;
        }
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class TestCloud extends org.incendo.cloud.CommandManager<Object> {
        private TestCloud() {
            super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(Object sender, String permission) {
            return true;
        }
    }
}
