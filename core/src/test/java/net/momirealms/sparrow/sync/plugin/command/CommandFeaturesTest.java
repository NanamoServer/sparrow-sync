package net.momirealms.sparrow.sync.plugin.command;

import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotListCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.GuiCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.MigrateCommand;
import net.momirealms.sparrow.sync.compatibility.CompatibilityManager;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotViewCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.ExceptionListCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.ExceptionViewCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.ExceptionDeleteCommand;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.SnapshotDetails;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionHeader;
import net.momirealms.sparrow.sync.snapshot.page.SnapshotPage;
import net.momirealms.sparrow.sync.util.ChatTextUtils;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
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
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotPinCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotUnpinCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotDeleteCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotExportCommand;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.junit.jupiter.api.io.TempDir;
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
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.util.concurrent.Executor;
import java.nio.file.Path;
import java.util.Map;
import java.io.StringReader;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
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
    @TempDir Path directory;
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
    void migrationRequiresPermissionAndConsoleAndReportsUnavailableSource() throws Exception {
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "compatibilityManager", new CompatibilityManager(this.plugin));
        this.manager.registerFeature(new MigrateCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("migrate"));
        assertThrows(ExecutionException.class, () -> this.execute(sender(Set.of()), "sparrow-sync data migrate invsync"));
        this.manager.locale = Locale.SIMPLIFIED_CHINESE;
        this.execute(player(Set.of("sparrow_sync.command.migrate")), "sparrow-sync data migrate invsync");
        assertTrue(this.text().contains("控制台"));
        this.messages.clear();
        ConsoleCommandSender console = proxy(ConsoleCommandSender.class, (instance, method, args) -> method.getName().equals("hasPermission") ? true : null);
        this.execute(console, "sparrow-sync data migrate HuskSync");
        assertTrue(this.text().contains("husksync"));
        assertTrue(this.text().contains("未就绪"));
        this.messages.clear();
        this.manager.locale = Locale.ENGLISH;
        RemoteConsoleCommandSender remote = proxy(RemoteConsoleCommandSender.class, (instance, method, args) -> method.getName().equals("hasPermission") ? true : null);
        this.execute(remote, "sparrow-sync data migrate invsync");
        assertTrue(this.text().contains("Source invsync is unavailable"));
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
        for (String feature : List.of("status", "reload", "test", "snapshot_capture", "snapshot_restore", "snapshot_pin", "snapshot_unpin", "snapshot_delete", "snapshot_export", "snapshot_import", "exception_delete")) {
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

    @ParameterizedTest
    @ValueSource(strings = {"pin", "unpin", "delete", "export"})
    void snapshotManagementCommandsUseOnlySnapshotIdAndTheirPermission(String action) throws Exception {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
        UUID player = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "playerDirectory", null);
        AtomicInteger changes = new AtomicInteger();
        Snapshot snapshot = new Snapshot(new SnapshotMeta(id, player, 1, SaveCause.COMMAND, false, "origin", 0), Map.of());
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> switch (method.getName()) {
            case "snapshot" -> {
                assertEquals(id, args[0]);
                changes.incrementAndGet();
                yield CompletableFuture.completedFuture(Optional.of(snapshot));
            }
            case "setPinned" -> {
                assertEquals(id, args[0]);
                assertEquals(action.equals("pin"), args[1]);
                changes.incrementAndGet();
                yield CompletableFuture.completedFuture(true);
            }
            case "deleteSnapshot" -> {
                assertEquals(id, args[0]);
                changes.incrementAndGet();
                yield CompletableFuture.completedFuture(true);
            }
            default -> throw new AssertionError(method.getName());
        });
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "storageProvider", storage);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "dataFolderPath", this.directory);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "binaryCodec", new BinarySnapshotCodec(CompressorRegistry.NONE));
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "scheduler", proxy(SchedulerAdapter.class, (instance, method, args) -> {
            assertEquals("async", method.getName());
            return (Executor) Runnable::run;
        }));
        SnapshotService service = new SnapshotService(this.plugin);
        service.onLoad();
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "snapshotService", service);
        CommandFeature feature = switch (action) {
            case "pin" -> new SnapshotPinCommand(this.manager, this.plugin);
            case "unpin" -> new SnapshotUnpinCommand(this.manager, this.plugin);
            case "delete" -> new SnapshotDeleteCommand(this.manager, this.plugin);
            case "export" -> new SnapshotExportCommand(this.manager, this.plugin);
            default -> throw new AssertionError(action);
        };
        this.manager.registerFeature(feature, new CommandsConfig.ConfigDefinition().command("snapshot_" + action));
        String command = "sparrow-sync snapshot " + action + (action.equals("export") ? " binary " : " ") + id;
        assertThrows(ExecutionException.class, () -> this.execute(sender(Set.of()), command));
        assertEquals(0, changes.get());
        this.execute(sender(Set.of("sparrow_sync.command." + action)), command);
        assertEquals(1, changes.get());
        assertFalse(this.text().isBlank());
        assertTrue(this.messages.stream().anyMatch(message -> hasCopy(message, id.toString())));
        assertFalse(this.text().contains("command.snapshot"));
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
        assertTrue(this.text().contains(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(1))));
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
            response.complete(uuid == null ? null : UUIDUtils.toBytes(uuid));
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
        assertTrue(this.text().contains(language.equals("zh") ? "异步耗时 18 毫秒" : "async 18 ms"));
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

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh_cn"})
    void textPanelRendersOneLinePerRecordAndExactPermissionBoundCommands(String language) {
        this.manager.locale = language.equals("zh_cn") ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        UUID id = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        this.registerPanelCommands();
        SnapshotMeta meta = new SnapshotMeta(id, playerId, 1788877230000L, SaveCause.COMMAND, true, "<red>origin", 0);
        CommandSender viewer = player(Set.of("sparrow_sync.command.view", "custom.delete", "sparrow_sync.command.export"));
        this.showSnapshots(viewer, new PlayerIdentity(playerId, "Steve"), new SnapshotPage(0, 5, 12, List.of(meta)));
        assertEquals(3, this.text().lines().count());
        assertTrue(this.text().contains("<red>or..."));
        assertTrue(this.messages.stream().anyMatch(message -> hasCopy(message, "<red>origin")));
        assertTrue(this.text().contains("1/3"));
        assertFalse(this.text().contains("command.panel"));
        assertTrue(this.messages.stream().anyMatch(message -> hasEvent(message, ClickEvent.copyToClipboard(id.toString()))));
        assertTrue(this.messages.stream().anyMatch(message -> hasEvent(message, ClickEvent.suggestCommand("/custom erase " + id))));
        assertFalse(this.messages.stream().anyMatch(message -> hasClick(message, "/custom erase " + id)));
        assertFalse(this.messages.stream().anyMatch(message -> hasClick(message, "/sparrow-sync snapshot export binary " + id)));
        assertTrue(this.messages.stream().anyMatch(message -> hasClick(message, "/sparrow-sync snapshot export json " + id)));
        assertTrue(this.text().contains(language.equals("zh_cn") ? "★ [查] [删] [导]" : "★ [V] [D] [J]"));
        assertTrue(this.messages.stream().anyMatch(message -> hasClick(message, "/custom history Steve 2")));
        assertFalse(this.messages.stream().anyMatch(message -> hasClick(message, "/custom history Steve 0")));
        assertTrue(this.messages.stream().anyMatch(message -> hasClick(message, "/custom history Steve 1")));
        this.assertTranslatedHover(this.messages.getFirst());
    }

    @Test
    void textPanelHidesMutationClicksFromViewOnlySendersAndUsesLivePermission() throws Exception {
        this.registerPanelCommands();
        UUID playerId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        SnapshotPage page = new SnapshotPage(0, 5, 1, List.of(new SnapshotMeta(id, playerId, 1, SaveCause.COMMAND, false, "origin", 0)));
        Set<String> permissions = new HashSet<>(Set.of("sparrow_sync.command.view", "custom.delete"));
        Player viewer = player(permissions);
        this.showSnapshots(viewer, new PlayerIdentity(playerId, "Steve"), page);
        assertTrue(this.messages.stream().anyMatch(message -> hasEvent(message, ClickEvent.suggestCommand("/custom erase " + id))));
        permissions.remove("custom.delete");
        assertThrows(ExecutionException.class, () -> this.execute(viewer, "custom erase " + id));
        this.messages.clear();
        this.showSnapshots(viewer, new PlayerIdentity(playerId, "Steve"), page);
        assertFalse(this.messages.stream().anyMatch(message -> hasEvent(message, ClickEvent.suggestCommand("/custom erase " + id))));
        assertFalse(this.messages.stream().anyMatch(message -> hasClick(message, "/sparrow-sync snapshot export json " + id)));
    }

    @Test
    void consolePanelPrintsFullIdsDatesAndUsableCommandsWithoutEvents() {
        this.registerPanelCommands();
        UUID playerId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        this.showSnapshots(sender(Set.of("sparrow_sync.command.view", "custom.delete", "sparrow_sync.command.export")),
                new PlayerIdentity(playerId, "Steve"), new SnapshotPage(1, 5, 6, List.of(new SnapshotMeta(id, playerId, 1, SaveCause.COMMAND, false, "origin", 0))));
        assertTrue(this.text().contains(id.toString()));
        assertTrue(this.text().contains(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(1))));
        assertTrue(this.text().contains("/custom erase " + id));
        assertTrue(this.text().contains("/custom history Steve 1"));
        assertTrue(this.text().contains("2/2"));
        assertFalse(this.text().contains("command.panel"));
        this.assertNoEvents(this.messages.getFirst());
    }

    @Test
    void snapshotListReadsOnlyCurrentPageAndClampsInputUsingPagination() throws Exception {
        UUID playerId = UUID.randomUUID();
        this.redisPlayerName(playerId, "Steve");
        AtomicInteger reads = new AtomicInteger();
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> {
            SnapshotQuery query = (SnapshotQuery) args[0];
            assertEquals(playerId, query.player());
            return switch (method.getName()) {
                case "countSnapshots" -> CompletableFuture.completedFuture(8L);
                case "listSnapshots" -> {
                    assertEquals(7, query.offset());
                    assertEquals(7, query.limit());
                    reads.incrementAndGet();
                    yield CompletableFuture.completedFuture(List.of(new SnapshotMeta(UUID.randomUUID(), playerId, 1, SaveCause.COMMAND, false, "origin", 0)));
                }
                default -> throw new AssertionError(method.getName());
            };
        });
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "storageProvider", storage);
        this.manager.registerFeature(new SnapshotListCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("snapshot_list"));
        CommandSender viewer = sender(Set.of("sparrow_sync.command.view"));
        assertThrows(ExecutionException.class, () -> this.execute(sender(Set.of()), "sparrow-sync snapshot list Steve 99"));
        assertThrows(ExecutionException.class, () -> this.execute(viewer, "sparrow-sync snapshot list Steve 0"));
        this.execute(viewer, "sparrow-sync snapshot list Steve 99");
        assertEquals(1, reads.get());
        assertTrue(this.text().contains("2/2"));
    }

    @Test
    void snapshotListEmptyAndFailedQueriesHaveDistinctFeedback() throws Exception {
        this.redisPlayerName(UUID.randomUUID(), "Steve");
        AtomicBoolean fail = new AtomicBoolean();
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> {
            assertEquals("countSnapshots", method.getName());
            return fail.get() ? CompletableFuture.failedFuture(new IOException("offline")) : CompletableFuture.completedFuture(0L);
        });
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "storageProvider", storage);
        this.manager.registerFeature(new SnapshotListCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("snapshot_list"));
        this.execute(sender(Set.of("sparrow_sync.command.view")), "sparrow-sync snapshot list Steve");
        assertTrue(this.text().contains("1/1"));
        assertTrue(this.text().contains("No records"));
        this.messages.clear();
        fail.set(true);
        this.execute(sender(Set.of("sparrow_sync.command.view")), "sparrow-sync snapshot list Steve");
        assertTrue(this.text().contains("Query failed"));
        assertFalse(this.text().contains("No records"));
    }

    @Test
    void exceptionListSeparatesPageFromPlayerAndNeverDecodesBodies() throws Exception {
        SnapshotFiles files = this.installArchiveService();
        UUID playerId = UUID.randomUUID();
        this.redisPlayerName(playerId, "Steve");
        for (int i = 0; i < 8; i++) {
            Path body = files.exceptions().resolve("corrupted/archive-" + i + ".snapshot");
            Files.createDirectories(body.getParent());
            Files.writeString(body, "not a snapshot");
            new ExceptionHeader(new SnapshotMeta(UUID.randomUUID(), playerId, i, SaveCause.COMMAND, false, "origin", 0), "Steve").write(body);
        }
        Files.writeString(files.exceptions().resolve("corrupted/legacy.snapshot"), "not a snapshot either");
        this.manager.registerFeature(new ExceptionListCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("exception_list"));
        CommandSender viewer = sender(Set.of("sparrow_sync.command.view"));
        this.execute(viewer, "sparrow-sync exception list 2");
        assertTrue(this.text().contains("2/2"));
        assertTrue(this.text().contains("legacy.snapshot"));
        assertTrue(this.text().contains("Unknown player"));
        assertFalse(this.text().contains("[JSON]"));
        this.messages.clear();
        this.execute(viewer, "sparrow-sync exception list Steve 99");
        assertTrue(this.text().contains("2/2"));
        assertFalse(this.text().contains("legacy.snapshot"));
        assertTrue(this.text().contains("Body unchecked"));
        this.messages.clear();
        this.execute(viewer, "sparrow-sync exception list 0");
        assertTrue(this.text().contains("Page must be an integer"));
        this.messages.clear();
        this.execute(viewer, "sparrow-sync exception list 9999999999999999999");
        assertTrue(this.text().contains("Page must be an integer"));
    }

    @Test
    void exceptionViewShowsCorruptionAndMissingBodyToConsole() throws Exception {
        SnapshotFiles files = this.installArchiveService();
        Path body = files.exceptions().resolve("corrupted/archive.snapshot");
        Files.createDirectories(body.getParent());
        Files.writeString(body, "broken");
        this.manager.registerFeature(new ExceptionViewCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("exception_view"));
        CommandSender viewer = sender(Set.of("sparrow_sync.command.view"));
        this.execute(viewer, "sparrow-sync exception view corrupted/archive.snapshot");
        assertTrue(this.text().contains("Cannot decode body"));
        assertTrue(this.text().contains("Header missing"));
        this.messages.clear();
        Files.delete(body);
        this.execute(viewer, "sparrow-sync exception view corrupted/archive.snapshot");
        assertTrue(this.text().contains("Body missing"));
        this.messages.clear();
        AtomicInteger scheduled = this.installGuiScheduler();
        this.execute(player(Set.of("sparrow_sync.command.view")), "sparrow-sync exception view corrupted/archive.snapshot");
        assertEquals(1, scheduled.get());
        assertTrue(this.messages.isEmpty());
    }

    @Test
    void guiCommandsRequirePlayerAndViewPermissionAndPreserveSnapshotId() throws Exception {
        this.redisPlayerName(UUID.randomUUID(), "TestPlayer");
        AtomicInteger scheduled = this.installGuiScheduler();
        this.manager.registerFeature(new GuiCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("gui"));
        this.manager.registerFeature(new SnapshotViewCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("snapshot_view"));
        Player allowed = player(Set.of("sparrow_sync.command.view"));
        this.execute(allowed, "sparrow-sync gui TestPlayer");
        this.execute(allowed, "sparrow-sync snapshot view TestPlayer 12345678-1234-1234-1234-123456789abc");
        assertEquals(2, scheduled.get());
        assertThrows(ExecutionException.class, () -> this.execute(player(Set.of()), "sparrow-sync gui TestPlayer"));
        assertThrows(ExecutionException.class, () -> this.execute(sender(Set.of("sparrow_sync.command.view")), "sparrow-sync gui TestPlayer"));
        assertThrows(ExecutionException.class, () -> this.execute(allowed, "sparrow-sync snapshot view TestPlayer invalid-id"));
        assertEquals(2, scheduled.get());
        this.messages.clear();
        SnapshotMeta meta = new SnapshotMeta(UUID.fromString("12345678-1234-1234-1234-123456789abc"), UUID.randomUUID(), 1, SaveCause.COMMAND, false, "server", 1);
        this.showSnapshots(allowed, new PlayerIdentity(meta.player(), "TestPlayer"), new SnapshotPage(0, 7, 1, List.of(meta)));
        assertTrue(this.messages.stream().anyMatch(message -> hasClick(message, "/sparrow-sync snapshot view TestPlayer " + meta.id())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh_cn"})
    void guiWaitsForPlayerLookupAndReportsMissingDataWithoutOpeningMenu(String language) throws Exception {
        this.manager.locale = language.equals("zh_cn") ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        this.redisPlayerName(null, "UnknownPlayer");
        CompletableFuture<Optional<UUID>> lookup = new CompletableFuture<>();
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> {
            assertEquals("lookupUser", method.getName());
            assertEquals("UnknownPlayer", args[0]);
            return lookup;
        });
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "storageProvider", storage);
        AtomicInteger scheduled = this.installGuiScheduler();
        this.manager.registerFeature(new GuiCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("gui"));

        this.execute(player(Set.of("sparrow_sync.command.view")), "sparrow-sync gui UnknownPlayer");
        assertEquals(0, scheduled.get());
        assertTrue(this.messages.isEmpty());
        lookup.complete(Optional.empty());

        assertEquals(0, scheduled.get());
        assertEquals(1, this.messages.size());
        assertTrue(this.text().contains(language.equals("zh_cn")
                ? "未找到该玩家的数据，这位玩家可能从未在本服登录过。"
                : "No data was found for this player. They may never have joined this server."));
    }

    @Test
    void guiReportsLookupFailureWithoutOpeningMenu() throws Exception {
        this.redisPlayerName(null, "TestPlayer");
        StorageProvider storage = proxy(StorageProvider.class, (instance, method, args) -> CompletableFuture.failedFuture(new IllegalStateException("database offline")));
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "storageProvider", storage);
        AtomicInteger scheduled = this.installGuiScheduler();
        this.manager.registerFeature(new GuiCommand(this.manager, this.plugin), new CommandsConfig.ConfigDefinition().command("gui"));

        this.execute(player(Set.of("sparrow_sync.command.view")), "sparrow-sync gui TestPlayer");

        assertEquals(0, scheduled.get());
        assertEquals(1, this.messages.size());
        assertTrue(this.text().contains("Query failed"));
    }

    private AtomicInteger installGuiScheduler() {
        AtomicInteger scheduled = new AtomicInteger();
        // 只记录异步构建任务, 命令入口无需先访问实体调度器或玩家物品栏.
        Executor async = task -> scheduled.incrementAndGet();
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "scheduler", proxy(SchedulerAdapter.class, (instance, method, args) -> method.getName().equals("async") ? async : null));
        return scheduled;
    }

    private SnapshotFiles installArchiveService() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
        SnapshotFiles files = new SnapshotFiles(this.directory, new BinarySnapshotCodec(CompressorRegistry.NONE));
        SnapshotService service = new SnapshotService(this.plugin);
        NmsPlayerFixture.set(SnapshotService.class, service, "files", files);
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "scheduler", proxy(SchedulerAdapter.class, (instance, method, args) -> (Executor) Runnable::run));
        NmsPlayerFixture.set(SnapshotService.class, service, "details", new SnapshotDetails(null, files, new DataRegistry(), Runnable::run));
        NmsPlayerFixture.set(SparrowSync.class, this.plugin, "snapshotService", service);
        return files;
    }

    @Test
    void exceptionPanelBindsFullPathAndShowsIndependentHeaderAndBodyStates() {
        this.manager.registerFeature(new ExceptionDeleteCommand(this.manager, this.plugin),
                new CommandConfig(true, List.of("/archive remove"), "custom.archive"));
        String path = "corrupted/archive with spaces.snapshot";
        SnapshotFiles.ExceptionEntry entry = new SnapshotFiles.ExceptionEntry(path, "corrupted", null, SnapshotFiles.HeadStatus.UNREADABLE, false);
        this.showExceptions(player(Set.of("custom.archive")), null, new SnapshotFiles.ExceptionPage(0, 5, 1, 1, List.of(entry)));
        assertTrue(this.text().contains("Header unreadable"));
        assertTrue(this.text().contains("Body missing"));
        assertTrue(this.text().contains("Unknown player"));
        assertTrue(this.messages.stream().anyMatch(message -> hasEvent(message, ClickEvent.suggestCommand("/archive remove " + path))));
        assertFalse(this.text().contains("[Binary]"));
        this.assertTranslatedHover(this.messages.getFirst());
    }

    // 直接验证具体命令的文字输出, 分页查询与命令权限由相邻的执行测试覆盖.
    private void showSnapshots(CommandSender sender, PlayerIdentity player, SnapshotPage page) {
        try {
            var render = SnapshotListCommand.class.getDeclaredMethod("renderPage", CommandSender.class, PlayerIdentity.class, SnapshotPage.class);
            render.setAccessible(true);
            render.invoke(new SnapshotListCommand(this.manager, this.plugin), sender, player, page);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private void showExceptions(CommandSender sender, String player, SnapshotFiles.ExceptionPage page) {
        try {
            var render = ExceptionListCommand.class.getDeclaredMethod("renderPage", CommandSender.class, String.class, SnapshotFiles.ExceptionPage.class);
            render.setAccessible(true);
            render.invoke(new ExceptionListCommand(this.manager, this.plugin), sender, player, page);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private void registerPanelCommands() {
        CommandsConfig.ConfigDefinition defaults = new CommandsConfig.ConfigDefinition();
        this.manager.registerFeature(new SnapshotListCommand(this.manager, this.plugin), new CommandConfig(true, List.of("/custom history"), "sparrow_sync.command.view"));
        this.manager.registerFeature(new SnapshotDeleteCommand(this.manager, this.plugin), new CommandConfig(true, List.of("/custom erase"), "custom.delete"));
        this.manager.registerFeature(new SnapshotExportCommand(this.manager, this.plugin), defaults.command("snapshot_export"));
        this.manager.registerFeature(new SnapshotPinCommand(this.manager, this.plugin), new CommandConfig(true, List.of("/custom pin"), "custom.pin"));
        this.manager.registerFeature(new SnapshotUnpinCommand(this.manager, this.plugin), new CommandConfig(true, List.of("/custom unpin"), "custom.unpin"));
    }

    @Test
    void textPanelAlignsButtonsAndRetainsFullSourceInteraction() {
        this.registerPanelCommands();
        UUID playerId = UUID.randomUUID();
        List<SnapshotMeta> records = List.of(
                new SnapshotMeta(UUID.fromString("ffffffff-0000-0000-0000-000000000000"), playerId, 1, SaveCause.DISCONNECT, false, "i", 0),
                new SnapshotMeta(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000"), playerId, 1, SaveCause.SHUTDOWN, true, "WWWWWWWWWW", 0),
                new SnapshotMeta(UUID.fromString("ffffaaaa-0000-0000-0000-000000000000"), playerId, 1, SaveCause.WORLD_SAVE, false, "server-name-is-long", 0));
        this.showSnapshots(player(Set.of("sparrow_sync.command.view")), new PlayerIdentity(playerId, "Steve"), new SnapshotPage(0, 5, 3, records));
        assertEquals(5, this.text().lines().count());
        assertTrue(this.text().contains("WWWWWWWWWW"));
        assertTrue(this.text().contains("server-..."));
        assertFalse(this.text().contains("server-name-is-long"));
        for (SnapshotMeta meta : records) {
            assertTrue(this.messages.stream().anyMatch(message -> hasCopy(message, meta.server())));
        }
        List<Integer> buttonOffsets = new ArrayList<>();
        collectLineWidths(this.messages.getFirst(), false, new int[1], buttonOffsets, new ArrayList<>());
        assertEquals(3, buttonOffsets.size());
        assertEquals(1, buttonOffsets.stream().distinct().count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "12345678901", "测试服务器名字超过十个字符", "abcdef😀ghijkl"})
    void sourceAbbreviationKeepsTenCodePointsIncludingDotsAndConsoleKeepsFullName(String server) {
        this.registerPanelCommands();
        UUID playerId = UUID.randomUUID();
        SnapshotPage page = new SnapshotPage(0, 5, 1, List.of(new SnapshotMeta(UUID.randomUUID(), playerId, 1, SaveCause.COMMAND, false, server, 0)));
        PlayerIdentity identity = new PlayerIdentity(playerId, "Steve");
        this.showSnapshots(player(Set.of()), identity, page);
        String expected = server.codePointCount(0, server.length()) <= 10 ? server : server.substring(0, server.offsetByCodePoints(0, 7)) + "...";
        assertTrue(this.text().contains(expected));
        assertTrue(this.messages.stream().anyMatch(message -> hasCopy(message, server)));
        this.messages.clear();
        this.showSnapshots(sender(Set.of()), identity, page);
        assertTrue(this.text().contains(server));
        this.assertNoEvents(this.messages.getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh_cn"})
    void screenshotRowsFitDefaultChatWidthWithAlignedButtons(String language) {
        this.manager.locale = language.equals("zh_cn") ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        this.registerPanelCommands();
        UUID playerId = UUID.randomUUID();
        String[] ids = {"55be881a", "d582f7cc", "b44cdafb", "a9285aab", "60f0ad54"};
        List<SnapshotMeta> records = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            records.add(new SnapshotMeta(UUID.fromString(ids[i] + "-0000-0000-0000-000000000000"), playerId, 1,
                    i == 1 || i == 4 ? SaveCause.WORLD_SAVE : SaveCause.SHUTDOWN, i == 2, "Paper_26.2", 0));
        }
        this.showSnapshots(player(Set.of("sparrow_sync.command.view")),
                new PlayerIdentity(playerId, "Catnies"), new SnapshotPage(0, 5, 34, records));
        List<Integer> offsets = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        collectLineWidths(this.messages.getFirst(), false, new int[1], offsets, widths);
        assertEquals(5, offsets.size());
        assertEquals(1, offsets.stream().distinct().count());
        assertEquals(6, widths.size());
        for (int i = 1; i < widths.size(); i++) {
            assertTrue(widths.get(i) <= 320, "row " + i + " exceeds default chat width: " + widths.get(i));
        }
    }

    private static void collectLineWidths(Component component, boolean inheritedBold, int[] pixels, List<Integer> offsets, List<Integer> widths) {
        boolean bold = switch (component.decoration(TextDecoration.BOLD)) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_SET -> inheritedBold;
        };
        if (component instanceof TextComponent text) {
            String content = text.content();
            int length = content.length();
            for (int i = 0; i < length;) {
                int character = content.codePointAt(i);
                if (character == '\n') {
                    widths.add(pixels[0]);
                    pixels[0] = 0;
                } else {
                    if (character == '☆' || character == '★') {
                        offsets.add(pixels[0]);
                    }
                    pixels[0] += ChatTextUtils.width(new String(Character.toChars(character))) + (bold ? 1 : 0);
                }
                i += Character.charCount(character);
            }
        }
        for (Component child : component.children()) collectLineWidths(child, bold, pixels, offsets, widths);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void textPanelAlwaysShowsPinStateAndBindsTheMatchingPermission(boolean pinned) throws Exception {
        this.registerPanelCommands();
        UUID playerId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        String action = pinned ? "unpin" : "pin";
        String opposite = pinned ? "pin" : "unpin";
        String command = "/custom " + action + " " + id;
        PlayerIdentity identity = new PlayerIdentity(playerId, "Steve");
        SnapshotPage page = new SnapshotPage(0, 5, 1, List.of(new SnapshotMeta(id, playerId, 1, SaveCause.COMMAND, pinned, "origin", 0)));
        Set<String> permissions = new HashSet<>(Set.of("sparrow_sync.command.view", "custom." + action));
        Player viewer = player(permissions);
        this.showSnapshots(viewer, identity, page);
        assertTrue(this.text().contains(pinned ? "★ [V]" : "☆ [V]"));
        assertTrue(this.messages.stream().anyMatch(message -> hasClick(message, command)));
        assertFalse(this.messages.stream().anyMatch(message -> hasClick(message, "/custom " + opposite + " " + id)));
        this.assertTranslatedHover(this.messages.getFirst());

        permissions.remove("custom." + action);
        permissions.add("custom." + opposite);
        assertThrows(ExecutionException.class, () -> this.execute(viewer, command.substring(1)));
        this.messages.clear();
        this.showSnapshots(viewer, identity, page);
        assertTrue(this.text().contains(pinned ? "★ [V]" : "☆ [V]"));
        assertFalse(this.messages.stream().anyMatch(message -> hasClick(message, command)));
    }

    private void assertTranslatedHover(Component component) {
        if (component.hoverEvent() != null && component.hoverEvent().value() instanceof Component hover) {
            assertFalse(textOf(hover).contains("command.panel"));
            assertFalse(hover instanceof TranslatableComponent);
        }
        for (Component child : component.children()) this.assertTranslatedHover(child);
    }

    private void assertNoEvents(Component component) {
        assertNull(component.clickEvent());
        for (Component child : component.children()) this.assertNoEvents(child);
    }

    private static boolean hasEvent(Component component, ClickEvent event) {
        return event.equals(component.clickEvent()) || component.children().stream().anyMatch(child -> hasEvent(child, event));
    }

    private static Player player(Set<String> permissions) {
        return proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
            case "hasPermission" -> permissions.contains(args[0]);
            case "getName", "toString" -> "Viewer";
            case "getUniqueId" -> UUID.fromString("12345678-1234-1234-1234-123456789abc");
            case "isOp" -> false;
            default -> null;
        });
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
            return Index.create(CommandFeature::getFeatureID, List.copyOf(this.registeredFeatures));
        }
    }
}
