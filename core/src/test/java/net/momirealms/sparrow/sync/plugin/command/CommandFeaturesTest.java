package net.momirealms.sparrow.sync.plugin.command;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ByteArrayOutput;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.Command;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.util.Index;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.player.PlayerDirectory;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.locale.tag.IndexedArgumentTag;
import net.momirealms.sparrow.sync.plugin.PaperJavaPlugin;
import net.momirealms.sparrow.sync.plugin.SparrowSync;

import net.momirealms.sparrow.sync.plugin.command.feature.StatusCommand;
import net.momirealms.sparrow.sync.plugin.configuration.CommandsConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ConfigurationManager;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.route.Route;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CommandFeaturesTest {
    private SparrowSync plugin;
    private TestManager manager;
    private final List<Component> messages = new ArrayList<>();
    private Object previousConfig;
    private Object previousServer;

    @BeforeEach
    void setUp() throws Exception {
        this.previousConfig = value(PluginConfig.class, "config");
        this.previousServer = value(ServerConfig.class, "config");
        NmsPlayerFixture.set(PluginConfig.class, null, "config", new PluginConfig.ConfigDefinition());
        NmsPlayerFixture.set(ServerConfig.class, null, "config", new ServerConfig.ConfigDefinition());
        this.plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "playerDirectory", new PlayerDirectory(this.plugin));
        PaperJavaPlugin javaPlugin = NmsPlayerFixture.allocate(PaperJavaPlugin.class);
        PluginDescriptionFile description = new PluginDescriptionFile(new StringReader("""
                name: SparrowSync
                version: test
                main: test.Main
                authors: [TestAuthor]
                website: https://example.com/docs
                """));
        NmsPlayerFixture.set(JavaPlugin.class, javaPlugin, "description", description);
        NmsPlayerFixture.set(JavaPlugin.class, javaPlugin, "pluginMeta", description);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "javaPlugin", javaPlugin);
        SparrowYaml yaml = SparrowYaml.builder().build();
        YamlDocument en = yaml.load(new String(CommandFeaturesTest.class.getResourceAsStream("/translations/en.yml").readAllBytes(), StandardCharsets.UTF_8));
        YamlDocument zh = yaml.load(new String(CommandFeaturesTest.class.getResourceAsStream("/translations/zh_cn.yml").readAllBytes(), StandardCharsets.UTF_8));
        TranslationManager translations = proxy(TranslationManager.class, (instance, method, args) -> {
            if (method.getName().equals("render")) {
                TranslatableComponent component = (TranslatableComponent) args[0];
                YamlDocument source = args.length > 1 && Locale.SIMPLIFIED_CHINESE.equals(args[1]) ? zh : en;
                String format = source.getString(Route.from(component.key()));
                return MiniMessage.miniMessage().deserialize(format == null ? component.key() : format, new IndexedArgumentTag(component.arguments()));
            }
            return null;
        });
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "translationManager", translations);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "sessionManager", new SessionManager(null));
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "logger", new SyncLogger(proxy(PluginLogger.class, (instance, method, args) -> null)));
        this.manager = new TestManager(this.plugin);
        this.manager.setFeedbackConsumer((sender, key, message) -> this.messages.add(message));
    }

    @AfterEach
    void restoreConfig() {
        NmsPlayerFixture.set(PluginConfig.class, null, "config", this.previousConfig);
        NmsPlayerFixture.set(ServerConfig.class, null, "config", this.previousServer);
    }

    @Test
    void rendersNestedTranslationsWithoutMutatingSharedBuilders() {
        this.manager.locale = Locale.SIMPLIFIED_CHINESE;
        this.manager.handleCommandFeedback(sender(Set.of()), MessageConstants.COMMAND_STATUS_PLAYER,
                MessageConstants.COMMAND_PINNED.build().clickEvent(ClickEvent.runCommand("/sparrow-sync snapshot view test")), Component.empty(), Component.empty(), Component.empty(), Component.empty());
        assertTrue(this.text().contains("已固定"));
        assertTrue(this.messages.stream().anyMatch(message -> hasClick(message, "/sparrow-sync snapshot view test")));
        assertTrue(MessageConstants.COMMAND_STATUS_PLAYER.build().arguments().isEmpty());
    }

    @Test
    void defaultFeaturesOnlyRegisterTheCanonicalRoot() throws Exception {
        CommandsConfig.ConfigDefinition configs = new CommandsConfig.ConfigDefinition();
        AtomicInteger calls = new AtomicInteger();
        for (String feature : List.of("status", "reload", "test", "debug_save_binary", "debug_save_json", "debug_apply")) {
            CommandConfig config = configs.command(feature);
            for (var builder : this.manager.buildCommandBuilders(config)) {
                this.manager.getCommandManager().command(builder.handler(context -> calls.incrementAndGet()));
            }
            String path = config.getUsages().getFirst().substring(1);
            CommandSender allowed = sender(Set.of(config.getPermission()));
            int before = calls.get();
            this.execute(allowed, path);
            assertThrows(ExecutionException.class, () -> this.execute(allowed, path.replaceFirst("sparrow-sync", "ssync")));
            assertEquals(before + 1, calls.get());
            assertThrows(ExecutionException.class, () -> this.execute(sender(Set.of()), path));
            assertEquals(before + 1, calls.get());
        }
    }

    @Test
    void additionalUsagesRegisterOnlyWhenConfigured() throws Exception {
        CommandConfig config = new CommandConfig(true, List.of("/ssync status", "/sparrow-sync status"), "status");
        var builders = this.manager.buildCommandBuilders(config);
        assertEquals(2, builders.size());
        AtomicInteger calls = new AtomicInteger();
        for (var builder : builders) {
            this.manager.getCommandManager().command(builder.handler(context -> calls.incrementAndGet()));
        }
        this.execute(sender(Set.of("status")), "ssync status");
        this.execute(sender(Set.of("status")), "sparrow-sync status");
        assertEquals(2, calls.get());
    }

    @Test
    void statusReadsOnlyLatestMetadataAndDoesNotAcquireTheLock() throws Exception {
        UUID player = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        this.redisPlayerName(player, "Steve");
        AtomicInteger reads = new AtomicInteger();
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(instance, method, args);
            assertEquals("listSnapshots", method.getName());
            SnapshotQuery query = (SnapshotQuery) args[0];
            assertEquals(player, query.player());
            assertEquals(1, query.limit());
            reads.incrementAndGet();
            return CompletableFuture.completedFuture(List.of(new SnapshotMeta(id, player, 1, SaveCause.COMMAND, true, "origin", 0)));
        });
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "storageProvider", storage);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "sessionLock", lock("other-server:token".getBytes(StandardCharsets.UTF_8), null));
        this.manager.registerFeature(new StatusCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("status"));
        this.execute(sender(Set.of("sparrow_sync.command.status")), "sparrow-sync status Steve");
        assertEquals(1, reads.get());
        assertTrue(this.text().contains("other-server"));
        assertTrue(this.text().contains(id.toString().substring(0, 8)));
        assertFalse(this.text().contains(id.toString()));
        assertEquals(5, this.text().lines().count());
        assertTrue(this.messages.stream().anyMatch(message -> hasCopy(message, id.toString())));
        assertTrue(this.messages.stream().anyMatch(message -> hasCopy(message, player.toString())));
        assertTrue(this.text().contains("No local session"));
        assertFalse(this.text().contains("command.status"));
    }

    @Test
    void statusDistinguishesMissingDataFromQueryFailure() throws Exception {
        this.redisPlayerName(UUID.randomUUID(), "Steve");
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(instance, method, args);
            return CompletableFuture.failedFuture(new IllegalStateException("database offline"));
        });
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "storageProvider", storage);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "sessionLock", lock(null, new IllegalStateException("redis offline")));
        this.manager.registerFeature(new StatusCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("status"));
        this.execute(sender(Set.of("sparrow_sync.command.status")), "sparrow-sync status Steve");
        assertTrue(this.text().contains("Query failed"));
        assertEquals(3, this.text().lines().count());
        assertFalse(this.text().contains("No lock"));
        assertFalse(this.text().contains("No stored snapshot"));
    }

    @SuppressWarnings("unchecked")
    private void redisPlayerName(UUID uuid, String name) {
        RedisAsyncCommands<byte[], byte[]> commands = proxy(RedisAsyncCommands.class, (instance, method, args) -> {
            assertEquals("get", method.getName());
            String key = new String((byte[]) args[0], StandardCharsets.UTF_8);
            assertTrue(key.startsWith("ss:user-name:"));
            assertEquals(name, new String(HexFormat.of().parseHex(key.substring("ss:user-name:".length())), StandardCharsets.UTF_8));
            AsyncCommand<byte[], byte[], byte[]> response = new AsyncCommand<>(new Command<>(CommandType.GET, new ByteArrayOutput<>(ByteArrayCodec.INSTANCE)));
            response.complete(UUIDUtils.toBytes(uuid));
            return response;
        });
        StatefulRedisConnection<byte[], byte[]> connection = proxy(StatefulRedisConnection.class, (instance, method, args) -> {
            assertEquals("async", method.getName());
            return commands;
        });
        RedisConnector connector = NmsPlayerFixture.allocate(RedisConnector.class);
        NmsPlayerFixture.set(RedisConnector.class, connector, "connection", connection);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "redisConnector", connector);
    }

    @Test
    void lockReadDistinguishesMissingMalformedAndUnavailableValues() {
        UUID player = UUID.randomUUID();
        assertEquals(Optional.empty(), lock(null, null).holder(player).join());
        assertThrows(Exception.class, () -> lock("malformed".getBytes(StandardCharsets.UTF_8), null).holder(player).join());
        assertThrows(Exception.class, () -> lock(null, new IllegalStateException("offline")).holder(player).join());
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void compactTemplatesKeepTheirLineBudgetAndAsyncDuration(String language) {
        this.manager.locale = language.equals("zh") ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        this.manager.handleCommandFeedback(sender(Set.of()), MessageConstants.COMMAND_STATUS_SYSTEM,
                Component.text("version-must-not-appear"), Component.text("server-version-must-not-appear"),
                Component.text("survival-01"), Component.text("PostgreSQL"), MessageConstants.COMMAND_CONNECTED.asComponent(),
                Component.text(4), Component.text(0), Component.text(16), Component.text(0));
        assertEquals(5, this.text().lines().count());
        assertFalse(this.text().contains("must-not-appear"));
        assertFalse(this.text().contains(language.equals("zh") ? "登录拒绝" : "Login rejections"));
        assertFalse(this.text().contains("<arg:"));
        assertTrue(this.text().contains("PostgreSQL"));
        this.messages.clear();
        this.manager.handleCommandFeedback(sender(Set.of()), MessageConstants.COMMAND_RELOAD_CONFIG_SUCCESS,
                Component.text(999), Component.text(18), Component.text(981));
        assertEquals(1, this.text().lines().count());
        assertTrue(this.text().startsWith(">> SparrowSync · "));
        assertTrue(this.text().contains(language.equals("zh") ? "异步 18 毫秒" : "async 18 ms"));
        assertFalse(this.text().contains("999"));
        assertFalse(this.text().contains("981"));
    }

    private static boolean hasCopy(Component component, String value) {
        if (ClickEvent.copyToClipboard(value).equals(component.clickEvent())) {
            return component.hoverEvent() != null && component.hoverEvent().value().equals(Component.text(value));
        }
        return component.children().stream().anyMatch(child -> hasCopy(child, value));
    }

    @Test
    void reloadHoldsItsMarkerUntilTheSyncStageCompletes() {
        ReloadConfig config = NmsPlayerFixture.allocate(ReloadConfig.class);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "configurationManager", config);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "reloading", new AtomicBoolean());
        List<Runnable> syncTasks = new ArrayList<>();
        CompletableFuture<SparrowSync.ReloadResult> result = this.plugin.reloadPlugin(Runnable::run, syncTasks::add);
        assertFalse(result.isDone());
        assertTrue(this.plugin.isReloading());
        assertFalse(this.plugin.reloadPlugin(Runnable::run, syncTasks::add).join().success());
        assertEquals(1, syncTasks.size());
        syncTasks.getFirst().run();
        assertTrue(result.join().success());
        assertFalse(this.plugin.isReloading());
    }

    @Test
    void failedReloadDoesNotScheduleALateSuccessStage() {
        ReloadConfig config = NmsPlayerFixture.allocate(ReloadConfig.class);
        config.fail = true;
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "configurationManager", config);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "reloading", new AtomicBoolean());
        List<Runnable> syncTasks = new ArrayList<>();
        assertFalse(this.plugin.reloadPlugin(Runnable::run, syncTasks::add).join().success());
        assertTrue(syncTasks.isEmpty());
        assertFalse(this.plugin.isReloading());
        config.fail = false;
        assertTrue(this.plugin.reloadPlugin(Runnable::run, Runnable::run).join().success());
    }

    private void execute(CommandSender sender, String command) throws Exception {
        this.manager.getCommandManager().commandExecutor().executeCommand(sender, command).get(5, TimeUnit.SECONDS);
    }

    private String text() {
        return String.join("\n", this.messages.stream().map(CommandFeaturesTest::textOf).toList());
    }

    private static String textOf(Component component) {
        return (component instanceof TextComponent text ? text.content() : "") + String.join("", component.children().stream().map(CommandFeaturesTest::textOf).toList());
    }

    private static boolean hasClick(Component component, String value) {
        if (ClickEvent.runCommand(value).equals(component.clickEvent())) return true;
        return component.children().stream().anyMatch(child -> hasClick(child, value));
    }

    private static CommandSender sender(Set<String> permissions) {
        return proxy(CommandSender.class, (instance, method, args) -> switch (method.getName()) {
            case "hasPermission" -> permissions.contains(args[0]);
            case "getName", "toString" -> "TestSender";
            case "isOp" -> false;
            default -> null;
        });
    }

    @SuppressWarnings("unchecked")
    private static SessionLock lock(byte[] value, Throwable failure) {
        RedisConnector connector = NmsPlayerFixture.allocate(RedisConnector.class);
        RedisAsyncCommands<byte[], byte[]> commands = proxy(RedisAsyncCommands.class, (instance, method, args) -> {
            assertEquals("get", method.getName(), "status must never write a lock");
            AsyncCommand<byte[], byte[], byte[]> response = new AsyncCommand<>(new Command<>(CommandType.GET, new ByteArrayOutput<>(ByteArrayCodec.INSTANCE)));
            if (failure == null) response.complete(value);
            else response.completeExceptionally(failure);
            return response;
        });
        StatefulRedisConnection<byte[], byte[]> connection = proxy(StatefulRedisConnection.class, (instance, method, args) -> {
            assertEquals("async", method.getName());
            return commands;
        });
        NmsPlayerFixture.set(RedisConnector.class, connector, "connection", connection);
        return new SessionLock(connector, "local");
    }

    private static Object value(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class TestCloud extends org.incendo.cloud.CommandManager<CommandSender> {
        private TestCloud() {
            super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(CommandSender sender, String permission) {
            return sender.hasPermission(permission);
        }
    }

    private static final class ReloadConfig extends ConfigurationManager {
        private boolean fail;

        private ReloadConfig() {
            super(null);
        }

        @Override
        public void reload() {
            if (this.fail) throw new IllegalStateException("invalid config");
        }
    }

    private static final class TestManager extends AbstractCommandManager {
        private Locale locale = Locale.ENGLISH;

        private TestManager(SparrowSync plugin) {
            super(plugin, new TestCloud());
        }

        @Override
        protected Locale getLocale(CommandSender sender) {
            return this.locale;
        }

        @Override
        public Index<String, CommandFeature> features() {
            return Index.create(CommandFeature::getFeatureID, List.of());
        }
    }
}
