package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证共享 YAML 的 DataKey 序列化与启动期名单, 配置重载后已冻结注册表继续使用原名单. */
class UnknownDataConfigurationTest {
    /**
     * 配置和第三方注册共同组成丢弃名单, 重载配置不会改动已冻结的运行时集合.
     *
     * @param directory 本次测试的配置目录
     * @throws Exception 当配置加载或恢复静态配置失败时
     */
    @Test
    void keysRoundTripAndFrozenRegistrySurvivesReload(@TempDir Path directory) throws Exception {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
            if (method.getName().equals("dataFolderPath")) return directory;
            throw new AssertionError(method.getName());
        });
        ConfigurationManager manager = new ConfigurationManager(plugin);
        PluginConfig config = new PluginConfig(plugin, manager.sparrowYaml());
        Field current = PluginConfig.class.getDeclaredField("config");
        current.setAccessible(true);
        Object previous = current.get(null);
        try {
            config.reload();
            assertTrue(PluginConfig.synchronization$discardUnknownData().isEmpty());
            Path path = directory.resolve("config.yml");
            Files.writeString(path, "config-version: '4'\nsynchronization:\n  discard-unknown-data: [location, 'external:book']\n");
            config.reload();
            Set<DataKey> configured = PluginConfig.synchronization$discardUnknownData();
            assertEquals(Set.of(DataKey.sparrow("location"), DataKey.of("external", "book")), configured);
            DataRegistry registry = new DataRegistry();
            assertTrue(registry.shouldDropUnknown(DataKey.sparrow("location")));
            assertTrue(registry.shouldDropUnknown(DataKey.of("external", "book")));
            DataKey apiKey = DataKey.of("external", "api");
            registry.registerUnknownDrop(apiKey);
            registry.freeze();

            // 使用同一 SparrowYaml 写回配置, DataKey 应以带命名空间的字符串保存.
            var mapper = YamlMapperFactory.builder().sparrowYaml(manager.sparrowYaml()).build()
                    .create(PluginConfig.ConfigDefinition.class, PluginConfig.ConfigDefinition::new);
            mapper.save(path, (PluginConfig.ConfigDefinition) current.get(null));
            assertTrue(Files.readString(path).contains("sparrow_sync:location"));
            config.reload();
            assertEquals(configured, PluginConfig.synchronization$discardUnknownData());

            Files.writeString(path, "config-version: '4'\nsynchronization:\n  discard-unknown-data: ['external:new']\n");
            config.reload();
            assertEquals(Set.of(DataKey.of("external", "new")), PluginConfig.synchronization$discardUnknownData());
            assertTrue(registry.shouldDropUnknown(DataKey.sparrow("location")));
            assertTrue(registry.shouldDropUnknown(DataKey.of("external", "book")));
            assertTrue(registry.shouldDropUnknown(apiKey));
            assertFalse(registry.shouldDropUnknown(DataKey.of("external", "new")));
        } finally {
            current.set(null, previous);
        }
    }
}
