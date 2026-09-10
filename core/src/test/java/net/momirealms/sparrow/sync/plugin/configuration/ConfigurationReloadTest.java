package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationReloadTest {
    private static final String CONFIG_FILE = "config.yml";
    private static final String SERVER_FILE = "server.yml";
    // 把空的 PDC 黑名单换成一个含空路径的列表, 触发的正是现有入参校验拒绝的写法
    private static final String EMPTY_BLACKLIST = "(?m)^([ \\t]*)pdc-merge-namespaces:.*$";

    @TempDir Path directory;
    private ConfigurationManager manager;
    private Object previousConfig;
    private Object previousServer;

    @BeforeEach
    void setUp() {
        this.previousConfig = field(PluginConfig.class, "config");
        this.previousServer = field(ServerConfig.class, "config");
        NmsPlayerFixture.set(PluginConfig.class, null, "config", new PluginConfig.ConfigDefinition());
        NmsPlayerFixture.set(ServerConfig.class, null, "config", new ServerConfig.ConfigDefinition());
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataFolderPath", this.directory);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", new SyncLogger(proxy(PluginLogger.class, (instance, method, args) -> null)));
        this.manager = new ConfigurationManager(plugin);
    }

    @AfterEach
    void restoreConfig() {
        NmsPlayerFixture.set(PluginConfig.class, null, "config", this.previousConfig);
        NmsPlayerFixture.set(ServerConfig.class, null, "config", this.previousServer);
    }

    @Test
    void reloadCreatesBothFilesAndAppliesTheirValues() throws Exception {
        assertDoesNotThrow(() -> this.manager.reload());
        assertTrue(Files.exists(this.directory.resolve(CONFIG_FILE)));
        Path serverFile = this.directory.resolve(SERVER_FILE);
        assertTrue(Files.exists(serverFile));
        // 正常路径必须真的生效, 否则下面的失败用例断言"没有生效"就没有意义
        String server = Files.readString(serverFile, StandardCharsets.UTF_8);
        Files.writeString(serverFile, server.replaceAll("(?m)^server-id:.*$", "server-id: server-b"), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> this.manager.reload());
        assertEquals("server-b", ServerConfig.serverId());
    }

    @Test
    void invalidConfigFileThrowsAndKeepsPreviousValues() throws Exception {
        assertDoesNotThrow(() -> this.manager.reload());
        PluginConfig.ConfigDefinition alive = new PluginConfig.ConfigDefinition();
        alive.synchronization.maxSnapshots = 7;
        NmsPlayerFixture.set(PluginConfig.class, null, "config", alive);

        Path configFile = this.directory.resolve(CONFIG_FILE);
        String content = Files.readString(configFile, StandardCharsets.UTF_8);
        String broken = content.replaceFirst(EMPTY_BLACKLIST, "$1pdc-merge-namespaces: [[]]");
        assertNotEquals(content, broken, "默认配置应包含 PDC 黑名单段");
        Files.writeString(configFile, broken, StandardCharsets.UTF_8);

        // 校验类失败自带可读原因, 不再被吞掉, 也没有被包装
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.manager.reload());
        assertTrue(failure.getMessage().startsWith("PDC merge blacklist entries must be a path string"), failure.getMessage());
        assertSame(alive, field(PluginConfig.class, "config"));
        assertEquals(7, PluginConfig.synchronization$maxSnapshots());
    }

    @Test
    void unreadableConfigFileIsReportedWithItsName() throws Exception {
        assertDoesNotThrow(() -> this.manager.reload());
        Files.delete(this.directory.resolve(CONFIG_FILE));
        Files.createDirectory(this.directory.resolve(CONFIG_FILE));

        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> this.manager.reload());
        assertEquals("Failed to load " + CONFIG_FILE, failure.getMessage());
    }

    @Test
    void failingConfigFileStopsTheRemainingFiles() throws Exception {
        assertDoesNotThrow(() -> this.manager.reload());
        Path serverFile = this.directory.resolve(SERVER_FILE);
        String server = Files.readString(serverFile, StandardCharsets.UTF_8);
        Files.writeString(serverFile, server.replaceAll("(?m)^server-id:.*$", "server-id: server-b"), StandardCharsets.UTF_8);
        Files.delete(this.directory.resolve(CONFIG_FILE));
        Files.createDirectory(this.directory.resolve(CONFIG_FILE));

        assertThrows(UncheckedIOException.class, () -> this.manager.reload());
        // config.yml 失败后不再继续载入 server.yml, 不会留下"一半新一半旧"的状态
        assertTrue(ServerConfig.serverId().isEmpty());
    }

    @Test
    void failingServerConfigAlsoThrowsAndKeepsItsPreviousValue() throws Exception {
        assertDoesNotThrow(() -> this.manager.reload());
        Files.delete(this.directory.resolve(SERVER_FILE));
        Files.createDirectory(this.directory.resolve(SERVER_FILE));

        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> this.manager.reload());
        assertEquals("Failed to load " + SERVER_FILE, failure.getMessage());
        assertTrue(ServerConfig.serverId().isEmpty());
    }

    private static Object field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
